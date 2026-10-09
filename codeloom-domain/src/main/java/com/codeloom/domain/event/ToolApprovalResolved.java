package com.codeloom.domain.event;

import com.codeloom.domain.user.UserId;

/**
 * 那次挂起的调用有了答复：批了，或者拒了。
 *
 * <h2>它同时充当那次调用的「结果」</h2>
 * 投影时它会被转成一条 tool 消息（"已批准，可以执行" / "被拒绝，换个别的方式"）。
 * 这样 {@link ToolCallRequested} 的配对关系不用特殊处理 —— 对 API 来说，
 * 它就是那次调用的结果。
 *
 * <h2>为什么记下是谁批的</h2>
 * 在"双方平等协作"的场景里，这是唯一能说清"这个危险操作是谁放行的"的东西。
 * 只改会话状态的话，事后只能查到"当时是待审批状态"，查不到是谁点的头。
 *
 * <p>记 **id** 而不是用户名：查责任要的是身份，而用户名会变 —— 它唯一不代表它是身份
 *（见 {@link SessionRewound} 里同一段理由）。名字由读的那一侧按 id 现查。
 *
 * @param callId           哪一次调用
 * @param approved         批了还是拒了
 * @param resolvedByUserId 谁批的。**记 id**（理由见上）。**不做成可空** ——
 *                         说不出是谁批的审批记录没有意义
 * @param reason           拒绝的理由。批的时候可以为空；拒的时候它是模型唯一的线索，
 *                         告诉它"别换个姿势再来一次"
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
