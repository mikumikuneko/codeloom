package com.codeloom.domain.event;

/**
 * 用户拒绝了这次调用：它没有执行，也不会再有结果。
 *
 * <p>它和 {@link ToolApprovalResolved} 是两个事实：那条记"谁做了决定、为什么"，这条记"这次调用
 * 到此为止"。批准之后还会真的跑并产出 {@link ToolResult}，拒绝之后什么都不会再来。缺少它，
 * 界面上的调用会一直停在"正在跑"。终局判据见
 * {@link com.codeloom.domain.session.ToolCallLifecycle}；理由在
 * {@link ToolApprovalResolved#reason()}。见
 * {@code docs/decisions/bug-fix/2026-10-05-a-rejected-call-gets-its-own-event}。
 *
 * @param callId 哪一次调用
 */
public record ToolRejected(String callId) implements PersistentEvent {
}
