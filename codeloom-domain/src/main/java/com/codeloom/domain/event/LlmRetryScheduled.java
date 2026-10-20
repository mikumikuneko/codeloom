package com.codeloom.domain.event;

/**
 * 一次模型调用失败了，我们决定等一下再试。
 *
 * <p>落库因为它是一段用户看得见的静默：重试若藏在 provider 客户端里，几次尝试之间界面空白，
 * 刷新和事后复盘也查不到。它记的是"打算等多久"，不是"等到了" —— 等待期间用户可能取消，
 * 取消是另一条事实。见
 * {@code docs/decisions/architecture/2026-10-06-cancel-is-a-signal-timeout-is-a-result}。
 *
 * @param attempt     第几次尝试（从 1 开始）；1 表示第一次失败，正在试第二次
 * @param maxAttempts 最多尝试几次，用于显示"第 2/3 次"
 * @param delayMs     计划等待多久（毫秒），在等待前算好，可拿来倒计时
 * @param reason      失败原因的一句话分类（"限流"、"服务商故障"），不是服务商原文；原文在日志里
 * @throws IllegalArgumentException attempt 小于 1、maxAttempts 小于 attempt，或 delayMs 为负
 */
public record LlmRetryScheduled(int attempt,
                                int maxAttempts,
                                long delayMs,
                                String reason) implements PersistentEvent {

    public LlmRetryScheduled {
        if (attempt < 1) {
            throw new IllegalArgumentException("第几次尝试从 1 开始，收到的是 " + attempt);
        }
        if (maxAttempts < attempt) {
            throw new IllegalArgumentException("最多试 " + maxAttempts
                    + " 次，却说这是第 " + attempt + " 次 —— 这条事件本身就不成立");
        }
        if (delayMs < 0) {
            throw new IllegalArgumentException("等待时间不能是负的");
        }
        reason = reason == null ? "" : reason;
    }
}
