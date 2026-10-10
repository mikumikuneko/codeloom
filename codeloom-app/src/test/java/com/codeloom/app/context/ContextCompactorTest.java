package com.codeloom.app.context;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.app.turn.SessionWriter;
import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.SessionStateChanged;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.SessionState;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩的**触发依据**：什么时候该压全靠那几个数，而它们一度是**系统性低估中文**的
 * （见 {@link ContextCompactor#estimatedTokens} 的注释）。这类错误不会报错，
 * 只会让压缩来得太晚 —— 所以必须钉住。
 *
 * <p>另外钉住那道"便宜的闸"：读数离水位还远时，这一轮**不该去读整条流**。
 */
class ContextCompactorTest {

    private static final SessionId SESSION = SessionId.generate();
    private static final Session SESSION_ROW = session("deepseek-flash");

    /** 这几个用例都到不了"真的要压"那一步，所以没有一个客户端可给 —— 给一个假的反而看不出这件事。 */
    private static final LlmClientProvider NO_CLIENTS = (ownerId, model) -> Optional.empty();

    private static final LeaseToken TOKEN = new LeaseToken(SESSION,
            WorkspaceId.of(UserId.generate(), ProjectId.generate()), 1, "test");

    private static Session session(String modelId) {
        return Session.create(SESSION, ProjectId.generate(), UserId.generate(),
                new ModelConfig(ProviderId.of("deepseek"), modelId, null));
    }

    @Test
    @DisplayName("【核心】DeepSeek 的压缩水位在 80 万，**不是四万多**")
    void deepseekCompactsAtEightyPercentOfAMillion() {
        // 这一条钉的就是那个错：窗口常量一度是 64,000，而 DeepSeek 实际是 1,000,000，
        // 于是压缩在 9% 的地方就触发了 —— 每压一次白花一次模型调用、作废一次 prompt 缓存。
        // 80 万的出处是 deepseek-harness 的默认算式（窗口 × 0.8），
        // 窗口本身的出处是实测 + 官方文档
        assertThat(ContextCompactor.fullThreshold(session("deepseek-flash")))
                .isEqualTo(800_000);
        // 旧名走的是同一个模型，水位必须一样
        assertThat(ContextCompactor.fullThreshold(session("deepseek-chat")))
                .isEqualTo(800_000);
    }

    @Test
    @DisplayName("认不出的模型退回保守默认，而且**水位仍然是个正数**")
    void unknownModelFallsBackToASaneThreshold() {
        // deepseek-harness 那个"离上限至少留 65,536"的余量是照 1M 定的；照搬到 64k 的窗口上
        // 会算出负数 —— 而那意味着"该不该压"永远为真，每一轮都在压，比原来的毛病更糟。
        // 所以余量按窗口封顶（最多四分之一）
        int threshold = ContextCompactor.fullThreshold(session("some-local-model"));

        // 64k − 4,096 − 16,000（那个 4,096 现在是**认不出的模型的默认输出上限**，
        // 从 ModelCapabilities 来 —— 它从前是会话上的 maxTokens，已经挪走了）
        assertThat(threshold).isEqualTo(43_904);
        assertThat(threshold).isLessThan(64_000);      // 低于窗口
        assertThat(threshold).isGreaterThan(0);        // 而且是正数 —— 这才是那条守卫的意义
    }

    @Test
    @DisplayName("【核心】真实读数比估算大时，**用真实读数** —— 这正是中文会话的样子")
    void measuredReadingWinsOverTheCharacterEstimate() {
        // 两个汉字的历史：按"四字符一 token"估出来是 0（2/4 取整），
        // 而服务商上一次亲口说这份上下文是 5,000。真实的中文会话就是这个形状：
        // 估算低得离谱，真值才是对的
        List<StoredEvent> history = withEvents(
                new UserMessage("你好"),
                usage(5_000, 64_000));

        assertThat(compactorOver(null).estimatedTokens(history, SESSION_ROW)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("估算比真实读数大时用估算 —— 宁可早压，不能晚压")
    void characterEstimateWinsWhenItIsLarger() {
        // 一段纯 ASCII：这时候"四字符一 token"是准的甚至偏高，
        // 而那个真实读数（200）明显偏小。取大的，才是安全的那一边
        List<StoredEvent> history = withEvents(
                new UserMessage("x".repeat(4_000)),
                usage(200, 64_000));

        assertThat(compactorOver(null).estimatedTokens(history, SESSION_ROW)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("一条真实读数都没有时（会话第一轮）退回估算")
    void fallsBackToTheEstimateBeforeAnyReadingExists() {
        List<StoredEvent> history = withEvents(new UserMessage("x".repeat(400)));

        assertThat(compactorOver(null).estimatedTokens(history, SESSION_ROW)).isEqualTo(100);
    }

    @Test
    @DisplayName("跳过没有读数的用量事件，继续往前找 —— 那种轮次是常态，不是异常")
    void skipsUsageEventsWithoutAReading() {
        // 中间那一轮被取消了（或者压根没调过模型），它落下的用量事件里**没有读数**
        // （那一对是 null，不是 0）。只看最后一条的话就什么也拿不到，
        // 白丢一个本来能用的下界
        List<StoredEvent> history = withEvents(
                new UserMessage("你好"),
                usage(12_345, 64_000),
                withoutReading());

        assertThat(ContextCompactor.lastMeasuredContext(history)).isEqualTo(12_345);
        assertThat(compactorOver(null).estimatedTokens(history, SESSION_ROW)).isEqualTo(12_345);
    }

    @Test
    @DisplayName("在加这个字段之前落的旧用量事件也不算数 —— 它们没有读数")
    void ignoresLegacyUsageEvents() {
        // 旧行里那两个键压根不存在，反序列化出来是 null（不是 0）——
        // 「上下文是 0」不可能发生，所以 0 只能当暗号用，而暗号是这里刻意不要的东西
        List<StoredEvent> history = withEvents(
                new UserMessage("你好"),
                withoutReading());

        assertThat(ContextCompactor.lastMeasuredContext(history)).isZero();
    }

    @Test
    @DisplayName("【摘要输入】别人捎来的留言，装给摘要模型时带着**真名**")
    void theSummaryInputCarriesRealNamesOnNotes() {
        // 摘要一旦落下去就顶替整段历史，所以它怎么写"这是谁的请求"会一直留在后续每一次
        // 调用里。压缩器从前用的是一份**没有查名字那一层**的装配器，于是摘要里只会写
        // "协作者" —— 这件事不报错，也没有任何地方会提醒
        List<StoredEvent> history = withEvents(
                new UserMessage("先看 README"),
                new AgentNoteDelivered(SessionId.generate(), UserId.of("u-li"),
                        "顺便把构建脚本也改一下"));

        List<LlmMessage> input = compactorOver(null, NAMED).summaryInput(history, SESSION_ROW);

        assertThat(input).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("小李"))
                .noneMatch(content -> content.contains("协作者"));
    }

    // ------------------------------------------------------------------
    // 那道便宜的闸

    @Test
    @DisplayName("【那道闸判不反】压缩点之前只落状态事件，所以'只看读数'与'取大者'同判")
    void theCheapGateAgreesWithTheFullEstimateAtTheCompactionPoint() {
        // 压缩点之前会发生什么，见 TurnExecutor.runOnce：上一轮收尾落一条带读数的用量事件，
        // 这一轮先落两条状态变更（重试回 IDLE、进 THINKING），才轮到压缩。
        // 状态变更**不进上下文** —— 所以取大者拿到的仍然是那个读数，短路与老路是同一个答案
        List<StoredEvent> history = withEvents(
                new UserMessage("第一句"),
                usage(700_000, 1_000_000),
                new SessionStateChanged(SessionState.IDLE, SessionState.THINKING, null),
                new SessionStateChanged(SessionState.IDLE, SessionState.THINKING, null));

        int measured = ContextCompactor.lastMeasuredContext(history);

        assertThat(measured).isEqualTo(700_000);
        assertThat(compactorOver(null).estimatedTokens(history, SESSION_ROW))
                .as("多出来那两条对估算一个字都没贡献 —— 这正是那道闸敢只看读数的全部依据")
                .isEqualTo(measured);
    }

    @Test
    @DisplayName("【省一次全表读】读数离水位还远时，连历史都不读")
    void aFarAwayReadingSkipsTheWholeRead() {
        RecordingStore store = new RecordingStore(OptionalInt.of(1_000));

        Optional<SessionWriter.Written> written = compactorOver(store).compactIfNeeded(
                SESSION_ROW, ContextCompactor.Trigger.PRESSURE, CancellationToken.none(), TOKEN);

        assertThat(written).isEmpty();
        assertThat(store.readAlls)
                .as("读数已经说了'还早'，而读整条流是这一轮里最贵的一件事")
                .isZero();
    }

    @Test
    @DisplayName("读数越过水位 → 退回老路：读整条流，按估算和读数取大者判")
    void aReadingNearTheWatermarkFallsBackToTheFullHistory() {
        RecordingStore store = new RecordingStore(OptionalInt.of(999_999_999));

        compactorOver(store).compactIfNeeded(SESSION_ROW, ContextCompactor.Trigger.PRESSURE,
                CancellationToken.none(), TOKEN);

        assertThat(store.readAlls).isEqualTo(1);
    }

    @Test
    @DisplayName("没有读数（新会话、或上一轮压根没调成模型）→ 也走老路")
    void noReadingAtAllFallsBackToTheFullHistory() {
        RecordingStore store = new RecordingStore(OptionalInt.empty());

        compactorOver(store).compactIfNeeded(SESSION_ROW, ContextCompactor.Trigger.PRESSURE,
                CancellationToken.none(), TOKEN);

        assertThat(store.readAlls).isEqualTo(1);
    }

    // ------------------------------------------------------------------

    /** 压缩器；**写入器给 null** —— 这几个用例都停在"要不要压"那一步，到不了写入。 */
    /** 不要名字的那份：这些用例里没有留言，名字对它们一个字都不影响。 */
    private static final ContextAssembler NAMELESS = new ContextAssembler(userId -> null);

    /** 查得到名字的那份 —— 生产上装配的就是它（见 {@code ContextAssemblyConfig}）。 */
    private static final ContextAssembler NAMED = new ContextAssembler(
            id -> id.equals(UserId.of("u-li")) ? "小李" : null);

    private static ContextCompactor compactorOver(EventStore store) {
        return compactorOver(store, NAMELESS);
    }

    private static ContextCompactor compactorOver(EventStore store, ContextAssembler assembler) {
        return new ContextCompactor(store, NO_CLIENTS, null, assembler);
    }

    /** 只在内存里、并且**记账**的事件表 —— 这条测试要断言的正是"读了几次"。 */
    private static final class RecordingStore implements EventStore {

        private final OptionalInt reading;
        private int readAlls;

        private RecordingStore(OptionalInt reading) {
            this.reading = reading;
        }

        @Override
        public OptionalInt lastContextTokens(SessionId sessionId) {
            return reading;
        }

        @Override
        public List<StoredEvent> readAll(SessionId sessionId) {
            readAlls++;
            return List.of();
        }

        @Override
        public List<StoredEvent> readAfter(SessionId sessionId, long afterSeq, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long lastSeq(SessionId sessionId) {
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

    /** 一次用量结算：上下文读数是「最后一次模型调用送进去多少」。 */
    private static TurnTokensUsed usage(int contextTokens, int contextWindow) {
        return new TurnTokensUsed(100, 20, 0, 0, contextTokens, contextWindow, "deepseek-flash");
    }

    /** 一次**没有读数**的结算：这一轮一次模型都没调成（被取消、或者服务商没报用量）。 */
    private static TurnTokensUsed withoutReading() {
        return new TurnTokensUsed(100, 20, 0, 0, null, null, "deepseek-flash");
    }

    private static List<StoredEvent> withEvents(PersistentEvent... events) {
        List<StoredEvent> history = new ArrayList<>();
        long seq = 1;
        for (PersistentEvent event : events) {
            history.add(new StoredEvent(SESSION, seq++, Instant.EPOCH, event));
        }
        return history;
    }
}
