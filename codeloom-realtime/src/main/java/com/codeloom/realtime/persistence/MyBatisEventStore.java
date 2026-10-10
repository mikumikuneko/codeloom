package com.codeloom.realtime.persistence;

import com.codeloom.domain.event.EphemeralEvent;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.EventType;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.port.EventDiscard;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import com.codeloom.realtime.event.EventCodec;
import com.codeloom.realtime.event.EventCodecException;
import com.codeloom.realtime.event.EventTypes;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * {@link EventStore} 的持久化实现。
 *
 * <h2>fencing token 的校验就落在这里，而且是它唯一该在的地方</h2>
 * 锁（Redis）挡的是"同时有两个人执行"，挡不住这种情况：实例 A 长时间 GC 停顿 →
 * 租约过期 → 实例 B 接管 → A 醒过来，**它不知道自己已经失去执行权**，继续跑完并落数据。
 * 这个缺口修不掉，因为"锁的过期"和"持有者的知情"之间必然有时延 —— 所以改成校验写入：
 * 每次写都带上 {@link LeaseToken}，由 {@link WorkspaceFence} 在**加锁读**里确认它仍是
 * 当前有效值，不是就抛 {@link com.codeloom.domain.port.StaleLeaseException}。
 *
 * <p>锁和号都挂在这条会话所在的**那棵树**上（{@link LeaseToken#workspaceId()}），
 * 不挂在会话上 —— 理由见 {@code WorkspaceFence} 的类注释里那个反例。
 *
 * <p>{@code append} 的形参类型是 {@code PersistentEvent} 而不是 {@code Event}：
 * 逐 token 的 {@code AssistantDelta} 连编译都过不去，不需要靠评审或注释去拦。
 *
 * <h2>两个方法都必须是 {@code @Transactional}，理由不是"写得整齐"</h2>
 * {@code WorkspaceFence.assertValid} 的传播行为是 {@code MANDATORY}：它要的就是
 * "必须已经在一个事务里"。没有事务时它直接抛 {@code IllegalTransactionStateException}，
 * 而不是自己开一个 —— 后者会让加锁读完就释放，然后我们在毫无保护的情况下写入，
 * 而且看起来一切正常。那道锁要一直持有到本事务提交，才覆盖得住下面的插入。
 */
@Repository
public class MyBatisEventStore implements EventStore, EventDiscard {

    private final EventMapper mapper;
    private final WorkspaceFence fence;

    /**
     * 无状态、无配置，不是需要被替换的协作者，所以直接持有而不是注入 ——
     * 它没有第二种实现，注入只会让人以为有。
     */
    private final EventCodec codec = new EventCodec();

    public MyBatisEventStore(EventMapper mapper, WorkspaceFence fence) {
        this.mapper = mapper;
        this.fence = fence;
    }

    @Override
    @Transactional
    public StoredEvent append(SessionId sessionId, PersistentEvent event, LeaseToken token) {
        return append(sessionId, List.of(event), token).getFirst();
    }

    @Override
    @Transactional
    public List<StoredEvent> append(SessionId sessionId, List<PersistentEvent> events, LeaseToken token) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(token, "token");

        // 拿 A 会话的合法 token 去写 B 会话，是这套机制最容易被绕过的一种用法：
        // token 本身没毛病、校验也会通过（fence 校验的是那棵树，而 A 和 B 可能**共用
        // 同一棵树**），而写下去的是 B。共享工作区之后这道校验更是必需的。
        if (!sessionId.equals(token.sessionId())) {
            throw new IllegalArgumentException(
                    "租约与目标会话不匹配：token 属于会话 " + token.sessionId()
                            + "，却要写入会话 " + sessionId);
        }
        if (events.isEmpty()) {
            return List.of();
        }

        // 校验必须在插入之前，顺序不能反。assertValid 是加锁读，它把这棵树的行
        // 锁到本事务结束；反过来的话，锁要等到插入之后才拿到，中间那段窗口就够已失去
        // 租约的实例写入了。
        fence.assertValid(token);

        // 同一批共用一个落库时间。批内的先后由 seq 决定，不由时间决定 ——
        // 在 DATETIME(3) 的毫秒精度下，同一批本来也分不出先后。
        //
        // truncatedTo(MILLIS) 不能省：列是 DATETIME(3)，而 Instant.now() 在多数平台上
        // 精度到微秒。不截的话，append 交出去的这个信封（微秒）和之后从库里读回来的
        // 那条（毫秒）**时间就不相等了** —— 而订阅者拿到的是前者、断线重连拉的是后者，
        // 两者对不上会在"同一条事件出现两次、时间还不一样"这种地方显形。
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<EventInsert> inserts = new ArrayList<>(events.size());
        for (PersistentEvent event : events) {
            EventCodec.EncodedEvent encoded = codec.encode(event);
            inserts.add(new EventInsert(sessionId.value(), encoded.type().name(),
                    encoded.payload(), occurredAt));
        }

        mapper.insertAll(inserts);
        return storedEvents(sessionId, events, inserts, occurredAt);
    }

    @Override
    public List<StoredEvent> readAfter(SessionId sessionId, long afterSeq, int limit) {
        return mapper.findAfter(sessionId.value(), afterSeq, limit).stream()
                .map(this::toStoredEvent)
                .toList();
    }

    @Override
    public List<StoredEvent> readAll(SessionId sessionId) {
        return mapper.readAll(sessionId.value()).stream()
                .map(this::toStoredEvent)
                .toList();
    }

    @Override
    public long lastSeq(SessionId sessionId) {
        return mapper.lastSeq(sessionId.value());
    }

    @Override
    public OptionalInt lastContextTokens(SessionId sessionId) {
        EventRow row = mapper.findLastOfType(sessionId.value(), EventType.TURN_TOKENS_USED.name());
        if (row == null) {
            return OptionalInt.empty();
        }
        // 最后一条收尾可能压根没调过模型（被取消），那种读数就是空 —— 按"问不出来"处理，
        // 由调用方决定退回去算一遍还是继续
        return toStoredEvent(row).event() instanceof TurnTokensUsed used && used.hasContext()
                ? OptionalInt.of(used.contextTokens())
                : OptionalInt.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <h2>为什么它在这个类上、却不是 {@code EventStore} 的一部分</h2>
     * 因为这两个接口的**承诺不一样**，而承诺不该混在一个类型里：
     * {@code EventStore} 的类注释写着 append-only，那是它的性质，没有例外；
     * 而"删掉一整条会话"是另一件事，它有自己的一份理由（见 {@link EventDiscard}）。
     * 拆成两个接口之后，{@code EventStore} 那句承诺仍然是真的，
     * 而这个例外有一个能被 grep 到的名字 —— 谁在删事件，搜得到。
     *
     * <p>同一个实现类同时实现两者，是因为它们共用 {@link EventMapper}
     * 和那套编解码假设；分成两个类只会让"谁在碰这张表"变成两个答案。
     *
     * <p>**不加 {@code @Transactional}**：它必须跟着调用方的事务走。
     * 单独提交的话，"会话行删了、事件没删"这种半截状态就能落在库里 ——
     * 而那种状态没有任何路径能修回来（会话没了，谁也不会再去碰它的事件）。
     */
    @Override
    public void bySession(SessionId sessionId) {
        mapper.deleteBySession(sessionId.value());
    }

    /** {@inheritDoc} —— 同样不看影响行数：删出 0 行是正常结果（这个人本来就没在这项目里跑过会话），不是错误。 */
    @Override
    public void byProjectAndOwner(ProjectId projectId, UserId ownerId) {
        mapper.deleteByProjectAndOwner(projectId.value(), ownerId.value());
    }

    /**
     * 把刚插进去的行组装成信封。**回填的 seq 缺一个都不行。**
     *
     * <p>seq 是后续所有读取的游标（断线补齐从这里接着拉、SSE 把它放进 {@code Last-Event-ID}），
     * 一个"未知的 seq"会把客户端永久卡在一个错误的位置上。所以宁可在这里炸，
     * 也不要把 null 传出去。
     */
    private static List<StoredEvent> storedEvents(SessionId sessionId, List<PersistentEvent> events,
                                                  List<EventInsert> inserts, Instant occurredAt) {
        List<StoredEvent> stored = new ArrayList<>(inserts.size());
        for (int i = 0; i < inserts.size(); i++) {
            Long id = inserts.get(i).getId();
            if (id == null) {
                throw new IllegalStateException(
                        "event 插入后没有回填自增主键 —— 它同时是 StoredEvent.seq，不能是未知值");
            }
            stored.add(new StoredEvent(sessionId, id, occurredAt, events.get(i)));
        }
        return List.copyOf(stored);
    }

    private StoredEvent toStoredEvent(EventRow row) {
        Event event = codec.decode(parseType(row.type()), row.payload());

        // 「易失事件不许落库」这条规则住在**这里**，不住在 codec 里。
        // codec 服务两条线（见 EventCodec.decode），流式增量本来就要走 Pub/Sub 那一条 ——
        // 而"从 event 表里读出一行 delta"才是真正错的事。
        // 编译期拦不住别人手工 insert，所以这里用 sealed 层级再拦一次。
        if (event instanceof EphemeralEvent) {
            throw new EventCodecException(
                    "event 表里出现了易失事件：" + row.type() + "（seq " + row.id() + "）—— 谁写进去的？");
        }
        return new StoredEvent(SessionId.of(row.sessionId()), row.id(), row.occurredAt(), event);
    }

    private static EventType parseType(String raw) {
        // 解析逻辑收在 EventTypes 里；读信封那条路用同一个实现，只是 where 不同
        return EventTypes.parse(raw, "event 表里");
    }
}
