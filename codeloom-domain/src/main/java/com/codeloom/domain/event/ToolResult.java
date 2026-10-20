package com.codeloom.domain.event;

/**
 * 工具执行完毕（无论成败）。
 *
 * @param callId       与 {@link ToolCallRequested} 配对
 * @param success      是否成功；等价于 exitCode == 0，但模型没给退出码时也要有结论
 * @param output       标准输出与错误的合并结果，超过上限会被截断
 * @param truncated    是否发生截断。截断了却不说，模型会以为输出就这么多
 * @param exitCode     退出码；纯内存工具（如读文件）为 null
 * @param durationMs   耗时，展示与审计用
 * @param mutated      这一次调用实际改动了工作区吗。该事实在 agent 层已经算出并驱动"改过才
 *                     跑验证"；不记它，界面只能按工具类别反推，新增工具时会表现为"树不刷新"。
 *                     事实只该有一个来源
 * @param presentation 只给界面看的结构化 JSON，可为 null。给模型看的 {@code output} 是有损
 *                     自由文本，界面要画卡片就得有可还原结构的副本；它不是领域事实，模型看
 *                     不到，缺失时展示层退回渲染 {@code output}。见
 *                     {@code docs/decisions/feature/2026-10-17-tool-presentation-for-ui}
 */
public record ToolResult(String callId,
                         boolean success,
                         String output,
                         boolean truncated,
                         Integer exitCode,
                         long durationMs,
                         boolean mutated,
                         String presentation) implements PersistentEvent {

    /**
     * 测试与便捷用的重载：没改工作区、也没有界面结构化结果。产品路径走全参构造器
     * （{@code AgentTurn} 总是带上 {@code presentation}）。
     */
    public ToolResult(String callId,
                      boolean success,
                      String output,
                      boolean truncated,
                      Integer exitCode,
                      long durationMs) {
        this(callId, success, output, truncated, exitCode, durationMs, false, null);
    }
}
