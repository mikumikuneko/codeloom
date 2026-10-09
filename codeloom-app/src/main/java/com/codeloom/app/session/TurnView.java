package com.codeloom.app.session;

import com.codeloom.agent.loop.TurnOutcome;

/**
 * 一轮跑完之后给客户端的结论。
 *
 * <p>只给结论、不给事件流 —— 事件是**另一条通道**（SSE）。让这个响应把事件也带上，
 * 等于同一条事实有两个来源，而"哪一份是权威"这种问题一旦出现就没有便宜的答案。
 *
 * @param status       为什么停下来（{@code COMPLETED} / {@code MAX_ITERATIONS} /
 *                     {@code TRUNCATED} / {@code CANCELLED} / {@code BUDGET_EXHAUSTED} /
 *                     {@code VERIFICATION_FAILED}）。它比"成功/失败"有用得多：
 *                     "模型说完成了但验证没过"和"模型在打转"是两种完全不同的处置
 */
public record TurnView(String status, String model, String finishReason, int totalTokens) {

    public static TurnView of(TurnOutcome outcome) {
        return new TurnView(
                outcome.status().name(),
                outcome.model(),
                outcome.finishReason(),
                outcome.usage().totalTokens());
    }
}
