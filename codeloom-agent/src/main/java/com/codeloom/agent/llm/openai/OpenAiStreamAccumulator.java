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
 * 把 OpenAI 兼容的流式分片拼回完整的响应。
 *
 * <h2>这个类存在的唯一理由：arguments 是逐 token 分片到达的</h2>
 * 这是实测出来的，不是猜的（真实抓包存在
 * {@code src/test/resources/fixtures/deepseek-stream-tool-call.sse}）：
 *
 * <pre>
 *   帧1:  {"index":0,"id":"call_00_...","type":"function","function":{"name":"read_file","arguments":""}}
 *   帧2:  {"index":0,"function":{"arguments":"{"}}
 *   帧3:  {"index":0,"function":{"arguments":"\""}}
 *   帧4:  {"index":0,"function":{"arguments":"path"}}
 *   ...
 *   帧14: {"index":0,"function":{"arguments":"}"}}
 * </pre>
 *
 * 十三条碎片才拼出 {@code {"path": "src/OrderService.java"}}。由此推出三条硬约束：
 * <ol>
 *   <li><b>不能边收边解析。</b>碎片会切在 JSON 语法中间 —— {@code "path"} 被切成
 *       {@code "} + {@code path} + {@code "}，{@code src/} 被切成 {@code src} + {@code /}。
 *       任何"收到一帧就试着解析参数"的写法都必然出错，只能等拼完再解析。</li>
 *   <li><b>必须按 {@code index} 分组。</b>一次响应可以有多个工具调用（并行调用），
 *       每个有自己的 index 和自己的碎片序列。</li>
 *   <li><b>{@code id} / {@code name} / {@code type} 只在第一帧出现。</b>
 *       后续帧里只有 {@code arguments}，别指望每帧都带全。</li>
 * </ol>
 *
 * <p>另外注意：**正文和工具调用会交错**。实测中模型先输出了正文
 * （"I'll read..."）才发起工具调用，所以 {@code delta.content} 和
 * {@code delta.tool_calls} 必须能同时处理，不能假设"要么说话要么调工具"。
 */
public final class OpenAiStreamAccumulator {

    /** OpenAI 的结束哨兵。它是 provider 约定而不是 SSE 规范的一部分，所以留在这里而不是 SseParser 里。 */
    private static final String DONE_SENTINEL = "[DONE]";

    private final ObjectMapper mapper;
    private final StringBuilder text = new StringBuilder();

    /**
     * 思考过程。
     *
     * <p>**这一家的字段名在这一层被消化掉**（见 {@link #appendReasoningDelta}）——
     * 领域的名字固定是中性的 {@code reasoning}，不跟着某一家走。
     */
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, PartialToolCall> toolCalls = new LinkedHashMap<>();

    private String model;
    private String finishReason;
    private TokenUsage usage = TokenUsage.UNKNOWN;
    private boolean done;
    private boolean finished;

    public OpenAiStreamAccumulator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 喂入一个 SSE data 载荷，产出零个或多个归一化事件。
     *
     * <p>正文增量会立即产出（要实时刷给界面看）；工具调用**不产出** ——
     * 它的参数还没拼完，提前交出去只会拿到半截 JSON。
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
     * 流结束后调用：把攒齐的工具调用和结束事件交出去。
     *
     * <p>工具调用到这一步才产出，因为只有这时才能确定碎片收全了。
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

    /** 已经收到过 {@code [DONE]}。 */
    public boolean isDone() {
        return done;
    }

    /**
     * 拼思考过程的增量，并**立刻**把这一段交出去。
     *
     * <p>和正文增量一样即时产出：思考过程是给人实时看的，攒到结束再给就失去了意义。
     *
     * <p>{@code reasoning_content} 是 OpenAI 兼容阵营里推理模型的常见字段名，但它**不是**标准 ——
     * 权威的是各家自己的字段（Anthropic 那边是带签名的 thinking block，形状完全不同）。
     * 所以映射就写在这儿一处：**领域层只认 {@code reasoning} 这个中性名**，
     * 换一家 provider 时改的是这里，不是领域代码。
     *
     * <p>没有这个字段就直接跳过 —— 不支持推理的模型不会发它，那不是错误。
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

    /** 拼好的思考过程；这个模型不产生思考时是空串。 */
    public String accumulatedReasoning() {
        return reasoning.toString();
    }

    /** 到目前为止累积的正文，便于调用方在流中断时也能拿到已产出的部分。 */
    public String accumulatedText() {
        return text.toString();
    }

    // ------------------------------------------------------------------

    /**
     * 把 SSE 流抽干成归一化结果 —— **生产路径与测试路径的唯一实现**。
     *
     * <p>共用一份实现（而不是生产、测试各一个循环）保证 {@code [DONE]} 的处理、
     * 取消检查、收尾逻辑在测试里被真正覆盖 —— 分开写的话这些只会在真实路径上跑、
     * 测不到。
     *
     * <p>同步阻塞读 —— 调用点必须在虚拟线程上（agent 循环本来就在虚拟线程里跑）。
     *
     * @param cancellation 每帧检查一次取消信号。检查点落在**帧边界**上，
     *                     与"中断只发生在工具返回边界"是同一套思路
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
                    // [DONE] 之后不再读 —— 有些实现会在后面挂 keep-alive 注释行
                    break;
                }
            }
        }
        collector.acceptAll(accumulator.finish());
        return collector.toResult(accumulator.accumulatedText(),
                accumulator.accumulatedReasoning());
    }

    /**
     * 边转发事件给下游，边把汇总需要的字段记下来。
     *
     * <p>"读流中"和"finish 之后"两段共用这套分拣逻辑，避免重复实现漏改后一处
     *（症状是"模型名/用量偶尔丢失"，很难查）。
     */
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
                // 正文和思考都不在这里汇总：它们由 accumulator 自己的 StringBuilder 攒着，
                // 分拣一遍只为了向下游转发
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
            // 记服务商【实际使用】的模型，不是我们请求时传的那个 ——
            // 旧别名会被服务商映射到新模型，审计要记真实值
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
        // 思考那部分**已经在 completion_tokens 里**，这里只是把它的细分读出来。
        // 嵌套的那一层是 OpenAI 起的头、DeepSeek 也照着的写法；两层都不在时
        // MissingNode 一路 .path() 下去得到 0，不需要额外的存在性判断
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

    /** 一路工具调用的累积状态。碎片按到达顺序追加，不做任何解析。 */
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
