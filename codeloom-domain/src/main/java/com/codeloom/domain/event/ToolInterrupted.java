package com.codeloom.domain.event;

/**
 * 进程重启后发现某个工具调用执行到一半就没了（崩溃恢复时补写）。
 *
 * <p>这个事件的存在意义是**防止重放**：恢复时不能把中断的调用重新执行一遍 ——
 * 它可能已经改过文件、跑过构建，重复执行会造成二次修改。正确做法是把它作为
 * 「我上次执行到一半」的事实注入上下文，让模型自己判断下一步。
 */
public record ToolInterrupted(String callId) implements PersistentEvent {
}
