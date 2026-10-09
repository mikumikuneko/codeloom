package com.codeloom.agent.llm;

import java.util.List;
import java.util.Objects;

/**
 * 一次调用的**完整**结果。流式回调之外的汇总视图。
 *
 * <p>两种用法都支持：想要实时刷界面就挂 listener 看 {@link StreamEvent.TextDelta}；
 * 只想要最终结果就读这个。
 *
 * @param model        服务商**实际使用**的模型。不一定等于请求时传的那个 ——
 *                     旧别名会被服务商映射到新模型记录，审计要记这个值
 * @param finishReason 结束原因：{@code stop} / {@code tool_calls} / {@code length}
 * @param text         拼好的完整正文
 * @param toolCalls    拼好的工具调用（参数已完整）
 * @param usage        token 用量
 * @param reasoning    这一轮的**思考过程**（推理模型才有），中性名，不用任何一家的字段名。
 *                     <strong>只用于展示</strong> —— 具体回不回传给模型由 provider 适配层决定
 */
public record LlmResult(String model,
                        String finishReason,
                        String text,
                        List<ToolCall> toolCalls,
                        TokenUsage usage,
                        String reasoning) {

    public LlmResult {
        Objects.requireNonNull(text, "text");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
    }

    /**
     * 没有思考过程的场合（多数模型、测试）。
     *
     * <p>它存在的另一个理由：{@code reasoning} 是后加的，有它就不用去改每一个已有的构造点。
     */
    public LlmResult(String model, String finishReason, String text,
                     List<ToolCall> toolCalls, TokenUsage usage) {
        this(model, finishReason, text, toolCalls, usage, null);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 这一轮有没有思考过程。
     *
     * <p>注意"有没有"是**模型能力**决定的，不是我们决定的 —— 换成不支持推理的模型，
     * 它自然就没有。所以调用方不该假设它一定有。
     */
    public boolean hasReasoning() {
        return reasoning != null && !reasoning.isBlank();
    }

    /**
     * 输出被截断了。
     *
     * <p>这个状态要**显式处理**：模型话说到一半被砍，如果当成正常结束，
     * 后续推理就建立在半截内容上。正确做法是把它当一轮失败，或者按预算策略优雅收尾。
     */
    public boolean isTruncated() {
        return "length".equals(finishReason);
    }
}
