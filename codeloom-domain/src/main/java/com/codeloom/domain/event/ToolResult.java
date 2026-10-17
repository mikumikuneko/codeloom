package com.codeloom.domain.event;

/**
 * 工具执行完毕（无论成败）。
 *
 * @param callId     与 {@link ToolCallRequested} 配对
 * @param success    是否成功（等价于 exitCode == 0，但模型没给退出码时也要有结论）
 * @param output     标准输出与错误的合并结果，超过上限会被截断
 * @param truncated  是否发生了截断。截断了却不说，模型会以为输出就这么多
 * @param exitCode   退出码；纯内存工具（如读文件）为 null
 * @param durationMs 耗时；展示与审计用（仓里没有任何压测在读它）
 * @param mutated    这一次调用**实际**改动了工作区吗
 *
 *                   <h2>它为什么必须落库</h2>
 *                   这个事实在 agent 那一层**早就算出来了**（它驱动"改过才跑验证"那条规则），
 *                   不记它的话，界面只能自己反推 —— 而反推错在两处：<b>一、它把"这一类工具
 *                   通常会改"当成了"这一次真的改了"</b>；<b>二、新增工具的那天那份名单不会
 *                   跟着变</b>，而症状是"树不刷新了"，没人会想到是这里。
 *
 *                   <p>算出来了却扔掉、再让下游猜回来，和"超时被吞成内部错误"是同一个毛病：
 *                   **事实只该有一个来源**。
 * @param presentation **只给界面看**的结构化结果（JSON 文本），可以为 null。
 *                     给模型看的 {@code output} 是有损的自由文本，界面要画卡片就得有
 *                     一份能还原结构的副本。**它不是领域事实，模型也看不到它** ——
 *                     读它的只有展示层；缺失时展示层退回渲染 {@code output}
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
     * 测试与便捷用的重载：没改工作区、也没有界面结构化结果。
     *
     * <p>**产品路径走全参构造器**（{@code AgentTurn} 总是把 {@code presentation} 一起带上）——
     * 这里不再声称"大多数工具都是这个形状"：工具的真实产出由那次调用自己给。
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
