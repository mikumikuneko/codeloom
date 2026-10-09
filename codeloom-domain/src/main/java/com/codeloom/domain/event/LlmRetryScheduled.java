package com.codeloom.domain.event;

/**
 * 一次模型调用失败了，**我们决定等一下再试**。
 *
 * <h2>为什么这个"等一下"要落库</h2>
 * 因为它是**用户看得见的一段静默**：重试藏在 provider 客户端里时，
 * 三次尝试之间那几秒界面上什么都没有 —— 看的人只会觉得"它卡住了"。
 *
 * <p>不落库的话这个问题修不掉：实时推给界面只能解决"此刻在看的人"，
 * 而刷新页面、事后复盘"这一轮为什么花了 40 秒"都得靠事件流里这条记录。
 *
 * <h2>它记的是"打算等多久"，不是"等到了"</h2>
 * 等待期间用户可能按 Esc、可能把页面关了。那时这条事件仍然是真话 ——
 * 我们**确实打算**等，而后来的取消是另一条事实（落成那一轮的收尾）。
 * 记成"我睡了 5 秒"反而会是一句需要事后修正的话。
 *
 * @param attempt     这是第几次尝试（从 1 开始）。1 表示"第一次失败了，正在试第二次"
 * @param maxAttempts 一共最多试几次。有它才画得出"第 2/3 次"
 * @param delayMs     打算等多久（毫秒）。**是等之前算好的那个数**，所以可以拿它倒计时
 * @param reason      为什么失败的 —— 给人和界面看的一句话（"限流"、"服务端故障"……）。
 *                    不是服务商的原文：那个在日志里，而这里要的是能稳定显示的分类
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
