package com.codeloom.agent.llm.openai;

import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.SseParser;
import com.codeloom.agent.llm.StreamEvent;
import com.codeloom.domain.port.CancellationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全部跑在**真实抓包**上，不是我自己编的格式。
 *
 * <p>fixture 是 2026-09-25 用 curl 直连 DeepSeek 抓下来的一次流式工具调用响应，
 * 原样保存下来（已确认不含任何密钥）。所以这些断言验的是"能不能解析对方**真的**
 * 吐出来的字节"，而不是"能不能解析我以为的格式"。
 *
 * <p>好处：不需要网络、不需要 API Key，CI 里也能跑。
 */
class OpenAiStreamAccumulatorTest {

    private static final String FIXTURE = "/fixtures/deepseek-stream-tool-call.sse";

    /** 手写的（不是抓包）：思考模式 + 带 {@code completion_tokens_details} 的用量帧。 */
    private static final String REASONING_FIXTURE = "/fixtures/deepseek-stream-reasoning-usage.sse";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("fixture 本身确实是【分片】的 —— 否则下面的断言就是空转")
    void fixtureIsActuallyFragmented() throws IOException {
        String raw = rawFixture();

        long argumentFrames = raw.lines()
                .filter(line -> line.contains("\"arguments\""))
                .count();

        // 实测是 14 帧（1 帧带 id/name + 13 帧纯碎片）。
        // 这条断言防的是：以后有人换成一份"参数一次性给全"的 fixture，
        // 那下面的拼接测试就变成永远通过、什么也没验的测试了。
        assertThat(argumentFrames)
                .as("fixture 里承载 arguments 的帧数，太少说明它不是分片的")
                .isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("【核心】13 个碎片被正确地拼回一个合法 JSON")
    void reassemblesFragmentedToolArguments() throws IOException {
        List<StreamEvent.ToolCallCompleted> calls = toolCalls(fixtureEvents());

        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().call().name()).isEqualTo("read_file");
        assertThat(calls.getFirst().call().id()).startsWith("call_");

        // 碎片切在 JSON 语法中间（"path" 被切成 " + path + "），
        // 拼错一个字符这里就得不到合法 JSON
        assertThat(calls.getFirst().call().argumentsJson())
                .isEqualTo("{\"path\": \"src/OrderService.java\"}");
    }

    @Test
    @DisplayName("结束事件带的是【服务端实际使用】的模型，不是请求时传的那个")
    void finishedCarriesTheModelTheServerActuallyUsed() throws IOException {
        StreamEvent.Finished finished = finished(fixtureEvents());

        // 抓包时请求传的是 deepseek-chat（一个已下线的旧别名），
        // 服务商回的是 deepseek-flash。审计必须记后者。
        assertThat(finished.model()).isEqualTo("deepseek-flash");
        assertThat(finished.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    @DisplayName("用量统计被完整解析出来 —— token 预算要用这些真实数字")
    void parsesTokenUsage() throws IOException {
        StreamEvent.Finished finished = finished(fixtureEvents());

        assertThat(finished.usage().inputTokens()).isEqualTo(292);
        assertThat(finished.usage().outputTokens()).isEqualTo(52);
        assertThat(finished.usage().totalTokens()).isEqualTo(344);
        assertThat(finished.usage().cachedInputTokens()).isZero();
        // 未命中缓存的那部分 = 输入 − 命中。这次一个都没命中，所以 292 全按未命中价算
        assertThat(finished.usage().inputTokens() - finished.usage().cachedInputTokens())
                .isEqualTo(292);
    }

    @Test
    @DisplayName("思考 token 从 completion_tokens_details 里读出来 —— 它含在输出里，不另算一笔")
    void parsesReasoningTokensOutOfTheOutput() throws IOException {
        StreamEvent.Finished finished = finished(eventsOf(REASONING_FIXTURE));

        // 这一份是**照着 DeepSeek 文档里的 usage 形状**手写的（不是抓包）：
        // completion_tokens_details.reasoning_tokens 那一层，官方文档的响应示例里就有。
        // 上面那份抓包 fixture 是非思考模式的，所以它里面没有这一层 ——
        // 两条路都要有人走一遍
        assertThat(finished.usage().reasoningTokens()).isEqualTo(480);
        // 账单上的数没变：480 是那 530 里的一部分，不是加在旁边的另一项
        assertThat(finished.usage().outputTokens()).isEqualTo(530);
        assertThat(finished.usage().totalTokens()).isEqualTo(1730);
        // 正文那部分 = 输出 − 思考（530 − 480），拆出来正好是模型真让我看见的那些字
        assertThat(finished.usage().outputTokens() - finished.usage().reasoningTokens())
                .isEqualTo(50);
    }

    @Test
    @DisplayName("provider 不报思考 token 时它是 0，不是「读到一个空值」")
    void reasoningTokensAreZeroWhenTheProviderDoesNotReportThem() throws IOException {
        // 嵌套那两层都不在的时候，.path() 一路走下去得到 MissingNode，
        // asInt(0) 给 0 —— 不需要任何额外的存在性判断
        assertThat(finished(fixtureEvents()).usage().reasoningTokens()).isZero();
    }

    @Test
    @DisplayName("正文增量是分多段到达的，按顺序拼起来才是一句话")
    void textDeltasArriveInPieces() throws IOException {
        List<String> pieces = fixtureEvents().stream()
                .filter(e -> e instanceof StreamEvent.TextDelta)
                .map(e -> ((StreamEvent.TextDelta) e).text())
                .toList();

        assertThat(pieces).hasSizeGreaterThan(2);          // 确实在流式，不是一次性给全
        assertThat(String.join("", pieces)).startsWith("I'll");
    }

    @Test
    @DisplayName("正文和工具调用会交错 —— 模型先说话再调工具，两者必须能共存")
    void textAndToolCallCoexist() throws IOException {
        List<StreamEvent> events = fixtureEvents();

        assertThat(events.stream().anyMatch(e -> e instanceof StreamEvent.TextDelta)).isTrue();
        assertThat(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCallCompleted)).isTrue();
    }

    @Test
    @DisplayName("思考过程按增量拼起来 —— 那家的字段名在这一层被消化掉")
    void reassemblesReasoningDeltas() {
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(mapper);

        accumulator.accept("""
                {"choices":[{"delta":{"reasoning_content":"先看题目"}}]}""");
        accumulator.accept("""
                {"choices":[{"delta":{"reasoning_content":"，再算"}}]}""");

        // 拼出来的是中性的 reasoning，不是某一家字段名的直接透传
        assertThat(accumulator.accumulatedReasoning()).isEqualTo("先看题目，再算");
    }

    @Test
    @DisplayName("【实时】思考增量在收到的那一刻就交出去 —— 攒到结束才给就失去意义了")
    void reasoningDeltasAreEmittedLive() {
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(mapper);

        List<StreamEvent> first = accumulator.accept("""
                {"choices":[{"delta":{"reasoning_content":"先看题目"}}]}""");
        List<StreamEvent> second = accumulator.accept("""
                {"choices":[{"delta":{"reasoning_content":"，再算"}}]}""");

        assertThat(first).containsExactly(new StreamEvent.ReasoningDelta("先看题目"));
        assertThat(second).containsExactly(new StreamEvent.ReasoningDelta("，再算"));
        // 归总的那个字符串照样在攒 —— 它随后要落进 AssistantMessage
        assertThat(accumulator.accumulatedReasoning()).isEqualTo("先看题目，再算");
    }

    @Test
    @DisplayName("【生产路径】思考增量经 drain 也转发给下游，且和正文分成两类事件")
    void reasoningDeltasReachTheListenerThroughDrain() throws IOException {
        SseParser parser = new SseParser(new StringReader("""
                data: {"choices":[{"delta":{"reasoning_content":"嗯"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: [DONE]

                """));

        List<StreamEvent> delivered = new ArrayList<>();
        var result = OpenAiStreamAccumulator.drain(parser, mapper, delivered::add,
                CancellationToken.none());

        assertThat(delivered).filteredOn(e -> e instanceof StreamEvent.ReasoningDelta)
                .containsExactly(new StreamEvent.ReasoningDelta("嗯"));
        assertThat(delivered).filteredOn(e -> e instanceof StreamEvent.TextDelta)
                .containsExactly(new StreamEvent.TextDelta("好"));
        assertThat(result.reasoning()).isEqualTo("嗯");
        assertThat(result.text()).isEqualTo("好");
    }

    @Test
    @DisplayName("不支持推理的模型不发那个字段 —— 不能因此报错，正文照常")
    void absentReasoningIsJustEmpty() {
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(mapper);

        accumulator.accept("""
                {"choices":[{"delta":{"content":"你好"}}]}""");

        assertThat(accumulator.accumulatedReasoning()).isEmpty();
        assertThat(accumulator.accumulatedText()).isEqualTo("你好");
    }

    @Test
    @DisplayName("工具调用在 finish() 才产出 —— 提前交出去只会拿到半截 JSON")
    void toolCallsAreWithheldUntilFinished() {
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(mapper);

        accumulator.accept("""
                {"model":"m","choices":[{"delta":{"tool_calls":\
                [{"index":0,"id":"c1","function":{"name":"read_file","arguments":"{\\"pa"}}]}}]}""");
        accumulator.accept("""
                {"model":"m","choices":[{"delta":{"tool_calls":\
                [{"index":0,"function":{"arguments":"th\\": \\"a.txt\\"}"}}]}}]}""");

        // 到这里参数还没收全，不该有任何 ToolCallCompleted
        List<StreamEvent> finished = accumulator.finish();

        assertThat(toolCalls(finished)).hasSize(1);
        assertThat(toolCalls(finished).getFirst().call().argumentsJson())
                .isEqualTo("{\"path\": \"a.txt\"}");
    }

    @Test
    @DisplayName("并行工具调用按 index 分别累积，不会串在一起")
    void parallelToolCallsAreKeptSeparate() {
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(mapper);

        accumulator.accept("""
                {"model":"m","choices":[{"delta":{"tool_calls":[\
                {"index":0,"id":"c0","function":{"name":"read_file","arguments":"{\\"path\\":"}},\
                {"index":1,"id":"c1","function":{"name":"grep","arguments":"{\\"pattern\\":"}}\
                ]}}]}""");
        accumulator.accept("""
                {"model":"m","choices":[{"delta":{"tool_calls":[\
                {"index":0,"function":{"arguments":"\\"a.txt\\"}"}},\
                {"index":1,"function":{"arguments":"\\"TODO\\"}"}}\
                ]}}]}""");

        List<StreamEvent.ToolCallCompleted> calls = toolCalls(accumulator.finish());

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).call().name()).isEqualTo("read_file");
        assertThat(calls.get(0).call().argumentsJson()).isEqualTo("{\"path\":\"a.txt\"}");
        assertThat(calls.get(1).call().name()).isEqualTo("grep");
        assertThat(calls.get(1).call().argumentsJson()).isEqualTo("{\"pattern\":\"TODO\"}");
    }

    @Test
    @DisplayName("[DONE] 被识别为结束哨兵，不会被当成 JSON 去解析")
    void doneSentinelIsNotParsedAsJson() {
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(mapper);

        accumulator.accept("[DONE]");

        assertThat(accumulator.isDone()).isTrue();
    }

    @Test
    @DisplayName("【生产路径】取消在流中途生效，且抛的是 CANCELLED 不是 NETWORK")
    void cancellationStopsMidStream() throws IOException {
        CancellationToken token = new CancellationToken();
        SseParser parser = new SseParser(new StringReader("""
                data: {"model":"m","choices":[{"delta":{"content":"一"}}]}

                data: {"model":"m","choices":[{"delta":{"content":"二"}}]}

                data: [DONE]

                """));

        List<StreamEvent> delivered = new ArrayList<>();

        // drain 是生产路径（客户端 stream() 直接调它）—— 若测试另跑一份循环，
        // [DONE] 和取消检查就会从没被测过。
        assertThatThrownBy(() -> OpenAiStreamAccumulator.drain(parser, mapper, event -> {
            delivered.add(event);
            token.cancel();          // 收到第一帧就相当于用户按了 Esc
        }, token))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> {
                    LlmCallException ex = (LlmCallException) e;
                    assertThat(ex.isCancelled()).isTrue();
                    assertThat(ex.retryable()).isFalse();   // 用户取消不该被重试
                });

        assertThat(delivered).hasSize(1);   // 只处理了第一帧就停下
    }

    // ------------------------------------------------------------------

    private List<StreamEvent> fixtureEvents() throws IOException {
        return eventsOf(FIXTURE);
    }

    /**
     * 把一个 fixture 读成归一化事件。
     *
     * <p>用的是生产那个 {@link OpenAiStreamAccumulator#drain}（只是不取消），
     * 所以这些用例覆盖的就是真实那条路径。[DONE] 的处理、取消检查、收尾全在 drain 里。
     *
     * <p>这段包装唯一的调用者是这里，所以它住在测试里，而不是生产树上一个零调用的入口。
     */
    private List<StreamEvent> eventsOf(String fixture) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(fixture)) {
            assertThat(in).as("fixture 未找到: %s", fixture).isNotNull();
            List<StreamEvent> events = new ArrayList<>();
            try (SseParser parser = new SseParser(in, StandardCharsets.UTF_8)) {
                OpenAiStreamAccumulator.drain(parser, mapper, events::add, CancellationToken.none());
            }
            return events;
        }
    }

    private String rawFixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<StreamEvent.ToolCallCompleted> toolCalls(List<StreamEvent> events) {
        return events.stream()
                .filter(e -> e instanceof StreamEvent.ToolCallCompleted)
                .map(e -> (StreamEvent.ToolCallCompleted) e)
                .toList();
    }

    private static StreamEvent.Finished finished(List<StreamEvent> events) {
        return events.stream()
                .filter(e -> e instanceof StreamEvent.Finished)
                .map(e -> (StreamEvent.Finished) e)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 Finished 事件。事件序列: " + events.stream()
                        .map(Object::toString).collect(Collectors.joining(", "))));
    }
}
