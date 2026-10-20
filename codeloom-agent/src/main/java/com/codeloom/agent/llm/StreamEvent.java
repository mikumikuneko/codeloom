package com.codeloom.agent.llm;

/**
 * 流式响应解析后产出的归一化事件。agent 循环只认这几种，不接触 provider 的原始帧；
 * 分片拼接等协议细节由适配器承担。
 */
public sealed interface StreamEvent {

    /** 一段正文增量。 */
    record TextDelta(String text) implements StreamEvent {
    }

    /**
     * 一段思考过程增量。与 {@link TextDelta} 分开，因为界面分属两个区域；
     * 不支持推理的模型不发它，这不是错误。字段名差异由适配器消化。
     */
    record ReasoningDelta(String text) implements StreamEvent {
    }

    /** 一个工具调用已经收全。arguments 分片到达，只有拼完才可能得到合法 JSON。 */
    record ToolCallCompleted(ToolCall call) implements StreamEvent {
    }

    /**
     * 本次响应结束。
     *
     * @param model        服务商实际使用的模型；别名可能被映射，审计记这个值
     * @param finishReason 结束原因，如 {@code stop} / {@code tool_calls} / {@code length}
     * @param usage        本次调用的 token 用量
     */
    record Finished(String model, String finishReason, TokenUsage usage) implements StreamEvent {
    }
}
