package com.codeloom.domain.event;

/**
 * 崩溃恢复时补写：某次工具调用执行到一半就没了。
 *
 * <p>它防止重放：恢复时不能把中断的调用重新执行一遍 —— 它可能已经改过文件、跑过构建，重复执行
 * 会造成二次修改。正确做法是把它作为"上次执行到一半"的事实注入上下文，让模型判断下一步。见
 * {@code docs/decisions/architecture/2026-09-28-crash-recovery-writes-facts-not-resumes}。
 *
 * @param callId 被中断的那次调用
 */
public record ToolInterrupted(String callId) implements PersistentEvent {
}
