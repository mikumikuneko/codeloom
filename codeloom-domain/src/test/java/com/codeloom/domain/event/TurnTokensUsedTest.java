package com.codeloom.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnTokensUsedTest {

    private static TurnTokensUsed usage(int input, int output, int cached) {
        return usage(input, output, 0, cached);
    }

    private static TurnTokensUsed usage(int input, int output, int reasoning, int cached) {
        // 上下文那一对默认**没有读数**（null）：这些用例盯的是**账**，
        // 而账和"当时上下文多大"是两个量
        return new TurnTokensUsed(input, output, reasoning, cached, null, null, "deepseek-flash");
    }

    @Test
    @DisplayName("总量 = 输入 + 输出，缓存命中的那部分**已经算在输入里**")
    void totalCountsCachedInputOnlyOnce() {
        assertThat(usage(100, 20, 0).totalTokens()).isEqualTo(120);
        // 缓存的 60 是那个 100 里的一部分，不能再加一遍 —— 加错了账单会虚高
        assertThat(usage(100, 20, 60).totalTokens()).isEqualTo(120);
    }

    @Test
    @DisplayName("思考那部分**也算在输出里** —— 它不是加在旁边的另一笔账")
    void totalCountsReasoningOnlyOnceInsideOutput() {
        // 思考 token 是 output_tokens 的细分。把它当成并列一项再加上去的话，
        // 每一轮的账都会虚高，而虚高的正好是最容易被忽略的那部分
        assertThat(usage(100, 20, 18, 0).totalTokens()).isEqualTo(120);
    }

    @Test
    @DisplayName("上下文读数**不掺进账里** —— 它是「当时多大」，不是「花了多少」")
    void contextReadingIsNotPartOfTheBill() {
        TurnTokensUsed withContext =
                new TurnTokensUsed(100, 20, 0, 0, 12_000, 64_000, "deepseek-flash");

        // 账单还是 120。上下文那 12,000 是**这一轮最后一次调用时那份上下文的大小** ——
        // 它比这一轮花掉的总量还大是常态（一次请求送进去的就有那么多），
        // 所以任何一个方向上的加减都说不通
        assertThat(withContext.totalTokens()).isEqualTo(120);
        assertThat(withContext.hasContext()).isTrue();
    }

    @Test
    @DisplayName("没有上下文读数时不画 —— 「没有」是 null，不是 0")
    void noContextReadingMeansNoRing() {
        // 一上来就被取消（压根没调过模型）
        assertThat(usage(100, 20, 0, 0).hasContext()).isFalse();
        // 一次都没调成：两个数都是 null。**不是 0** —— "上下文正好是 0"不可能发生，
        // 拿 0 当"没有"用的话，读的人就得分不清这两件事
        assertThat(new TurnTokensUsed(292, 52, 0, 0, null, null, "deepseek-flash").hasContext())
                .isFalse();
        // 只有一个数也不够 —— 分母为零的上下文用量环没有意义
        assertThat(new TurnTokensUsed(292, 52, 0, 0, 5_000, null, "m").hasContext()).isFalse();
        assertThat(new TurnTokensUsed(292, 52, 0, 0, null, 64_000, "m").hasContext()).isFalse();
    }

    @Test
    @DisplayName("用量为负直接拒绝 —— 那是记账写错了，不该悄悄落库")
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> usage(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usage(0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usage(0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usage(0, 0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        // 上下文读数同样不许为负 —— 但**null 是合法的**，它表示"这一轮没有读数"
        assertThatThrownBy(() -> new TurnTokensUsed(0, 0, 0, 0, -1, 64_000, "m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TurnTokensUsed(0, 0, 0, 0, null, null, "m").hasContext()).isFalse();
    }
}
