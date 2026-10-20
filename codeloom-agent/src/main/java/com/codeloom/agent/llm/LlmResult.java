package com.codeloom.agent.llm;

import java.util.List;
import java.util.Objects;

/**
 * 一次调用的完整结果，是流式回调之外的汇总视图。
 *
 * @param model        服务商实际使用的模型；可能因别名映射与请求值不同，审计记这个值
 * @param finishReason 结束原因：{@code stop} / {@code tool_calls} / {@code length}
 * @param text         完整正文
 * @param toolCalls    工具调用，参数已拼完整
 * @param usage        token 用量
 * @param reasoning    思考过程（推理模型才有）；它是回传协议的一部分，
 *                     具体是否发送由适配器按来源模型决定
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

    /** 没有思考过程的场合（多数模型、测试）。 */
    public LlmResult(String model, String finishReason, String text,
                     List<ToolCall> toolCalls, TokenUsage usage) {
        this(model, finishReason, text, toolCalls, usage, null);
    }

    /**
     * @return 本次是否包含工具调用
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 这一轮是否有思考过程。它取决于模型能力，调用方不能假设一定存在。
     *
     * @return 有非空思考时为 true
     */
    public boolean hasReasoning() {
        return reasoning != null && !reasoning.isBlank();
    }

    /**
     * 输出是否因长度上限被截断。调用方必须显式处理，不能当成正常结束。
     *
     * @return {@code finishReason} 为 {@code length} 时为 true
     */
    public boolean isTruncated() {
        return "length".equals(finishReason);
    }
}
