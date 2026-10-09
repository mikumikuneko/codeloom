package com.codeloom.domain.event;

/**
 * 某次工具调用**需要人来批**，这一轮就此停下等答复。
 *
 * <h2>为什么要单独一条事件，而不是让 {@code ToolResult} 带个标志</h2>
 * 这不是"这次调用失败了"，而是"它压根还没执行、卡在等人点头" ——
 * 三者的后续完全不同：失败要让模型换个做法，挂起要等人，而结果是执行完了。
 * 混成一种的话，模型会去"重试"一个正在等人的调用。
 *
 * <p>而且要能和 {@link ToolCallRequested} 分开看：那条说的是"模型请求了它"，
 * 这条说的是"平台判定它得先问过人"。请求本身是正常的事实，投影时照常进上下文
 * （不投影的话，模型就不知道自己在等什么了）；这条则只走审计。
 *
 * <h2>"要批的是什么"不在这里</h2>
 * 工具名和参数已经在 {@link ToolCallRequested} 里了，同一场事件流里再抄一遍
 * 就是两份真相。审批界面要展示命令本身的话，按 callId 回溯即可。
 *
 * <p>但 {@code reason} **不是**那种重复 —— 它是**新的事实**：我们为什么问。
 * 它和"工具名/参数"不是一回事。
 *
 * <h2>为什么要带上理由</h2>
 * 因为**人得看着它点同意**。判据有三种（程序不在免审批名单里 / 动到了工作区外面 /
 * 这行命令看不懂），要人过目的东西完全不同 —— 不说清楚的话，那个"批准"就是走过场。
 *
 * <p>它和 {@link ToolApprovalResolved#reason()}（人为什么拒绝）是同一个决定的两半 ——
 * 那一半记着，"我们为什么问"这一半原先一个字都没记。
 *
 * @param callId 哪一次调用在等人批
 * @param reason 为什么要问 —— 一句话，说给人听。**可能是空串**：这个字段是后加的，
 *               老事件里没有它。那时候只能显示一个不带理由的批准按钮
 */
public record ToolApprovalRequested(String callId, String reason) implements PersistentEvent {

    public ToolApprovalRequested {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("必须说清是哪一次调用在等审批");
        }
        reason = reason == null ? "" : reason;
    }
}
