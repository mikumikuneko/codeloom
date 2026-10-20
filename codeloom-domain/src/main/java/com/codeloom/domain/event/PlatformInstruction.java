package com.codeloom.domain.event;

/**
 * 平台注入给模型的一条指令 —— 不是用户说的。
 *
 * <p>不能混进 {@link UserMessage}：回放与审计会把平台指令显示成用户发言，前端会渲染进用户气泡，
 * 按用户消息统计的轮次与回滚截断会被平台噪声污染；模型长期看到 user 角色，也会把平台控制
 * 指令当成人类意图。
 *
 * @param text   指令正文。投影进上下文时带来源标记，让模型知道这不是用户说的
 * @param reason 注入原因（如 {@code verification-failed}），供审计区分来源与统计
 */
public record PlatformInstruction(String text, String reason) implements PersistentEvent {
}
