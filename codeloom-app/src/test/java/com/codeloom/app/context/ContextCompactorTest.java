package com.codeloom.app.context;

import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩的**触发依据**。
 *
 * <p>只测这一个数：什么时候该压全靠它，而它一度是**系统性低估中文**的
 * （见 {@link ContextCompactor#estimatedTokens} 的注释）。这类错误不会报错，
 * 只会让压缩来得太晚 —— 所以必须钉住。
 */
class ContextCompactorTest {

    private static final SessionId SESSION = SessionId.generate();
    private static final Session SESSION_ROW = session("deepseek-flash");

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

        assertThat(ContextCompactor.estimatedTokens(history, SESSION_ROW)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("估算比真实读数大时用估算 —— 宁可早压，不能晚压")
    void characterEstimateWinsWhenItIsLarger() {
        // 一段纯 ASCII：这时候"四字符一 token"是准的甚至偏高，
        // 而那个真实读数（200）明显偏小。取大的，才是安全的那一边
        List<StoredEvent> history = withEvents(
                new UserMessage("x".repeat(4_000)),
                usage(200, 64_000));

        assertThat(ContextCompactor.estimatedTokens(history, SESSION_ROW)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("一条真实读数都没有时（会话第一轮）退回估算")
    void fallsBackToTheEstimateBeforeAnyReadingExists() {
        List<StoredEvent> history = withEvents(new UserMessage("x".repeat(400)));

        assertThat(ContextCompactor.estimatedTokens(history, SESSION_ROW)).isEqualTo(100);
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
        assertThat(ContextCompactor.estimatedTokens(history, SESSION_ROW)).isEqualTo(12_345);
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

    // ------------------------------------------------------------------

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
