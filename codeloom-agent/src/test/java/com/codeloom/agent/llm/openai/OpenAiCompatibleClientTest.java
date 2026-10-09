package com.codeloom.agent.llm.openai;

import com.codeloom.agent.llm.ChatMessage;
import com.codeloom.agent.llm.ChatRequest;
import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.agent.llm.ToolDefinition;
import com.codeloom.domain.port.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用 JDK 自带的 {@code com.sun.net.httpserver} 起一个本地服务端来回放响应。
 *
 * <p>这样整条客户端链路（拼请求 → 发 HTTP → 读流 → 解析 → 归一化结果）**全部离线可测**，
 * 不需要网络也不需要 API Key。fixture 那份真实抓包直接当作服务商的响应体喂进去。
 */
class OpenAiCompatibleClientTest {

    private static final String FIXTURE = "/fixtures/deepseek-stream-tool-call.sse";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("端到端：把真实抓包的流喂给客户端，得到完整结果")
    void parsesARealStreamEndToEnd() throws IOException {
        String baseUrl = startServer(respondWith(200, "text/event-stream", fixtureBytes()));
        OpenAiCompatibleClient client = clientFor(baseUrl);

        LlmResult result = client.stream(request("帮我读一下文件"), event -> {
        }, CancellationToken.none());

        assertThat(result.model()).isEqualTo("deepseek-flash");
        assertThat(result.finishReason()).isEqualTo("tool_calls");
        assertThat(result.text()).startsWith("I'll");
        assertThat(result.hasToolCalls()).isTrue();
        assertThat(result.toolCalls().getFirst().name()).isEqualTo("read_file");
        assertThat(result.toolCalls().getFirst().argumentsJson())
                .isEqualTo("{\"path\": \"src/OrderService.java\"}");
        assertThat(result.usage().totalTokens()).isEqualTo(344);
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("【回归】请求体按 UTF-8 编码 —— 中文提示词不能变成非法字节")
    void encodesRequestBodyAsUtf8() throws IOException {
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            send(exchange, 200, "text/event-stream", minimalStream().getBytes(StandardCharsets.UTF_8));
        });
        OpenAiCompatibleClient client = clientFor(baseUrl);

        client.stream(request("请把 OrderService 的 create 改成幂等的"), event -> {
        }, CancellationToken.none());

        // 这条就是那次 400 "invalid unicode code point" 的回归测试：
        // 字节必须能被 UTF-8 解码，且解出来的中文和原文一字不差
        byte[] body = receivedBody.get();
        assertThat(new String(body, StandardCharsets.UTF_8))
                .contains("请把 OrderService 的 create 改成幂等的");

        JsonNode parsed = mapper.readTree(body);
        assertThat(parsed.path("messages").get(0).path("content").asText())
                .isEqualTo("请把 OrderService 的 create 改成幂等的");
    }

    @Test
    @DisplayName("请求里带上 stream 与 include_usage —— token 预算要真实数字不靠估算")
    void asksForUsageInStream() throws IOException {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            received.set(readJson(exchange));
            send(exchange, 200, "text/event-stream", minimalStream().getBytes(StandardCharsets.UTF_8));
        });

        clientFor(baseUrl).stream(request("hi"), event -> {
        }, CancellationToken.none());

        assertThat(received.get().path("stream").asBoolean()).isTrue();
        assertThat(received.get().path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(received.get().path("model").asText()).isEqualTo("deepseek-flash");
    }

    @Test
    @DisplayName("工具定义被渲染成 OpenAI 的 function 结构")
    void rendersToolDefinitions() throws IOException {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            received.set(readJson(exchange));
            send(exchange, 200, "text/event-stream", minimalStream().getBytes(StandardCharsets.UTF_8));
        });
        ChatRequest request = new ChatRequest("deepseek-flash",
                List.of(ChatMessage.user("读文件")),
                List.of(new ToolDefinition("read_file", "读取文件",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")),
                1024);

        clientFor(baseUrl).stream(request, event -> {
        }, CancellationToken.none());

        JsonNode tool = received.get().path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("function").path("name").asText()).isEqualTo("read_file");
        // JSON Schema 必须被当成对象嵌进去，不能变成一段字符串
        assertThat(tool.path("function").path("parameters").isObject()).isTrue();
    }

    @Test
    @DisplayName("带工具调用的那条 assistant 消息，reasoning_content 必须发出去")
    void rendersReasoningContentOnAssistantMessages() throws IOException {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            received.set(readJson(exchange));
            send(exchange, 200, "text/event-stream", minimalStream().getBytes(StandardCharsets.UTF_8));
        });
        // 来源模型传 null = **不知道这条是谁产的**（老事件就是这样）。
        // 不知道来源时照发 —— 见 reasoningToSend："该发而没发"曾导致 400
        ChatRequest request = new ChatRequest("deepseek-reasoner",
                List.of(ChatMessage.user("读一下 A"),
                        ChatMessage.assistantWithToolCalls("", List.of(new ToolCall("call_1",
                                "read_file", "{\"path\":\"A.java\"}")), null, "我在想先看哪个文件")),
                List.of(), 1024);

        clientFor(baseUrl).stream(request, event -> {
        }, CancellationToken.none());

        JsonNode assistant = received.get().path("messages").get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        // ★ 字段名就是 DeepSeek 要的那个。少了它，思维链模式会回
        //   400「The `reasoning_content` in the thinking mode must be passed back to the API.」
        assertThat(assistant.path("reasoning_content").asText()).isEqualTo("我在想先看哪个文件");
        assertThat(assistant.path("tool_calls").isArray()).isTrue();
    }

    @Test
    @DisplayName("【换模型】上一个模型产的思考**不发**给新模型 —— 那不是它的东西")
    void doesNotSendForeignReasoningAfterAModelSwitch() throws IOException {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            received.set(readJson(exchange));
            send(exchange, 200, "text/event-stream", minimalStream().getBytes(StandardCharsets.UTF_8));
        });
        // 这条 assistant 消息是 flash 说的，而这次请求发给 pro
        ChatRequest request = new ChatRequest("deepseek-pro",
                List.of(ChatMessage.user("接着改"),
                        ChatMessage.assistant("前半段我在想接口该怎么切", "deepseek-flash",
                                "前半段我在想接口该怎么切")),
                List.of(), 1024);

        clientFor(baseUrl).stream(request, event -> {
        }, CancellationToken.none());

        JsonNode assistant = received.get().path("messages").get(1);
        // 正文照发 —— 只有那份**只对 flash 成立**的思考被拦下来了
        assertThat(assistant.path("content").asText()).contains("前半段我在想接口该怎么切");
        assertThat(assistant.has("reasoning_content"))
                .as("别家模型产的思考不该发给新模型")
                .isFalse();
    }

    @Test
    @DisplayName("【换模型】同一个模型自己的思考照发 —— 那条协议要求不能被误伤")
    void stillSendsOwnReasoningWhenTheModelMatches() throws IOException {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            received.set(readJson(exchange));
            send(exchange, 200, "text/event-stream", minimalStream().getBytes(StandardCharsets.UTF_8));
        });
        ChatRequest request = new ChatRequest("deepseek-flash",
                List.of(ChatMessage.user("接着改"),
                        ChatMessage.assistantWithToolCalls("", List.of(new ToolCall("call_1",
                                        "read_file", "{\"path\":\"A.java\"}")),
                                "deepseek-flash", "我在想先看哪个文件")),
                List.of(), 1024);

        clientFor(baseUrl).stream(request, event -> {
        }, CancellationToken.none());

        JsonNode assistant = received.get().path("messages").get(1);
        assertThat(assistant.path("reasoning_content").asText()).isEqualTo("我在想先看哪个文件");
    }

    @Test
    @DisplayName("没想过的时候**不发**那个字段 —— 既不产空白属性，也不发空串")
    void omitsReasoningContentWhenThereIsNone() throws IOException {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            received.set(readJson(exchange));
            send(exchange, 200, "text/event-stream", minimalStream().getBytes(StandardCharsets.UTF_8));
        });

        clientFor(baseUrl).stream(request("hi"), event -> {
        }, CancellationToken.none());

        // 不产思考的 provider 走的永远是这一条 —— 所以"要不要回传"这件事
        // 不需要一张按 provider 维护的表，它是自适应的
        JsonNode user = received.get().path("messages").get(0);
        assertThat(user.has("reasoning_content")).isFalse();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("401 认证失败：不可重试，不浪费重试预算")
    void authFailureIsNotRetried() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        String baseUrl = startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 401, "application/json",
                    "{\"error\":{\"message\":\"Authentication Fails\"}}".getBytes(StandardCharsets.UTF_8));
        });

        assertThatThrownBy(() -> clientFor(baseUrl).stream(request("hi"), e -> {
        }, CancellationToken.none()))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> {
                    LlmCallException ex = (LlmCallException) e;
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.isCancelled()).isFalse();
                    // 服务商的原文和状态码都要带出来，否则用户不知道到底哪不对 ——
                    // 两句都在消息里，所以这里断言消息
                    assertThat(ex).hasMessageContaining("401")
                            .hasMessageContaining("Authentication Fails");
                });

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("400 参数错误：不可重试 —— 同样的请求重发多少次都是同样的 400")
    void invalidRequestIsNotRetried() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        String baseUrl = startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 400, "application/json",
                    "{\"error\":{\"message\":\"invalid unicode code point\"}}".getBytes(StandardCharsets.UTF_8));
        });

        assertThatThrownBy(() -> clientFor(baseUrl).stream(request("hi"), e -> {
        }, CancellationToken.none()))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> {
                    LlmCallException ex = (LlmCallException) e;
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex).hasMessageContaining("400");
                });

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("429 限流：**只发一次**，把失败原样报上来 —— 重试不归这一层")
    void rateLimitIsReportedNotRetried() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        String baseUrl = startServer(exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "3");
            send(exchange, 429, "application/json",
                    "{\"error\":{\"message\":\"Rate limit reached\"}}".getBytes(StandardCharsets.UTF_8));
        });

        assertThatThrownBy(() -> clientFor(baseUrl).stream(request("hi"), e -> {
        }, CancellationToken.none()))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> {
                    assertThat(((LlmCallException) e).retryable()).isTrue();
                    // 服务商说了等 3 秒，那就**带上来** —— 重试策略据此决定等多久，
                    // 而它不许去解析服务商原文（见 LlmCallException.retryAfterMs）
                    assertThat(((LlmCallException) e).retryAfterMs()).contains(3_000L);
                });

        // **一次** —— 重试不归这一层。若重试住在客户端里，界面上什么都看不见，
        // 而"值不值得再来一次"要看的上下文它一样都不知道
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("服务端 5xx：同样只发一次，照样原样报上来")
    void serverErrorIsReportedNotRetried() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        String baseUrl = startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 503, "application/json",
                    "{\"error\":{\"message\":\"server busy\"}}".getBytes(StandardCharsets.UTF_8));
        });

        assertThatThrownBy(() -> clientFor(baseUrl).stream(request("hi"), e -> {
        }, CancellationToken.none()))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> assertThat(((LlmCallException) e).retryable()).isTrue());

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("没有密钥就直说，不要发出一个注定 401 的请求")
    void missingApiKeyFailsFast() throws IOException {
        String baseUrl = startServer(exchange -> send(exchange, 500, "application/json", "{}".getBytes()));
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(mapper, baseUrl, () -> "");

        assertThatThrownBy(() -> client.stream(request("hi"), e -> {
        }, CancellationToken.none()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("API Key");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ChatRequest request(String userText) {
        return new ChatRequest("deepseek-flash", List.of(ChatMessage.user(userText)),
                List.of(), 1024);
    }

    private OpenAiCompatibleClient clientFor(String baseUrl) {
        return new OpenAiCompatibleClient(mapper, baseUrl, () -> "sk-test-not-a-real-key");
    }

    private String startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpHandler respondWith(int status, String contentType, byte[] body) {
        return exchange -> send(exchange, status, contentType, body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private JsonNode readJson(HttpExchange exchange) {
        try {
            return mapper.readTree(exchange.getRequestBody().readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("读取请求体失败", e);
        }
    }

    private byte[] fixtureBytes() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture 未找到: %s", FIXTURE).isNotNull();
            return in.readAllBytes();
        }
    }

    /** 一个最小的正常流，用于那些不关心内容的测试。 */
    private static String minimalStream() {
        return """
                data: {"model":"deepseek-flash","choices":[{"delta":{"content":"你"}}]}

                data: {"model":"deepseek-flash","choices":[{"delta":{"content":"好"}}]}

                data: {"model":"deepseek-flash","choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}

                data: [DONE]

                """;
    }
}
