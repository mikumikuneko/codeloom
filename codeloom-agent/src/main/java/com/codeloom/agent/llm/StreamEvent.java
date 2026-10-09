package com.codeloom.agent.llm;

/**
 * 流式响应解析后产出的**归一化事件**。agent 循环只认这几种，不认任何 provider 的原始帧。
 *
 * <p>拆成这几种而不是直接把原始 JSON 抛给上层，是为了让"解析"和"业务"分开：
 * tool_calls 的分片拼接这类脏活全部由 adapter 承担。
 */
public sealed interface StreamEvent {

    /** 一段正文增量。 */
    record TextDelta(String text) implements StreamEvent {
    }

    /**
     * 一段**思考过程**的增量。
     *
     * <p>和 {@link TextDelta} 分开而不是混在一起：正文是要展示给用户的回复，
     * 思考是"它是怎么想到这个回复的"，界面上分属两个区域。
     *
     * <p>不支持推理的模型不发这个 —— 那不是错误，就是没有。字段名各家不同，
     * 由 provider 适配层消化，这一层只认 {@code reasoning} 这个中性概念。
     */
    record ReasoningDelta(String text) implements StreamEvent {
    }

    /**
     * 一个工具调用**已经收全**了。
     *
     * <p>注意它是"收全"而不是"开始"：arguments 是逐 token 分片到达的，
     * 必须等这一路调用结束后才可能拼出合法 JSON。
     */
    record ToolCallCompleted(ToolCall call) implements StreamEvent {
    }

    /**
     * 本次响应结束。
     *
     * @param model        服务商**实际使用**的模型，不一定等于请求时传的那个 ——
     *                     旧别名会被服务商映射到新模型。审计要记这个字段，
     *                     否则证据链里写的是个已下线的名字
     * @param finishReason 结束原因，如 {@code stop} / {@code tool_calls} / {@code length}
     */
    record Finished(String model, String finishReason, TokenUsage usage) implements StreamEvent {
    }
}
