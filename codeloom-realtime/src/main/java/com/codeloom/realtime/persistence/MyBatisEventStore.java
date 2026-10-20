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
 * <p>fencing token 的校验落在这里，而且只能在这里：Redis 锁挡的是同时执行，挡不住
 * 实例停顿到租约过期、被接管后又醒来的写入者。它不知道自己已经失去执行权，所以每次写入
 * 都带 {@link LeaseToken}，由 {@link WorkspaceFence} 在加锁读里确认仍是当前有效值，
 * 否则抛 {@link com.codeloom.domain.port.StaleLeaseException}。锁和号都挂在会话所在的
 * 那棵树上（{@link LeaseToken#workspaceId()}），不挂在会话上。
 *
 * <p>{@code append} 的形参是 {@link PersistentEvent} 而不是 {@link Event}，易失的
 * {@link com.codeloom.domain.event.AssistantDelta} 在编译期就进不来。
 *
 * <p>两个 {@code append} 都必须是 {@code @Transactional}：
 * {@link WorkspaceFence#assertValid(LeaseToken)} 的传播行为是 {@code MANDATORY}，
 * 没有事务会直接抛异常而不是自行开启一个。自行开启会让
 * 加锁读完就释放，随后的写入处于无保护状态。锁必须持有到本事务提交。
 *
 * <p>理由见 {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
 */
@Repository
public class MyBatisEventStore implements EventStore, EventDiscard {

    private final EventMapper mapper;
    private final WorkspaceFence fence;

    /**
     * 无状态、无配置，也没有第二种实现，所以直接持有而不是注入。
     */
    private final EventCodec codec = new EventCodec();

    public MyBatisEventStore(EventMapper mapper, WorkspaceFence fence) {
        this.mapper = mapper;
        this.fence = fence;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public StoredEvent append(SessionId sessionId, PersistentEvent event, LeaseToken token) {
        return append(sessionId, List.of(event), token).getFirst();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException token 属于另一条会话
     * @throws com.codeloom.domain.port.StaleLeaseException token 已不是这棵树当前有效的租约
     * @throws IllegalStateException 插入后没有回填自增主键
     */
    @Override
    @Transactional
    public List<StoredEvent> append(SessionId sessionId, List<PersistentEvent> events, LeaseToken token) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(token, "token");

        // 拿 A 会话的合法 token 去写 B 会话是这套机制最容易被绕过的用法：token 本身合法，
        // fence 校验的是那棵树，而 A 和 B 可能共用同一棵树，于是校验会通过，写下去的却是 B。
        if (!sessionId.equals(token.sessionId())) {
            throw new IllegalArgumentException(
                    "租约与目标会话不匹配：token 属于会话 " + token.sessionId()
                            + "，却要写入会话 " + sessionId);
        }
        if (events.isEmpty()) {
            return List.of();
        }

        // 校验必须在插入之前。assertValid 是加锁读，会把树的行锁到本事务结束；反过来
        // 的话，锁到插入之后才拿到，中间窗口足够已失去租约的实例写入。
        fence.assertValid(token);

        // 同一批共用一个落库时间；批内先后由 seq 决定。DATETIME(3) 的精度下，同一批
        // 本来也分不出先后。
        //
        // truncatedTo(MILLIS) 不能省：列是 DATETIME(3)，而 Instant 通常精确到微秒。
        // 不截断的话，append 交出去的信封与之后从库里读回来的那条时间不相等；订阅者
        // 拿到前者、断线重连拉到后者，会表现为同一条事件出现两次且时间不同。
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
    public OptionalInt lastContextTokens(SessionId sessionId) {
        EventRow row = mapper.findLastOfType(sessionId.value(), EventType.TURN_TOKENS_USED.name());
        if (row == null) {
            return OptionalInt.empty();
        }
        // 最后一轮可能被取消、没调用过模型，收尾不会写 TurnTokensUsed。只看最后一条
        // 用量事件会把上一轮的读数当成这一轮的，所以还要确认它不早于最后一次开轮事件
        EventRow turnStart = mapper.findLastTurnStart(sessionId.value());
        if (turnStart != null && turnStart.id() > row.id()) {
            return OptionalInt.empty();
        }
        return toStoredEvent(row).event() instanceof TurnTokensUsed used && used.hasContext()
                ? OptionalInt.of(used.contextTokens())
                : OptionalInt.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>它不属于 {@link EventStore}，因为该接口承诺 append-only 且没有例外；删除整条
     * 会话是另一件事，单独成接口后，那份承诺仍然成立，删除路径也有一个可搜索的名字。
     * 同一个类实现两者，是因为它们共用 {@link EventMapper} 和编解码假设。
     *
     * <p>刻意不加 {@code @Transactional}：它必须跟随调用方的事务。单独提交会留下
     * “会话行删了、事件没删”的半截状态，而会话没了之后没有任何路径能修回来。
     */
    @Override
    public void bySession(SessionId sessionId) {
        mapper.deleteBySession(sessionId.value());
    }

    /**
     * {@inheritDoc}
     *
     * <p>不看影响行数：删出 0 行是正常结果（这个人本来就没在这个项目里跑过会话），不是错误。
     * 必须在删除 {@code session} 行之前调用。
     */
    @Override
    public void byProjectAndOwner(ProjectId projectId, UserId ownerId) {
        mapper.deleteByProjectAndOwner(projectId.value(), ownerId.value());
    }

    /**
     * 把刚插进去的行组装成信封；回填的 seq 缺一个都不行。
     *
     * <p>seq 是后续所有读取的游标（断线补齐与 SSE 的 {@code Last-Event-ID}），未知的 seq
     * 会把客户端永久卡在错误位置。宁可在这里抛异常，也不把 null 传出去。
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

        // “易失事件不许落库”住在读侧而不是 codec：codec 服务两条线，流式增量本来就要走
        // Pub/Sub。真正错的是从 event 表里读出一行 delta；手工 insert 绕过编译期，所以
        // 这里按 sealed 层级再拦一次。
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
