package com.codeloom.domain.event;

/**
 * 用户拒绝了这次调用：它**没有执行**，而且不会再有结果了。
 *
 * <h2>它和 {@link ToolApprovalResolved} 是两个事实，别合并</h2>
 * 那条记的是"**谁做了这个决定、为什么**"—— 一份审批记录，事后要查得到是谁点的头。
 * 这条记的是"**这次调用到此为止**"。
 *
 * <p>分开是必要的，因为两条命不一样：批准之后那次调用还会真的跑、还会产出
 * {@link ToolResult}；而拒绝之后**什么都不会再来了**。
 *
 * <h2>为什么必须有一条这样的记录</h2>
 * 所有消费端判"这次调用结束了没有"用的都是同一句话：**有没有一条收尾的记录**。
 * 缺了它，界面上那条调用会永远停在"正在跑"的样子 —— 实际用出来的症状就是
 * "拒绝了之后那行一直闪"，因为它的判据正是"没有结果、也没有'没跑成'"。
 *
 * <p>**不带理由**：理由是那个"决定"的一部分，已经在
 * {@link ToolApprovalResolved#reason()} 里了。这条只管一件事 —— 它结束了。
 *
 * @param callId 哪一次调用
 */
public record ToolRejected(String callId) implements PersistentEvent {
}
