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
        // 上下文那一对默认没有读数（null）：这些用例只关心 token 用量，
        // 而用量和“当时上下文多大”是两个量。
        return new TurnTokensUsed(input, output, reasoning, cached, null, null, "deepseek-flash");
    }

    @Test
    @DisplayName("总量 = 输入 + 输出，缓存命中的那部分**已经算在输入里**")
    void totalCountsCachedInputOnlyOnce() {
        assertThat(usage(100, 20, 0).totalTokens()).isEqualTo(120);
        // 缓存的 60 已包含在 100 里，不能再加一遍，否则 total 会虚高
        assertThat(usage(100, 20, 60).totalTokens()).isEqualTo(120);
    }

    @Test
    @DisplayName("思考那部分**也算在输出里** —— 它不是加在旁边的另一笔账")
    void totalCountsReasoningOnlyOnceInsideOutput() {
        // 思考 token 是 output_tokens 的细分；当作并列一项再加，每轮的 total 都会虚高
        assertThat(usage(100, 20, 18, 0).totalTokens()).isEqualTo(120);
    }

    @Test
    @DisplayName("上下文读数**不掺进账里** —— 它是「当时多大」，不是「花了多少」")
    void contextReadingIsNotPartOfTheBill() {
        TurnTokensUsed withContext =
                new TurnTokensUsed(100, 20, 0, 0, 12_000, 64_000, "deepseek-flash");

        // 总量仍是 120。上下文 12,000 是这一轮最后一次调用时上下文的大小；它比本轮
        // 消耗的总量还大是常态（一次请求送进去的就有那么多），不能加减进 total。
        assertThat(withContext.totalTokens()).isEqualTo(120);
        assertThat(withContext.hasContext()).isTrue();
    }

    @Test
    @DisplayName("没有上下文读数时不画 —— 「没有」是 null，不是 0")
    void noContextReadingMeansNoRing() {
        // 一上来就被取消（压根没调过模型）
        assertThat(usage(100, 20, 0, 0).hasContext()).isFalse();
        // 一次都没调成：两个数都是 null，不是 0。“上下文正好是 0”不可能发生，拿 0 当
        // “没有”就分不清两件事（见
        // docs/decisions/architecture/2026-10-04-nullable-only-when-zero-is-a-lie.md）。
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
        // 上下文读数同样不许为负，但 null 是合法的，它表示这一轮没有读数
        assertThatThrownBy(() -> new TurnTokensUsed(0, 0, 0, 0, -1, 64_000, "m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TurnTokensUsed(0, 0, 0, 0, null, null, "m").hasContext()).isFalse();
    }
}
