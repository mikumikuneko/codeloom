package com.codeloom.app.sse;

import com.codeloom.app.support.TestSessions;
import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.app.support.TestUsers;
import com.codeloom.realtime.sse.SessionStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「先补历史、再接上实时」这三条性质的真机测试（真 MySQL + 真 Redis）。
 *
 * <p>把 {@code replay-batch} 调到 2，是为了让补历史那一段**必然走多次循环** ——
 * 用默认的 500 的话，"分批拉到底"那个 while 在测试里永远只转一圈，
 * 而这个类里最容易写错的就是那一圈之后的边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
@TestPropertySource(properties = "codeloom.stream.replay-batch=2")
@Transactional
class SessionStreamServiceTest {

    private static final ProjectId PROJECT_ID = ProjectId.of("66666666-6666-6666-6666-666666666666");
    private static final long RECEIVE_TIMEOUT_SECONDS = 5;

    @Autowired
    private SessionStreamService streams;

    @Autowired
    private EventStore events;

    @Autowired
    private EventBus bus;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private WorkspaceRepository worktrees;

    @Autowired
    private WorkspaceFence fence;

    private Session session;
    private final BlockingQueue<EventEnvelope> sink = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() {
        SessionId id = SessionId.generate();
        session = TestSessions.persist(sessions, worktrees, id, PROJECT_ID, TestUsers.OWNER,
                "D:/ws/" + id.value());
        sink.clear();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("补历史要分批拉到底 —— 断线很久的客户端不能静默丢掉中间那段")
    void replaysEverythingInOrderAcrossBatches() {
        appendMessages(5);

        try (EventBus.Subscription ignored = streams.stream(session.id(), null, sink::add)) {
            // 补历史是**同步**的，所以方法一返回，队列里就该齐了
            assertThat(textsIn(sink)).containsExactly(
                    "第 1 条", "第 2 条", "第 3 条", "第 4 条", "第 5 条");
        }
    }

    @Test
    @DisplayName("带上 Last-Event-ID：只补它之后那些，之前的不要再发一遍")
    void replaysOnlyWhatTheClientMissed() {
        List<StoredEvent> appended = appendMessages(5);
        long lastSeen = appended.get(1).seq();   // 客户端说它已经收到第 2 条了

        try (EventBus.Subscription ignored = streams.stream(session.id(), lastSeen, sink::add)) {
            assertThat(textsIn(sink)).containsExactly("第 3 条", "第 4 条", "第 5 条");
        }
    }

    @Test
    @DisplayName("补完之后实时接上：新事件立刻推过来")
    void liveEventsFollowTheReplay() {
        appendMessages(2);

        try (EventBus.Subscription ignored = streams.stream(session.id(), null, sink::add)) {
            assertThat(textsIn(sink)).containsExactly("第 1 条", "第 2 条");

            StoredEvent fresh = appendMessages(1).getFirst();
            bus.publish(fresh);

            assertThat(awaitOne().seq()).isEqualTo(fresh.seq());
        }
    }

    @Test
    @DisplayName("交界处重复的那一条只发一次 —— 否则客户端会看到一条一模一样的")
    void theBoundaryEventIsSentOnlyOnce() throws Exception {
        List<StoredEvent> appended = appendMessages(2);
        StoredEvent boundary = appended.getLast();

        // 从"倒数第二条之前"开始补：最后那条会被当成历史发一次
        try (EventBus.Subscription ignored = streams.stream(session.id(), boundary.seq() - 1, sink::add)) {
            assertThat(textsIn(sink)).containsExactly("第 2 条");

            // 人为把它再从实时通道发一遍 —— 模拟"它既在补发的历史里、又赶上了订阅建立"
            // 那个交界窗口。没有游标去重的话，这里会变成两条
            bus.publish(boundary);

            assertThat(sink.poll(500, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    @DisplayName("流式增量走得通，而且到订阅者手里仍然没有 seq")
    void ephemeralDeltasFlowThrough() {
        try (EventBus.Subscription ignored = streams.stream(session.id(), null, sink::add)) {
            bus.publishEphemeral(session.id(), new AssistantDelta("正在打字"));

            EventEnvelope envelope = awaitOne();
            assertThat(envelope.seq()).isNull();
            assertThat(envelope.event()).isEqualTo(new AssistantDelta("正在打字"));
        }
    }

    @Test
    @DisplayName("订阅关掉之后实时事件就不再来了")
    void closingTheStreamStopsTheFlow() throws Exception {
        EventBus.Subscription subscription = streams.stream(session.id(), null, sink::add);
        subscription.close();

        StoredEvent fresh = appendMessages(1).getFirst();
        bus.publish(fresh);

        assertThat(sink.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    // ------------------------------------------------------------------

    private List<StoredEvent> appendMessages(int count) {
        // 每个测试自己发号：号只有对已存在的工作区才发得出来（见 MyBatisWorkspaceFence#issue）
        LeaseToken token = TestSessions.mint(session, fence);
        List<PersistentEvent> batch = IntStream.rangeClosed(1, count)
                .mapToObj(i -> (PersistentEvent) new UserMessage("第 " + i + " 条"))
                .toList();
        return events.append(session.id(), batch, token);
    }

    /** 把队列里**当前已有**的都取出来 —— 补历史是同步的，所以这个方法对那一段是确定的。 */
    private static List<String> textsIn(BlockingQueue<EventEnvelope> queue) {
        List<String> texts = new ArrayList<>();
        for (EventEnvelope envelope = queue.poll();
             envelope != null;
             envelope = queue.poll()) {
            texts.add(((UserMessage) envelope.event()).text());
        }
        return texts;
    }

    private EventEnvelope awaitOne() {
        try {
            EventEnvelope envelope = sink.poll(RECEIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(envelope).as("没有等到实时事件").isNotNull();
            return envelope;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待事件时被中断", e);
        }
    }
}
