package com.codeloom.domain.event;

/**
 * 用户中断了正在执行的工具。
 *
 * <p>只有会话所有者能触发。它落成独立事件、而不是 {@code ToolResult(success = false)}，
 * 因为它是**用户意图**：审计和回放要能把"用户让它停"和"它自己失败了"分开。
 *
 * <p>取消只发生在工具返回边界上：先给进程发取消信号、等进程树被杀干净，再回到用户。
 * 否则会留下僵尸进程和半成品文件。
 *
 * <p>它**不带理由**：取消这个动作没有内容要转达 —— 用户要表达"你方向错了"，
 * 那是下一条 {@code UserMessage} 的事，不是这个字段的事。
 *
 * @param callId 哪一次调用
 */
public record ToolCancelled(String callId) implements PersistentEvent {
}
