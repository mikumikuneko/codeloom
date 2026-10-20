package com.codeloom.domain.event;

/**
 * 用户中断了正在执行的工具，只有会话所有者能触发。
 *
 * <p>落成独立事件而不是 {@code ToolResult(success = false)}：这是用户意图，审计和回放要能区分
 * "用户让它停"和"它自己失败"。取消发生在工具返回边界上：先发信号、等进程树被杀干净再回，
 * 否则留下僵尸进程和半成品文件。它不带理由 —— 用户要表达"方向错了"是下一条
 * {@link UserMessage} 的事。见
 * {@code docs/decisions/architecture/2026-10-06-cancel-is-a-signal-timeout-is-a-result}。
 *
 * @param callId 哪一次调用被中断
 */
public record ToolCancelled(String callId) implements PersistentEvent {
}
