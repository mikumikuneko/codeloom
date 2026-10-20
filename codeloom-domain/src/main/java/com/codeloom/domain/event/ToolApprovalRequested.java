package com.codeloom.domain.event;

/**
 * 某次工具调用需要人来批，这一轮就此停下等答复。
 *
 * <p>它不能和 {@link ToolCallRequested} 合并：那条说模型请求了调用，这条说平台判定要先问人。
 * 也不能用 {@link ToolResult} 带失败标志表示：调用尚未执行，失败、挂起、完成三种后续不同；
 * 混成一种，模型会重试一个正在等人的调用。请求照常进上下文，这条只走审计。
 *
 * <p>工具名与参数不在这里，按 {@code callId} 回溯即可。{@code reason} 是新事实：我们为什么问。
 * 判据有三种（不在免审批名单、动到工作区外、命令看不懂），要人过目的东西不同；它和
 * {@link ToolApprovalResolved#reason()} 是同一个决定的两半。见
 * {@code docs/decisions/architecture/2026-09-30-approval-is-scoped-to-the-project}。
 *
 * @param callId 哪一次调用在等人批
 * @param reason 为什么要问，一句话；可能是空串（后加字段，老事件没有）
 */
public record ToolApprovalRequested(String callId, String reason) implements PersistentEvent {

    public ToolApprovalRequested {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("必须说清是哪一次调用在等审批");
        }
        reason = reason == null ? "" : reason;
    }
}
