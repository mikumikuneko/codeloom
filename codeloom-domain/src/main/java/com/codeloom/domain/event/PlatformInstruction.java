package com.codeloom.domain.event;

/**
 * **平台**注入给模型的一条指令 —— 不是用户说的。
 *
 * <h2>为什么要单独一个事件类型</h2>
 * 它**不能**混进 {@link UserMessage} —— 后者是"**用户**发给 agent 的消息"，
 * 把平台指令写成它，后果是：
 *
 * <ul>
 *   <li>回放与审计时，"请根据这个结果修正问题"显示成**用户说的话**</li>
 *   <li>前端会把它渲染进用户的气泡</li>
 *   <li>任何按 {@code UserMessage} 统计的东西（用户轮次、参与度、回滚时按 turn 截断）
 *       都被平台噪声污染 —— 而回滚正是按 turn 对齐代码与对话的</li>
 *   <li>模型侧长期看到 user 角色，会被诱导把平台控制指令理解成人类意图</li>
 * </ul>
 *
 * <p>平台注入和用户发言是两件事，不该共用一个事件类型。
 *
 * @param text   指令正文。投影进上下文时会带来源标记，让模型知道这不是用户说的
 * @param reason 注入原因（如 {@code verification-failed}），便于审计时区分来源与统计
 */
public record PlatformInstruction(String text, String reason) implements PersistentEvent {
}
