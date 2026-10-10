package com.codeloom.app.turn;

import com.codeloom.app.support.AbstractPersistenceTest;
import com.codeloom.app.support.TestSessions;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.ModelChanged;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「落库即宣告」在**真库 + 真总线**上的兑现处。
 *
 * <h2>为什么这个类**不开**测试事务</h2>
 * 要验的恰好是"提交之后才发"，而测试事务是回滚的 —— 那样一条都不会发出去，
 * 测什么都是空的。所以这一整个类用 {@code NOT_SUPPORTED} 跑，写下来就是真写下来，
 * 收尾由 {@link #dropWhatWasCommitted()} 逐张表删干净。
 *
 * <p>机制那一半（登记顺序、没有事务时当场发、广播失败不抛）在
 * {@code EventAnnouncerTest} 里；两条一起才盖住"机制对"和"这条写入路径真的走了它"。
 */
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionWriterAnnouncementTest extends AbstractPersistenceTest {

    @Autowired
    private SessionWriter writer;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private WorkspaceRepository worktrees;

    @Autowired
    private WorkspaceFence fence;

    @Autowired
    private EventBus bus;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<Session> written = new ArrayList<>();
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();

    @AfterEach
    void dropWhatWasCommitted() {
        subscriptions.forEach(EventBus.Subscription::close);
        subscriptions.clear();
        for (Session session : written) {
            jdbc.update("DELETE FROM event WHERE session_id = ?", session.id().value());
            jdbc.update("DELETE FROM session WHERE id = ?", session.id().value());
            jdbc.update("DELETE FROM workspace WHERE owner_id = ? AND project_id = ?",
                    session.ownerId().value(), session.projectId().value());
        }
        written.clear();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【这一个从前是漏的】回滚落了库，正在看的人当场就收到了那条 SessionRewound")
    void rewindIsAnnounced() throws Exception {
        Session session = newSession();
        BlockingQueue<EventEnvelope> live = watch(session);

        writer.rewind(session, "abc1234", 0, 1L, OWNER, TestSessions.mint(session, fence));

        assertThat(collect(live, 1)).extracting(EventEnvelope::event)
                .containsExactly(new SessionRewound("abc1234", 1L, OWNER));
    }

    @Test
    @DisplayName("【这一个从前也是漏的】换模型落了库，订阅者也收到了")
    void modelChangeIsAnnounced() throws Exception {
        Session session = newSession();
        BlockingQueue<EventEnvelope> live = watch(session);

        writer.changeModel(session, model("model-b"), TestSessions.mint(session, fence));

        assertThat(collect(live, 1)).extracting(EventEnvelope::event)
                .containsExactly(new ModelChanged(TestSessions.DEFAULT_MODEL.modelId(), "model-b"));
    }

    @Test
    @DisplayName("一次写入里的两条事件，按落库顺序发 —— 顺序反了订阅端会把前一条当成重复丢掉")
    void twoEventsInOneWriteArriveInSeqOrder() throws Exception {
        Session session = newSession();
        BlockingQueue<EventEnvelope> live = watch(session);

        writer.startSession(session, worktrees.require(session.workspaceId()),
                TestSessions.mint(session, fence));

        List<EventEnvelope> received = collect(live, 2);
        assertThat(received).hasSize(2);
        assertThat(received).extracting(EventEnvelope::seq).isSorted();
    }

    @Test
    @DisplayName("事务回滚了就不发 —— 那种「已经落库」只是假象")
    void aRolledBackWriteIsNeverAnnounced() throws Exception {
        Session session = newSession();
        BlockingQueue<EventEnvelope> live = watch(session);

        new TransactionTemplate(transactions).execute(status -> {
            writer.changeModel(session, model("model-a"), TestSessions.mint(session, fence));
            status.setRollbackOnly();
            return null;
        });
        // 一次真提交的写入跟在后面。它到了、而它前面那条没到 —— 就说明那条从来没发出去过，
        // 不用靠"等一会儿看看有没有"（那种等待要么太短要么白等）
        writer.changeModel(session, model("model-b"), TestSessions.mint(session, fence));

        assertThat(collect(live, 1)).extracting(EventEnvelope::event)
                .containsExactly(new ModelChanged(TestSessions.DEFAULT_MODEL.modelId(), "model-b"));
    }

    // ------------------------------------------------------------------

    private Session newSession() {
        Session session = TestSessions.persist(sessions, worktrees, SessionId.generate(),
                ProjectId.generate(), OWNER, "D:/ws/" + UUID.randomUUID(), "basecommit0");
        written.add(session);
        return session;
    }

    private BlockingQueue<EventEnvelope> watch(Session session) {
        BlockingQueue<EventEnvelope> live = new LinkedBlockingQueue<>();
        subscriptions.add(bus.subscribe(session.id(), live::add));
        return live;
    }

    private static ModelConfig model(String modelId) {
        return new ModelConfig(ProviderId.of("deepseek"), modelId,
                TestSessions.DEFAULT_MODEL.systemPrompt());
    }

    /** 收到够 {@code expected} 条就返回；到点还没齐就把已经收到的交出去，让断言去说差在哪。 */
    private static List<EventEnvelope> collect(BlockingQueue<EventEnvelope> queue, int expected)
            throws InterruptedException {
        List<EventEnvelope> all = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (all.size() < expected && System.nanoTime() < deadline) {
            EventEnvelope envelope = queue.poll(200, TimeUnit.MILLISECONDS);
            if (envelope != null) {
                all.add(envelope);
            }
        }
        return all;
    }
}
