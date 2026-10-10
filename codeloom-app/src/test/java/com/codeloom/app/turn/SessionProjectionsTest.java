package com.codeloom.app.turn;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TodoListUpdated;
import com.codeloom.domain.event.ToolResultsCleared;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

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
     * 装配器。**这条测试不关心用户名** —— 投影里唯一查它的一处是"别人捎来的留言"
     * 要标来源（见 {@link ContextAssembler}），而这里没有留言，所以明写"不要名字"。
     */
    private static final ContextAssembler NAMELESS = new ContextAssembler(userId -> null);

    private SessionProjections projections;

    @BeforeEach
    void setUp() {
        projections = new SessionProjections(events, NAMELESS, 128, DataSize.ofMegabytes(32));
    }

    private static Session sessionWith(String systemPrompt) {
        return sessionWith(SESSION, systemPrompt);
    }

    private static Session sessionWith(SessionId id, String systemPrompt) {
        return Session.create(id, ProjectId.of("22222222-2222-2222-2222-222222222222"),
                UserId.of("33333333-3333-3333-3333-333333333333"),
                new ModelConfig(ProviderId.of("deepseek"), "deepseek-flash", systemPrompt));
    }

    private void assertSameAsFromScratch(ContextAssembler.Projection cached) {
        assertSameAsFromScratch(SESSION, cached);
    }

    /**
     * 拿到的投影和"从整条流从头建一条"必须**逐字符相同**。
     *
     * <p>这是整个缓存唯一的正确性判据：它错了不会报错，只会让模型看见的上下文
     * 重复一段或者少一段。
     */
    private void assertSameAsFromScratch(SessionId sessionId, ContextAssembler.Projection cached) {
        ContextAssembler.Projection fresh = new ContextAssembler(userId -> null).projection(PROMPT);
        fresh.fold(events.of(sessionId));
        List<LlmMessage> fromCache = new ArrayList<>();
        List<LlmMessage> fromScratch = new ArrayList<>();
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
        assertThat(projections.liveReport().count()).isEqualTo(1);
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
        List<LlmMessage> messages = new ArrayList<>();
        projection.advance(List.of(), messages);
        assertThat(messages).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("前面聊过这些"))
                .noneMatch(content -> content.contains("第一句"));
    }

    @Test
    @DisplayName("【分页】改写类事件落在第二页时，重来拿的是整条流 —— 不是手里那一页")
    void aRewriteBeyondTheFirstPageStillRebuildsFromTheWholeStream() {
        // 一页 500 条，前 600 条把改写事件顶到第二页去。补读那一趟手里只有第二页，
        // 而"推倒重来"要的是整条流 —— 拿那一片当整条流，前面 500 条会静默消失
        for (int i = 1; i <= 600; i++) {
            events.append(new UserMessage("第 " + i + " 句"));
        }
        ContextAssembler.Projection projection = projections.acquire(sessionWith(PROMPT));

        events.append(new ToolResultsCleared(List.of("call_1")));

        projections.acquire(sessionWith(PROMPT));
        assertSameAsFromScratch(projection);
    }

    @Test
    @DisplayName("清单跟着会话一路走：换个调用方来拿，还是同一份清单")
    void theTodoListRidesAlong() {
        events.append(new TodoListUpdated(List.of(
                new TodoListUpdated.Item("跑测试", TodoListUpdated.State.IN_PROGRESS))));

        List<LlmMessage> messages = new ArrayList<>();
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
        List<LlmMessage> messages = new ArrayList<>();
        after.advance(List.of(), messages);
        assertThat(messages.getFirst().content()).isEqualTo("换了一份提示词。");
    }

    // ------------------------------------------------------------------
    // 上限

    @Test
    @DisplayName("【上限】超了就把**最久没用过**的那条放下；被放下的下次是重建")
    void evictionDropsTheLeastRecentlyUsed() {
        SessionId a = SessionId.generate();
        SessionId b = SessionId.generate();
        SessionId c = SessionId.generate();
        events.append(a, new UserMessage("a 说的话"));
        events.append(b, new UserMessage("b 说的话"));
        events.append(c, new UserMessage("c 说的话"));

        SessionProjections capped =
                new SessionProjections(events, NAMELESS, 2, DataSize.ofMegabytes(32));

        capped.acquire(sessionWith(a, PROMPT));
        capped.acquire(sessionWith(b, PROMPT));
        // 再碰一次 a —— 于是"最久没用过"换成了 b，而不是先来的 a
        capped.acquire(sessionWith(a, PROMPT));
        capped.acquire(sessionWith(c, PROMPT));

        assertThat(capped.liveReport().count()).isEqualTo(2);

        events.readAfterCalls.clear();
        capped.acquire(sessionWith(a, PROMPT));
        assertThat(events.readAfterCalls)
                .as("a 还在表里 —— 它只该往后补读，不该从第 0 条重建")
                .allMatch(seq -> seq > 0);

        events.readAfterCalls.clear();
        ContextAssembler.Projection rebuilt = capped.acquire(sessionWith(b, PROMPT));
        assertThat(events.readAfterCalls)
                .as("b 被放下过 —— 再拿就得重建")
                .containsExactly(0L);
        assertSameAsFromScratch(b, rebuilt);
    }

    @Test
    @DisplayName("【上限】字节那条也会踢 —— 条数远远没到也一样")
    void evictionAlsoHonoursTheByteBudget() {
        SessionId a = SessionId.generate();
        SessionId b = SessionId.generate();
        events.append(a, new UserMessage("x".repeat(1_000)));
        events.append(b, new UserMessage("x".repeat(1_000)));

        // 每条投影约 2,000 字节（按最坏情况"一个字符两个字节"算），而这里只给 2,500
        SessionProjections capped =
                new SessionProjections(events, NAMELESS, 128, DataSize.ofBytes(2_500));

        capped.acquire(sessionWith(a, PROMPT));
        capped.acquire(sessionWith(b, PROMPT));

        assertThat(capped.liveReport().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("丢弃会话之后它的投影不再常驻；再拿是一次重建，内容仍然一样")
    void forgettingASessionDropsItsProjection() {
        events.append(new UserMessage("说一句"));
        ContextAssembler.Projection before = projections.acquire(sessionWith(PROMPT));

        projections.forget(SESSION);

        assertThat(projections.liveReport().count()).isZero();
        ContextAssembler.Projection after = projections.acquire(sessionWith(PROMPT));
        assertThat(after).isNotSameAs(before);
        assertSameAsFromScratch(after);
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
            append(SESSION, event);
        }

        private void append(SessionId sessionId, PersistentEvent event) {
            all.add(new StoredEvent(sessionId, nextSeq++, Instant.EPOCH, event));
        }

        /** 这条会话的全部事件 —— 测试拿它去对"从头建一条"。 */
        private List<StoredEvent> of(SessionId sessionId) {
            return all.stream().filter(stored -> stored.sessionId().equals(sessionId)).toList();
        }

        @Override
        public List<StoredEvent> readAfter(SessionId sessionId, long afterSeq, int limit) {
            readAfterCalls.add(afterSeq);
            return of(sessionId).stream()
                    .filter(stored -> stored.seq() > afterSeq)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<StoredEvent> readAll(SessionId sessionId) {
            return of(sessionId);
        }

        @Override
        public long lastSeq(SessionId sessionId) {
            List<StoredEvent> mine = of(sessionId);
            return mine.isEmpty() ? 0 : mine.getLast().seq();
        }

        /** 这条测试盯的是**投影**，压缩那道闸归 {@code ContextCompactorTest} 管。 */
        @Override
        public OptionalInt lastContextTokens(SessionId sessionId) {
            throw new UnsupportedOperationException();
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
