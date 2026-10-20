package com.codeloom.domain.event;

import com.codeloom.domain.user.UserId;

/**
 * 那次挂起的调用有了答复：批了，或者拒了。
 *
 * <p>投影时它会转成一条 tool 消息，充当那次调用的结果，于是 {@link ToolCallRequested} 的配对
 * 不必特殊处理；批准后补跑的真实结果会原地替换它，同一个 {@code tool_call_id} 只留一条工具
 * 消息。见 {@code docs/decisions/bug-fix/2026-09-30-one-answer-per-tool-call}。
 *
 * <p>必须记下是谁批的：双方平等协作时，这是唯一能说清"危险操作是谁放行的"记录；只改会话状态
 * 查不到是谁点的头。记 id，用户名由读的一侧现查，见
 * {@code docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider}。
 *
 * @param callId           哪一次调用
 * @param approved         批了还是拒了
 * @param resolvedByUserId 谁批的，记 id；不可空 —— 说不出是谁的审批记录没有意义
 * @param reason           拒绝理由。批准时可为空；拒绝时它是模型唯一的线索
 */
public record ToolApprovalResolved(String callId, boolean approved, UserId resolvedByUserId,
                                   String reason) implements PersistentEvent {

    public ToolApprovalResolved {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("必须说清是哪一次调用的答复");
        }
        if (resolvedByUserId == null) {
            throw new IllegalArgumentException(
                    "必须记下是谁批的 —— 危险操作由谁放行，事后要查得到");
        }
    }
}
