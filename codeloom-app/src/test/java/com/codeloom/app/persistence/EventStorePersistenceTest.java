package com.codeloom.app.persistence;

import com.codeloom.app.support.AbstractPersistenceTest;
import com.codeloom.app.support.TestSessions;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.event.VerificationResult;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.StaleLeaseException;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import com.codeloom.realtime.event.EventCodecException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事件存储 + 执行租约的 fencing，真连 MySQL。
 *
 * <p>这个类是「水平扩展下不出问题」那句话的兑现处：**僵尸写入者被挡住**这件事，
 * 只有跑真库、并真的让两个 token 先后出现，才验证得了。
 *
 * <h2>为什么测试能造出"会话已经被接管"而不需要真的起两个实例</h2>
 * 因为接管在存储这一层等价于"发了一个更大的号"。所以
 * {@link #acquire} 调两次就是两个实例，不需要真的并发。
 */
class EventStorePersistenceTest extends AbstractPersistenceTest {

    private static final ProjectId PROJECT_ID =
            ProjectId.of("88888888-8888-8888-8888-888888888888");

    @Autowired
    private EventStore events;

    @Autowired
    private WorkspaceFence fence;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private WorkspaceRepository worktrees;

    // ------------------------------------------------------------------
    // 追加与读取
    // ------------------------------------------------------------------

    @Test
    @DisplayName("批量追加：seq 连续递增，且顺序与传入顺序一致")
    void appendAssignsSequentialSeqsInOrder() {
        Session session = savedSession();

        List<StoredEvent> appended = events.append(session.id(), List.of(
                new UserMessage("看一下 OrderService"),
                new AssistantMessage("我先读文件", "deepseek-flash"),
                new ToolCallRequested("call_1", "read_file", "{\"path\":\"A.java\"}"),
                new ToolResult("call_1", true, "class A {}", false, 0, 12L)),
                acquire(session));

        assertThat(appended).hasSize(4);
        assertThat(appended).extracting(StoredEvent::seq).isSorted();
        // 同一事务、同一条语句插进去的自增主键是连续的 —— 这是 event 表 seq「无洞」
        // 那个前提的正面证据。跨会话才会出现空洞（别的会话的插入夹在中间）。
        assertThat(appended.getLast().seq() - appended.getFirst().seq()).isEqualTo(3);

        // append 交出来的信封，和落库之后再读回来的**完全相等** —— 包括时间。
        // 这正是 append 该返回信封而不是只返回 seq 的理由：广播出去的那一条，
        // 必须和后来断线重连拉回来的那一条是同一个东西，否则订阅者两次会看到"两条"。
        assertThat(events.readAll(session.id())).isEqualTo(appended);
    }

    @Test
    @DisplayName("经 MySQL 的 JSON 列走一圈，事件仍然相等（键序被规范化过也认）")
    void eventsSurviveTheJsonColumn() {
        // MySQL 的 JSON 列会规范化对象键的顺序，所以这里验的不是"字节相同"而是
        // "反序列化出来相等" —— 而那正是我们从一开始就依赖的性质，也是 schema.sql
        // 里选择用 JSON 类型而不是 TEXT 时写下过的那个前提。
        Session session = savedSession();

        events.append(session.id(), List.of(
                new VerificationResult("verify-1", "mvn -q test", false, 1, "Tests run: 5, Failures: 1"),
                new ToolResult("call_1", true, "输出里有中文、引号\"和换行\n第二行", true, null, 37L)),
                acquire(session));

        assertThat(events.readAll(session.id())).extracting(StoredEvent::event).isEqualTo(List.of(
                new VerificationResult("verify-1", "mvn -q test", false, 1, "Tests run: 5, Failures: 1"),
                new ToolResult("call_1", true, "输出里有中文、引号\"和换行\n第二行", true, null, 37L)));
    }

    @Test
    @DisplayName("lastSeq / readAfter：断线补齐用的游标语义")
    void cursorSemanticsForReconnect() {
        Session session = savedSession();
        List<StoredEvent> appended = events.append(session.id(), List.of(
                new UserMessage("一"), new UserMessage("二"), new UserMessage("三")),
                acquire(session));

        long first = appended.getFirst().seq();
        long last = appended.getLast().seq();

        assertThat(events.lastSeq(session.id())).isEqualTo(last);
        // readAfter 是开区间：游标那条自己不再返回，否则重连时会重复渲染一条
        assertThat(events.readAfter(session.id(), first, 10)).hasSize(2);
        assertThat(events.readAfter(session.id(), last, 10)).isEmpty();
        assertThat(events.readAfter(session.id(), 0, 2)).hasSize(2);   // limit 生效
    }

    @Test
    @DisplayName("一条事件都没有时 lastSeq 是 0，不是 null —— 客户端拿它当初始游标")
    void emptySessionHasSeqZero() {
        assertThat(events.lastSeq(savedSession().id())).isZero();
    }

    @Test
    @DisplayName("空列表追加是空操作：什么都不写，也不碰租约")
    void appendingNothingIsANoOp() {
        Session session = savedSession();

        assertThat(events.append(session.id(), List.of(), acquire(session))).isEmpty();
        assertThat(events.lastSeq(session.id())).isZero();
    }

    // ------------------------------------------------------------------
    // fencing：这个类真正的重点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【安全约束】租约被接管后，旧 token 的写入被拒 —— 而且一行都没落库")
    void staleTokenIsRejectedAndNothingIsWritten() {
        Session session = savedSession();
        LeaseToken first = acquire(session);
        events.append(session.id(), new UserMessage("第一条"), first);

        // 第二个实例接管。存储这一层里，"接管"就等于"发了一个更大的号"
        LeaseToken second = acquire(session);
        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());

        // 僵尸写入者从 GC 停顿里醒来，它不知道自己已经失去执行权。
        // 断言打在那句话上（它带着号码）——**这是它唯一的口子**：把一个号码单开一个
        // 访问器出去，只为了让测试读得到，那是给测试开的生产 API
        assertThatThrownBy(() -> events.append(session.id(), new UserMessage("僵尸写的"), first))
                .isInstanceOf(StaleLeaseException.class)
                .hasMessageContaining(String.valueOf(first.fencingToken()));

        // 关键不是"报错了"，而是"什么都没写进去"：半途失败留下半条历史，
        // 比直接失败更难收拾
        assertThat(events.readAll(session.id()))
                .extracting(stored -> ((UserMessage) stored.event()).text())
                .containsExactly("第一条");

        // 新持有者照常能写，说明被挡住的只是那一个过期的号
        events.append(session.id(), new UserMessage("接管后写的"), second);
        assertThat(events.readAll(session.id())).hasSize(2);
    }

    @Test
    @DisplayName("【安全约束】拿 A 会话的合法 token 写 B 会话 —— 拦住")
    void tokenFromAnotherSessionIsRejected() {
        // 最容易被绕过的一种用法：token 本身没毛病，而写下去的是另一条会话。
        //
        // 共享工作区之后这两条会话**共用一棵树**（同一个人的两段对话），fence 校验
        // 必然通过 —— 于是这道会话比对成了唯一的拦网。
        Session alice = savedSession();
        Session bob = sameTreeAs(alice);

        assertThat(bob.workspaceId()).isEqualTo(alice.workspaceId());

        assertThatThrownBy(() -> events.append(bob.id(), new UserMessage("越权写入"), acquire(alice)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("租约与目标会话不匹配");

        assertThat(events.readAll(bob.id())).isEmpty();
    }

    @Test
    @DisplayName("发号单调递增，并且真的落在 workspace 表的 fencing_token 列上")
    void issuingIsMonotonicAndPersisted() {
        Session session = savedSession();

        long first = fence.issue(session.workspaceId());
        long second = fence.issue(session.workspaceId());

        assertThat(second).isGreaterThan(first);
        assertThat(rawFencingToken(session)).isEqualTo(second);
    }

    @Test
    @DisplayName("【安全约束】表里混进一行流式增量 → 读的时候炸，而不是让它流进回放")
    void ephemeralRowsAreRejectedOnRead() {
        // codec 现在解得开 delta（它要跨实例推送），所以"不许落库"这条规则必须由
        // 存储层来守 —— 只有它知道那一行是从 event 表里读出来的。
        // 编译期拦不住别人手工 insert，这里是第二道。
        Session session = savedSession();
        jdbc.update("""
                INSERT INTO `event` (session_id, `type`, payload, occurred_at)
                VALUES (?, 'ASSISTANT_DELTA', '{"text":"半个句"}', NOW(3))
                """, session.id().value());

        assertThatThrownBy(() -> events.readAll(session.id()))
                .isInstanceOf(EventCodecException.class)
                .hasMessageContaining("易失事件");
    }

    @Test
    @DisplayName("对不存在的工作区发号要报错，绝不能把连接上残留的号发出去")
    void issuingForUnknownWorkspaceFails() {
        // 这条钉的是一个具体的坑：发号用的是 MySQL 的 LAST_INSERT_ID(expr)，
        // 而它不是为这个用途设计的 —— UPDATE 一行都没命中时 expr 根本不会被求值，
        // 紧接着 SELECT LAST_INSERT_ID() 读回来的是这条连接更早的残留值。
        // 不问命中行数就把它当号发出去，会让一棵不相干的工作区莫名失去写入权。
        //
        // 号是**按树**发的（见 WorkspaceFence）：所以这里要造一个没落过库的树 id，
        // 而不是一条没落过库的会话 —— 那是两件事，而这一步正是把键提到树上的后果。
        WorkspaceId unknown = WorkspaceId.of(
                UserId.of("77777777-7777-7777-7777-777777777777"), PROJECT_ID);

        assertThatThrownBy(() -> fence.issue(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区不存在");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("【安全约束】没有事务时 assertValid 直接抛，而不是自己开一个、让那次加锁读白读")
    void assertValidRefusesToRunWithoutATransaction() {
        // 显式关掉测试自带的事务，模拟"有人在事务外调用它"。
        // 如果 assertValid 用的是 REQUIRED 而不是 MANDATORY，它会自己开一条事务、
        // 读完立刻释放锁，然后调用方在毫无保护的情况下写入 —— 而且看起来一切正常。
        //
        // 本方法刻意不碰数据库，所以关掉事务也不会留下未回滚的数据。
        LeaseToken token = new LeaseToken(SessionId.generate(),
                WorkspaceId.of(UserId.of("77777777-7777-7777-7777-777777777777"), PROJECT_ID),
                1L, "test-instance");

        assertThatThrownBy(() -> fence.assertValid(token))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("【共享工作区】同一个人的两条会话共用一个号：发号在树那一层单调，不按会话各算各的")
    void sessionsSharingATreeShareTheFenceToo() {
        // 两条会话共用一棵树之后，"按会话发号"和"按树发号"就分家了 —— 后果是：
        // 会话 B 拿到树锁、发到的号如果比 A 那一行自己的计数还小，停顿后醒来的 A
        // 就会**通过校验**，继续写一棵已经不属于它的树。
        Session first = savedSession();
        Session second = sameTreeAs(first);

        assertThat(second.workspaceId()).isEqualTo(first.workspaceId());

        long forFirst = fence.issue(first.workspaceId());
        long forSecond = fence.issue(second.workspaceId());

        assertThat(forSecond).isGreaterThan(forFirst);
        // 号记在树上，所以两个会话看到的是**同一个**当前值
        assertThat(rawFencingToken(first)).isEqualTo(forSecond);
        assertThat(rawFencingToken(second)).isEqualTo(forSecond);
    }

    // ------------------------------------------------------------------

    /** 造一条真会话**和它那棵树**。号是对树发的，所以两行都要落。 */
    private Session savedSession() {
        return TestSessions.persist(sessions, worktrees, SessionId.generate(), PROJECT_ID, OWNER,
                "D:/ws/" + UUID.randomUUID(), "basecommit0");
    }

    /**
     * 同一个人的另一条会话 —— 同一个项目、同一个 owner，所以**落在同一棵树上**。
     *
     * <p>这不是构造出来的边角：它就是"同一个人又开了一段新对话"。
     */
    private Session sameTreeAs(Session other) {
        return TestSessions.persist(sessions, worktrees, SessionId.generate(), other.projectId(),
                other.ownerId(), "D:/ws/" + UUID.randomUUID(), "basecommit0");
    }

    /** 发一个号并包成租约。调两次就等于"两个执行者先后接管了同一棵树"。 */
    private LeaseToken acquire(Session session) {
        return TestSessions.mint(session, fence);
    }

    private long rawFencingToken(Session session) {
        WorkspaceId id = session.workspaceId();
        Long value = jdbc.queryForObject(
                "SELECT fencing_token FROM workspace WHERE owner_id = ? AND project_id = ?",
                Long.class, id.ownerId().value(), id.projectId().value());
        return value == null ? -1L : value;
    }
}
