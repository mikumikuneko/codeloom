package com.codeloom.agent.llm.openai;

import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.LlmProtocolException;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.llm.SseParser;
import com.codeloom.agent.llm.StreamEvent;
import com.codeloom.agent.llm.TokenUsage;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.domain.port.CancellationToken;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 把 OpenAI 兼容的流式分片拼回完整响应。
 *
 * <p>实测（真实抓包见 {@code src/test/resources/fixtures/deepseek-stream-tool-call.sse}）：
 * 工具参数逐 token 分片，且会切在 JSON 语法中间，因此不能边收边解析，只能拼完再交给模型；
 * 一次响应可能有多个调用，必须按 {@code index} 分组，{@code id} / {@code name} 只在第一帧出现。
 *
 * <p>正文和工具调用可以交错到达，两者必须能同时处理。
 */
public final class OpenAiStreamAccumulator {

    /** OpenAI 的结束哨兵；属于 provider 约定，不放进 {@link SseParser}。 */
    private static final String DONE_SENTINEL = "[DONE]";

    private final ObjectMapper mapper;
    private final StringBuilder text = new StringBuilder();

    /** 思考过程；provider 字段名在本层消化，对外只用中性的 {@code reasoning}。 */
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, PartialToolCall> toolCalls = new LinkedHashMap<>();

    private String model;
    private String finishReason;
    private TokenUsage usage = TokenUsage.UNKNOWN;
    private boolean done;
    private boolean finished;

    /**
     * @param mapper JSON 解析器
     */
    public OpenAiStreamAccumulator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 喂入一个 SSE data 载荷，产出零个或多个归一化事件。
     *
     * <p>正文和思考增量立即产出；工具调用要等 {@link #finish()}，因为参数尚未拼完。
     *
     * @param dataPayload SSE 帧的 data 载荷
     * @return 本次产生的事件
     */
    public List<StreamEvent> accept(String dataPayload) {
        if (dataPayload == null) {
            return List.of();
        }
        String payload = dataPayload.strip();
        if (payload.isEmpty()) {
            return List.of();
        }
        if (DONE_SENTINEL.equals(payload)) {
            done = true;
            return List.of();
        }

        JsonNode chunk = readJson(payload);
        captureModel(chunk);
        captureUsage(chunk);

        List<StreamEvent> events = new ArrayList<>();
        JsonNode choice = firstChoice(chunk);
        if (choice == null) {
            return events;
        }

        JsonNode delta = choice.get("delta");
        if (delta != null && delta.isObject()) {
            appendTextDelta(delta, events);
            appendReasoningDelta(delta, events);
            accumulateToolCalls(delta);
        }

        JsonNode finish = choice.get("finish_reason");
        if (finish != null && !finish.isNull()) {
            finishReason = finish.asText();
        }
        return events;
    }

    /**
     * 流结束后调用：交出击攒齐的工具调用和结束事件。重复调用返回空。
     *
     * @return 工具调用与 {@link StreamEvent.Finished}
     */
    public List<StreamEvent> finish() {
        if (finished) {
            return List.of();
        }
        finished = true;

        List<StreamEvent> events = new ArrayList<>(toolCalls.size() + 1);
        for (PartialToolCall partial : toolCalls.values()) {
            events.add(new StreamEvent.ToolCallCompleted(partial.toComplete()));
        }
        events.add(new StreamEvent.Finished(model, finishReason, usage));
        return events;
    }

    /**
     * @return 是否已经收到 {@code [DONE]}
     */
    public boolean isDone() {
        return done;
    }

    /**
     * 拼接并立即产出思考增量。{@code reasoning_content} 是这一家的字段名，响应方向的映射只写在这里；
     * 缺少该字段时跳过，不视为错误。
     *
     * @param delta  流式增量
     * @param events 输出事件
     */
    private void appendReasoningDelta(JsonNode delta, List<StreamEvent> events) {
        JsonNode piece = delta.get("reasoning_content");
        if (piece == null || piece.isNull()) {
            return;
        }
        String text = piece.asText();
        if (text.isEmpty()) {
            return;
        }
        reasoning.append(text);
        events.add(new StreamEvent.ReasoningDelta(text));
    }

    /**
     * @return 拼好的思考过程；不产生思考时为空串
     */
    public String accumulatedReasoning() {
        return reasoning.toString();
    }

    /**
     * @return 目前累积的正文；流中断时仍包含已产出的部分
     */
    public String accumulatedText() {
        return text.toString();
    }

    // ------------------------------------------------------------------

    /**
     * 把 SSE 流抽干成归一化结果；生产和测试共用这一实现，确保 [DONE]、取消检查和收尾逻辑被覆盖。
     *
     * <p>同步阻塞读，调用点必须在虚拟线程上。
     *
     * @param parser       帧来源，结束时关闭
     * @param mapper       JSON 解析器
     * @param listener     事件下游
     * @param cancellation 每帧检查一次的取消信号
     * @return 完整结果
     * @throws IOException 读取流失败
     * @throws LlmCallException 用户取消时抛出 {@code CANCELLED}
     */
    public static LlmResult drain(SseParser parser, ObjectMapper mapper,
                                  Consumer<StreamEvent> listener, CancellationToken cancellation)
            throws IOException {
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(mapper);
        Collector collector = new Collector(listener);

        try (parser) {
            Optional<String> payload;
            while ((payload = parser.nextData()).isPresent()) {
                if (cancellation.isCancelled()) {
                    throw new LlmCallException(LlmCallException.Kind.CANCELLED,
                            "模型调用被用户取消", null);
                }
                accumulator.accept(payload.get()).forEach(collector);
                if (accumulator.isDone()) {
                    // [DONE] 之后不再读；有些实现会在后面挂 keep-alive 注释行。
                    break;
                }
            }
        }
        collector.acceptAll(accumulator.finish());
        return collector.toResult(accumulator.accumulatedText(),
                accumulator.accumulatedReasoning());
    }

    /** 向下游转发事件并汇总结果字段；读流中与 finish 之后共用同一套分拣。 */
    private static final class Collector implements Consumer<StreamEvent> {

        private final Consumer<StreamEvent> downstream;
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private String model;
        private String finishReason;
        private TokenUsage usage = TokenUsage.UNKNOWN;

        private Collector(Consumer<StreamEvent> downstream) {
            this.downstream = downstream;
        }

        void acceptAll(List<StreamEvent> events) {
            events.forEach(this);
        }

        @Override
        public void accept(StreamEvent event) {
            switch (event) {
                case StreamEvent.Finished finished -> {
                    model = finished.model();
                    finishReason = finished.finishReason();
                    usage = finished.usage();
                }
                case StreamEvent.ToolCallCompleted completed -> toolCalls.add(completed.call());
                // 正文和思考由 accumulator 的 StringBuilder 汇总，这里只转发。
                case StreamEvent.TextDelta ignored -> {
                }
                case StreamEvent.ReasoningDelta ignored -> {
                }
            }
            downstream.accept(event);
        }

        LlmResult toResult(String text, String reasoning) {
            return new LlmResult(model, finishReason, text, toolCalls, usage,
                    reasoning == null || reasoning.isBlank() ? null : reasoning);
        }
    }

    // ------------------------------------------------------------------

    private JsonNode readJson(String payload) {
        try {
            return mapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new LlmProtocolException(
                    "SSE 数据帧不是合法 JSON（前 200 字符）: " + abbreviate(payload), e);
        }
    }

    private void captureModel(JsonNode chunk) {
        JsonNode node = chunk.get("model");
        if (node != null && !node.isNull()) {
            // 记服务商实际使用的模型；旧别名可能被映射，审计要记真实值。
            model = node.asText();
        }
    }

    private void captureUsage(JsonNode chunk) {
        JsonNode node = chunk.get("usage");
        if (node != null && node.isObject()) {
            usage = parseUsage(node);
        }
    }

    /** 同时认 DeepSeek 的扁平字段与 OpenAI 的嵌套字段，归一成我们自己的 {@link TokenUsage}。 */
    private static TokenUsage parseUsage(JsonNode usage) {
        int input = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(input + output);
        int cached = usage.hasNonNull("prompt_cache_hit_tokens")
                ? usage.get("prompt_cache_hit_tokens").asInt(0)
                : usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        // 思考 token 已含在 completion_tokens 里，这里只读细分；缺失时 path 链得到 0。
        int reasoning = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
        return new TokenUsage(input, output, total, cached, reasoning);
    }

    private static JsonNode firstChoice(JsonNode chunk) {
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode choice = choices.get(0);
        return choice.isObject() ? choice : null;
    }

    private void appendTextDelta(JsonNode delta, List<StreamEvent> events) {
        JsonNode content = delta.get("content");
        if (content == null || content.isNull()) {
            return;
        }
        String piece = content.asText();
        if (!piece.isEmpty()) {
            text.append(piece);
            events.add(new StreamEvent.TextDelta(piece));
        }
    }

    private void accumulateToolCalls(JsonNode delta) {
        JsonNode calls = delta.get("tool_calls");
        if (calls == null || !calls.isArray()) {
            return;
        }
        for (JsonNode call : calls) {
            accumulateOne(call);
        }
    }

    private void accumulateOne(JsonNode call) {
        int index = call.hasNonNull("index") ? call.get("index").asInt() : 0;
        PartialToolCall partial = toolCalls.get(index);

        if (partial == null) {
            String id = call.hasNonNull("id") ? call.get("id").asText() : "call_" + index;
            partial = new PartialToolCall(id);
            toolCalls.put(index, partial);
        }

        JsonNode function = call.get("function");
        if (function == null || !function.isObject()) {
            return;
        }
        if (function.hasNonNull("name") && partial.name.isEmpty()) {
            partial.name = function.get("name").asText();
        }
        if (function.hasNonNull("arguments")) {
            partial.arguments.append(function.get("arguments").asText());
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    /** 一路工具调用的累积状态；碎片按到达顺序追加，不做解析。 */
    private static final class PartialToolCall {

        private final String id;
        private final StringBuilder arguments = new StringBuilder();
        private String name = "";

        private PartialToolCall(String id) {
            this.id = id;
        }

        private ToolCall toComplete() {
            return new ToolCall(id, name, arguments.toString());
        }
    }
}
