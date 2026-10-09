package com.codeloom.app.turn;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.ChatMessage;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TodoListUpdated;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话的活状态（跨轮那条投影）。
 *
 * <p>这里用的是一个只在内存里的 {@link EventStore} —— 要盯的是**缓存**那层的行为：
 * 复用它、补读它、什么时候把它作废重来。数据库那一层由别的测试盯着。
 */
class SessionProjectionsTest {

    private static final SessionId SESSION = SessionId.of("11111111-1111-1111-1111-111111111111");
    private static final String PROMPT = "你是助手。";

    private final FakeStore events = new FakeStore();

    /**
     * 用户表的最小替身。**这条测试不关心用户名** —— 投影里唯一查它的一处是
     * "别人捎来的留言"要标来源（见 {@link ContextAssembler}），而这里没有留言。
     */
    private static final UserRepository NO_USERS = new UserRepository() {
        @Override
        public void save(User user) {
            throw new UnsupportedOperationException("这条测试不写用户");
        }

        @Override
        public Optional<User> findById(UserId id) {
            return Optional.empty();
        }

        @Override
        public Optional<User> findByUsername(String username) {
            return Optional.empty();
        }

        @Override
        public boolean existsByUsername(String username) {
            return false;
        }

        @Override
        public boolean existsByDisplayName(String displayName) {
            return false;
        }
    };
    private SessionProjections projections;

    @BeforeEach
    void setUp() {
        projections = new SessionProjections(events, NO_USERS);
    }

    private static Session sessionWith(String systemPrompt) {
        return Session.create(SESSION, ProjectId.of("22222222-2222-2222-2222-222222222222"),
                UserId.of("33333333-3333-3333-3333-333333333333"),
                new ModelConfig(ProviderId.of("deepseek"), "deepseek-flash", systemPrompt));
    }

    /**
     * 拿到的投影和"从整条流从头建一条"必须**逐字符相同**。
     *
     * <p>这是整个缓存唯一的正确性判据：它错了不会报错，只会让模型看见的上下文
     * 重复一段或者少一段。
     */
    private void assertSameAsFromScratch(ContextAssembler.Projection cached) {
        ContextAssembler.Projection fresh = new ContextAssembler().projection(PROMPT);
        fresh.fold(events.all);
        List<ChatMessage> fromCache = new ArrayList<>();
        List<ChatMessage> fromScratch = new ArrayList<>();
        cached.advance(List.of(), fromCache);
        fresh.advance(List.of(), fromScratch);
        assertThat(fromCache).isEqualTo(fromScratch);
    }

    @Test
    @DisplayName("第二次拿还是那条投影，内容与从头建的一模一样")
    void reusesTheSameProjection() {
        events.append(new UserMessage("第一句"));
        events.append(new AssistantMessage("答一", null));

        ContextAssembler.Projection first = projections.acquire(sessionWith(PROMPT));
        ContextAssembler.Projection second = projections.acquire(sessionWith(PROMPT));

        assertThat(second).isSameAs(first);
        assertThat(projections.liveCount()).isEqualTo(1);
        assertSameAsFromScratch(second);
    }

    @Test
    @DisplayName("库里被别处追加了事件 → 下次拿的时候补读进来（不重读整条流）")
    void catchesUpWithEventsWrittenElsewhere() throws Exception {
        events.append(new UserMessage("第一句"));
        ContextAssembler.Projection projection = projections.acquire(sessionWith(PROMPT));

        // 另一个实例往这条会话写了事件（同步、合并、对方的操作都会这样）
        events.append(new UserMessage("别处写进来的一句"));

        // 再拿的时候补读到，而且**只读了新增那一截**（见 FakeStore.readAfterCalls）
        ContextAssembler.Projection again = projections.acquire(sessionWith(PROMPT));
        assertThat(again).isSameAs(projection);
        assertThat(events.readAfterCalls).containsExactly(0L, 1L);
        assertSameAsFromScratch(again);
    }

    @Test
    @DisplayName("补读到的是一次压缩 → 投影自己推倒重来，结果仍与从头相同")
    void aCompactionArrivingByCatchUpRebuilds() {
        events.append(new UserMessage("第一句"));
        events.append(new AssistantMessage("答一", null));
        ContextAssembler.Projection projection = projections.acquire(sessionWith(PROMPT));

        events.append(new ContextCompacted(2, "前面聊过这些"));

        projections.acquire(sessionWith(PROMPT));
        assertThat(projection).isSameAs(projections.acquire(sessionWith(PROMPT)));
        assertSameAsFromScratch(projection);
        List<ChatMessage> messages = new ArrayList<>();
        projection.advance(List.of(), messages);
        assertThat(messages).extracting(ChatMessage::content)
                .anyMatch(content -> content.contains("前面聊过这些"))
                .noneMatch(content -> content.contains("第一句"));
    }

    @Test
    @DisplayName("清单跟着会话一路走：换个调用方来拿，还是同一份清单")
    void theTodoListRidesAlong() {
        events.append(new TodoListUpdated(List.of(
                new TodoListUpdated.Item("跑测试", TodoListUpdated.State.IN_PROGRESS))));

        List<ChatMessage> messages = new ArrayList<>();
        projections.acquire(sessionWith(PROMPT)).advance(List.of(), messages);

        assertThat(messages.getLast().content())
                .contains("当前任务清单")
                .contains("跑测试");
    }

    @Test
    @DisplayName("系统提示词变了 → 那条投影作废重建（它是整个前缀的地基）")
    void aChangedSystemPromptInvalidatesTheProjection() {
        events.append(new UserMessage("第一句"));
        ContextAssembler.Projection before = projections.acquire(sessionWith(PROMPT));

        ContextAssembler.Projection after = projections.acquire(sessionWith("换了一份提示词。"));

        assertThat(after).isNotSameAs(before);
        List<ChatMessage> messages = new ArrayList<>();
        after.advance(List.of(), messages);
        assertThat(messages.getFirst().content()).isEqualTo("换了一份提示词。");
    }

    // ------------------------------------------------------------------

    /**
     * 只在内存里的事件表。刻意实现成"**和真表一样按 seq 分页**"：
     * 补读那条路最容易出的错是漏掉中间那一页，而那正是分页才有的错。
     */
    private static final class FakeStore implements EventStore {

        private final List<StoredEvent> all = new ArrayList<>();
        private final List<Long> readAfterCalls = new ArrayList<>();
        private long nextSeq = 1;

        private void append(PersistentEvent event) {
            all.add(new StoredEvent(SESSION, nextSeq++, Instant.EPOCH, event));
        }

        @Override
        public List<StoredEvent> readAfter(SessionId sessionId, long afterSeq, int limit) {
            readAfterCalls.add(afterSeq);
            return all.stream()
                    .filter(stored -> stored.seq() > afterSeq)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<StoredEvent> readAll(SessionId sessionId) {
            return List.copyOf(all);
        }

        @Override
        public long lastSeq(SessionId sessionId) {
            return all.isEmpty() ? 0 : all.getLast().seq();
        }

        @Override
        public StoredEvent append(SessionId sessionId, PersistentEvent event, LeaseToken token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StoredEvent> append(SessionId sessionId, List<PersistentEvent> events,
                                        LeaseToken token) {
            throw new UnsupportedOperationException();
        }
    }
}
