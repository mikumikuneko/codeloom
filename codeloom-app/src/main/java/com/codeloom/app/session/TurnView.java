package com.codeloom.app.session;

import com.codeloom.agent.loop.TurnOutcome;

/**
 * 一轮跑完后给客户端的结论。
 *
 * <p>只给结论，不给事件流：事件走 SSE 那条通道。同一个响应带事件会让同一条事实有两个来源，
 * 而哪一份是权威没有便宜的答案。
 *
 * @param status       停止原因：{@code COMPLETED}、{@code MAX_ITERATIONS}、{@code TRUNCATED}、
 *                     {@code AWAITING_APPROVAL}、{@code CANCELLED}、{@code BUDGET_EXHAUSTED}
 *                     或 {@code VERIFICATION_FAILED}。它比成功/失败更有用：
 *                     "模型说完成但验证没过"和"模型在打转"处置完全不同
 * @param model        这一轮使用的模型
 * @param finishReason 模型服务商给出的结束原因
 * @param totalTokens  这一轮消耗的总 token 数
 */
public record TurnView(String status, String model, String finishReason, int totalTokens) {

    /**
     * @param outcome 一轮的结果
     * @return 客户端视图
     */
    public static TurnView of(TurnOutcome outcome) {
        return new TurnView(
                outcome.status().name(),
                outcome.model(),
                outcome.finishReason(),
                outcome.usage().totalTokens());
    }
}
