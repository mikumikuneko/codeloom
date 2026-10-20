package com.codeloom.app.persistence;

import com.codeloom.app.support.AbstractPersistenceTest;
import com.codeloom.app.support.TestSessions;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.TurnTokensUsed;
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
 * 事件存储与执行租约 fencing 的真 MySQL 测试。
 *
 * <p>“僵尸写入者被挡住”只有跑真库、让两个 token 先后出现才验得了。接管在存储层
 * 等价于“发了一个更大的号”，所以 {@link #acquire} 调两次就代表两个实例，
 * 不需要真的并发。见 {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
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
        // 同一事务、同一条语句插进去的自增主键是连续的，这是 event 表 seq“无洞”的
        // 正面证据；跨会话才会出现空洞。
        assertThat(appended.getLast().seq() - appended.getFirst().seq()).isEqualTo(3);

        // append 返回的信封与落库后读回来的完全相等，包括时间。广播出去的那条必须和
        // 断线重连拉回来的那条是同一个东西，否则订阅者会看到“两条”。
        assertThat(events.readAll(session.id())).isEqualTo(appended);
    }

    @Test
    @DisplayName("【便宜的闸】lastContextTokens 读的是最后一轮的读数；那一轮没读数就问不出来")
    void lastContextTokensReadsTheNewestSettledReading() {
        Session session = savedSession();

        assertThat(events.lastContextTokens(session.id()))
                .as("一次收尾都还没有 —— 新会话就该问不出来")
                .isEmpty();

        events.append(session.id(), List.of(new UserMessage("第一轮"), settledWith(12_345)),
                acquire(session));
        assertThat(events.lastContextTokens(session.id())).hasValue(12_345);

        // 取消的轮次只会留下开轮消息，不会写用量事件；不能把上一轮的读数当成这一轮的。
        // 这是刻意的降级：调用方退回读整条流自己往前找，而不是拿过期的数糊弄。
        events.append(session.id(), new UserMessage("第二轮"), acquire(session));
        assertThat(events.lastContextTokens(session.id())).isEmpty();

        // 服务商没报用量的正常收尾同样问不出来
        events.append(session.id(), List.of(settledWithoutReading()), acquire(session));
        assertThat(events.lastContextTokens(session.id())).isEmpty();
    }

    /** 一次收尾，带上下文读数。 */
    private static TurnTokensUsed settledWith(int contextTokens) {
        return new TurnTokensUsed(100, 20, 0, 0, contextTokens, 64_000, "deepseek-flash");
    }

    /** 一次没有读数的收尾：这一轮一次模型都没调成（被取消，或服务商没报用量）。 */
    private static TurnTokensUsed settledWithoutReading() {
        return new TurnTokensUsed(100, 20, 0, 0, null, null, "deepseek-flash");
    }

    @Test
    @DisplayName("经 MySQL 的 JSON 列走一圈，事件仍然相等（键序被规范化过也认）")
    void eventsSurviveTheJsonColumn() {
        // MySQL 的 JSON 列会规范化对象键顺序，所以验的是“反序列化出来相等”，
        // 而不是字节相同；这正是选 JSON 类型而非 TEXT 的前提。
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
    @DisplayName("readAfter：断线补齐用的游标语义")
    void cursorSemanticsForReconnect() {
        Session session = savedSession();
        List<StoredEvent> appended = events.append(session.id(), List.of(
                new UserMessage("一"), new UserMessage("二"), new UserMessage("三")),
                acquire(session));

        long first = appended.getFirst().seq();
        long last = appended.getLast().seq();

        // readAfter 是开区间：游标那条自己不再返回，否则重连时会重复渲染一条
        assertThat(events.readAfter(session.id(), first, 10)).hasSize(2);
        assertThat(events.readAfter(session.id(), last, 10)).isEmpty();
        assertThat(events.readAfter(session.id(), 0, 2)).hasSize(2);   // limit 生效
    }

    @Test
    @DisplayName("一条事件都没有时什么都读不回来 —— 游标从 0 开始是安全的")
    void emptySessionHasNoEvents() {
        assertThat(events.readAfter(savedSession().id(), 0, 10)).isEmpty();
    }

    @Test
    @DisplayName("空列表追加是空操作：什么都不写，也不碰租约")
    void appendingNothingIsANoOp() {
        Session session = savedSession();

        assertThat(events.append(session.id(), List.of(), acquire(session))).isEmpty();
        assertThat(events.readAfter(session.id(), 0, 10)).isEmpty();
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

        // 僵尸写入者从 GC 停顿里醒来，不知道已失去执行权。断言打在那条带号码的消息上，
        // 不为测试单开号码访问器 —— 那是给测试开生产 API。
        assertThatThrownBy(() -> events.append(session.id(), new UserMessage("僵尸写的"), first))
                .isInstanceOf(StaleLeaseException.class)
                .hasMessageContaining(String.valueOf(first.fencingToken()));

        // 关键不是报错，而是什么都没写进去：半途失败留下半条历史更难收拾。
        assertThat(events.readAll(session.id()))
                .extracting(stored -> ((UserMessage) stored.event()).text())
                .containsExactly("第一条");

        // 新持有者照常能写，说明被挡住的只是那一个过期的号
        events.append(session.id(), new UserMessage("接管后写的"), second);
        assertThat(events.readAll(session.id())).hasSize(2);
    }

    @Test
    @DisplayName("【安全约束】没发过的“未来号”同样被拒 —— 合法不等于比当前号大")
    void tokenThatWasNeverIssuedIsRejected() {
        Session session = savedSession();
        long current = acquire(session).fencingToken();

        // 伪造一个比当前号更大的 token。只拒绝“旧号”的写法会让它通过，
        // 而它和旧号一样从未被发出去过，不应该取得写入权
        LeaseToken forged = new LeaseToken(session.id(), session.workspaceId(),
                current + 1, "forged-instance");

        assertThatThrownBy(() -> events.append(session.id(), new UserMessage("伪造的写入"), forged))
                .isInstanceOf(StaleLeaseException.class)
                .hasMessageContaining(String.valueOf(current + 1));
        assertThat(events.readAll(session.id())).isEmpty();
    }

    @Test
    @DisplayName("【安全约束】拿 A 会话的合法 token 写 B 会话 —— 拦住")
    void tokenFromAnotherSessionIsRejected() {
        // token 本身合法，但写的是另一条会话。共享工作区后这两条会话共用一棵树，
        // fence 校验必然通过，会话比对成了唯一的拦网。
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
        // codec 解得开 delta（要跨实例推送），所以“不许落库”必须由存储层来守：
        // 只有它知道那一行是从 event 表读出来的。编译期拦不住手工 insert，这是第二道。
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
        // 发号用 MySQL 的 LAST_INSERT_ID(expr)：UPDATE 没命中任何行时 expr 不会被求值，
        // 紧接着读回的是连接上更早的残留值。不问命中行数就发号，会让一棵无关的工作区
        // 莫名失去写入权。号按树发（见 WorkspaceFence），所以这里造未落库的树 id。
        WorkspaceId unknown = WorkspaceId.of(
                UserId.of("77777777-7777-7777-7777-777777777777"), PROJECT_ID);

        assertThatThrownBy(() -> fence.issue(unknown))
                // 和 WorkspaceRepository.require 同一类：库里的行少了（500），不是请求写错（400）
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工作区不存在");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("【安全约束】没有事务时 assertValid 直接抛，而不是自己开一个、让那次加锁读白读")
    void assertValidRefusesToRunWithoutATransaction() {
        // 显式关掉测试事务，模拟事务外调用。若 assertValid 用 REQUIRED 而不是 MANDATORY，
        // 它会自开事务、读完立刻释放锁，调用方随后在无保护下写入，且看起来一切正常。
        // 本方法不碰数据库，关掉事务不会留下未回滚的数据。
        LeaseToken token = new LeaseToken(SessionId.generate(),
                WorkspaceId.of(UserId.of("77777777-7777-7777-7777-777777777777"), PROJECT_ID),
                1L, "test-instance");

        assertThatThrownBy(() -> fence.assertValid(token))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("【共享工作区】同一个人的两条会话共用一个号：发号在树那一层单调，不按会话各算各的")
    void sessionsSharingATreeShareTheFenceToo() {
        // 共用一棵树后，“按会话发号”与“按树发号”会分家：B 拿到的号若比 A 那一行自己的
        // 计数小，醒来的 A 会通过校验，继续写一棵已不属于它的树。
        Session first = savedSession();
        Session second = sameTreeAs(first);

        assertThat(second.workspaceId()).isEqualTo(first.workspaceId());

        long forFirst = fence.issue(first.workspaceId());
        long forSecond = fence.issue(second.workspaceId());

        assertThat(forSecond).isGreaterThan(forFirst);
        // 号记在树上，所以两个会话看到同一个当前值。
        assertThat(rawFencingToken(first)).isEqualTo(forSecond);
        assertThat(rawFencingToken(second)).isEqualTo(forSecond);
    }

    // ------------------------------------------------------------------

    /** 造一条真会话和它那棵树。号是对树发的，所以两行都要落。 */
    private Session savedSession() {
        return TestSessions.persist(sessions, worktrees, SessionId.generate(), PROJECT_ID, OWNER,
                "D:/ws/" + UUID.randomUUID(), "basecommit0");
    }

    /**
     * 同一个人的另一条会话：同一个项目、同一个 owner，所以落在同一棵树上。
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
