package com.codeloom.agent.llm.openai;

import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.agent.llm.LlmRequest;
import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.llm.SseParser;
import com.codeloom.agent.llm.StreamEvent;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.agent.llm.ToolDefinition;
import com.codeloom.domain.port.CancellationToken;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link LlmClient} 的 OpenAI 兼容实现，只使用流式 {@code chat/completions}
 * （请求里 {@code stream} 恒为 true）。
 *
 * <p>请求体用 {@code writeValueAsBytes} 显式按 UTF-8 编码，不依赖 {@code file.encoding}；
 * 响应也按 UTF-8 解码。密钥通过 {@link Supplier} 每次现取，不作为字段常驻。
 *
 * <p>它只发送一次请求；是否重试由 agent 循环决定。分片解析在
 * {@link OpenAiStreamAccumulator} 和 {@link SseParser} 里。
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final Supplier<String> apiKeySupplier;
    private final Duration requestTimeout;
    /** 工具 schema 文本 → 解析后的树。schema 是常量，见 {@link #parsedSchema}。 */
    private final Map<String, JsonNode> schemaCache = new ConcurrentHashMap<>();

    /**
     * @param mapper         JSON 解析器
     * @param baseUrl        OpenAI 兼容端点，如 {@code https://api.deepseek.com}；
     *                       版本路径由调用方带上
     * @param apiKeySupplier 每次请求现取密钥
     */
    public OpenAiCompatibleClient(ObjectMapper mapper, String baseUrl, Supplier<String> apiKeySupplier) {
        this(mapper, baseUrl, apiKeySupplier, Duration.ofMinutes(5));
    }

    /**
     * @param mapper         JSON 解析器
     * @param baseUrl        OpenAI 兼容端点
     * @param apiKeySupplier 每次请求现取密钥
     * @param requestTimeout 单次请求超时
     */
    public OpenAiCompatibleClient(ObjectMapper mapper, String baseUrl,
                                  Supplier<String> apiKeySupplier, Duration requestTimeout) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.endpoint = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl")) + "/chat/completions";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ------------------------------------------------------------------

    @Override
    public LlmResult stream(LlmRequest request, Consumer<StreamEvent> listener, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(cancellation, "cancellation");

        HttpResponse<InputStream> response = send(buildRequest(request));

        SseParser parser = new SseParser(response.body(), StandardCharsets.UTF_8);
        try {
            // 测试与生产共用 drain：[DONE] 处理、取消检查和收尾都在同一条路径上。
            return OpenAiStreamAccumulator.drain(parser, mapper, listener, cancellation);
        } catch (IOException e) {
            throw new LlmCallException(LlmCallException.Kind.NETWORK,
                    "读取响应流失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 请求构造
    // ------------------------------------------------------------------

    private HttpRequest buildRequest(LlmRequest request) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + requireApiKey())
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encodeBody(request)))
                .build();
    }

    /** 每次现取密钥；取不到就是 {@code AUTH}，重试无法解决。 */
    private String requireApiKey() {
        String key = apiKeySupplier.get();
        if (key == null || key.isBlank()) {
            throw new LlmCallException(LlmCallException.Kind.AUTH,
                    "没有可用的 API Key，请先在会话配置里填一个", null);
        }
        return key.strip();
    }

    private byte[] encodeBody(LlmRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("stream", true);
        // 要求最后一个 chunk 带上真实 usage，预算不靠估算。
        payload.put("stream_options", Map.of("include_usage", true));
        payload.put("max_tokens", request.maxTokens());
        payload.put("messages", request.messages().stream()
                .map(message -> wireMessage(message, request.model())).toList());
        if (request.hasTools()) {
            payload.put("tools", request.tools().stream().map(this::wireTool).toList());
        }
        try {
            // writeValueAsBytes 恒定 UTF-8；writeValueAsString().getBytes() 会受默认字符集影响。
            return mapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new LlmCallException(LlmCallException.Kind.INVALID_REQUEST,
                    "无法序列化请求体", e);
        }
    }

    private Map<String, Object> wireMessage(LlmMessage message, String targetModel) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("role", switch (message.role()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        });
        node.put("content", message.textOrEmpty());
        if (message.role() == com.codeloom.agent.llm.LlmRole.TOOL) {
            node.put("tool_call_id", message.toolCallId());
        }
        if (message.hasToolCalls()) {
            node.put("tool_calls", message.toolCalls().stream().map(this::wireToolCall).toList());
        }
        // 思考过程原样回灌：思维链模式下带 tool_calls 的 assistant 消息必须携带
        // reasoning_content，否则返回 400。字段名在请求方向只出现在这一处；没有就不发，不发空串。
        // 响应方向的解析见 {@link OpenAiStreamAccumulator}。
        // 是否发送还取决于来源模型，见 reasoningToSend。
        String reasoning = reasoningToSend(message, targetModel);
        if (reasoning != null) {
            node.put("reasoning_content", reasoning);
        }
        return node;
    }

    /**
     * 这条 assistant 消息的思考是否该发给目标模型。
     *
     * <p>“必须回传”只对产生它的模型成立；跨模型的思考不可移植（参考实现 deepseek-harness
     * 同样按来源模型比对）。来源未知（{@code model == null}：老事件、测试）时照发，
     * 因为“该发而没发”会导致 400；来源明确且与目标不同时不发。
     *
     * <p>尚未验证：目标模型也是思维链模型时，收到缺少 {@code reasoning_content} 的外来历史是否会拒绝。
     * 见 {@code docs/decisions/architecture/2026-10-07-reasoning-is-a-neutral-field.md}。
     *
     * @param message     assistant 消息
     * @param targetModel 本次请求的目标模型
     * @return 应发送的思考；不应发送或没有时为 null
     */
    private static String reasoningToSend(LlmMessage message, String targetModel) {
        String reasoning = message.reasoning();
        if (reasoning == null || message.model() == null) {
            return reasoning;
        }
        return message.model().equals(targetModel) ? reasoning : null;
    }

    private Map<String, Object> wireToolCall(ToolCall call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.name());
        // 参数原样回灌：解析再序列化可能改变字段顺序或数字精度。
        function.put("arguments", call.argumentsJson());

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", call.id());
        node.put("type", "function");
        node.put("function", function);
        return node;
    }

    private Map<String, Object> wireTool(ToolDefinition tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", parsedSchema(tool));
        return Map.of("type", "function", "function", function);
    }

    /** 工具 schema 是常量文本，一轮内会重复解析；按 schema 文本缓存解析结果。 */
    private JsonNode parsedSchema(ToolDefinition tool) {
        return schemaCache.computeIfAbsent(tool.parametersJsonSchema(), schema -> {
            try {
                return mapper.readTree(schema);
            } catch (JsonProcessingException e) {
                throw new LlmCallException(LlmCallException.Kind.INVALID_REQUEST,
                        "工具 " + tool.name() + " 的 JSON Schema 不合法", e);
            }
        });
    }

    // ------------------------------------------------------------------
    // 发送与重试
    // ------------------------------------------------------------------

    /**
     * 发送一次请求，把失败转换为带分类的 {@link LlmCallException}。
     *
     * <p>使用阻塞 {@code send}，因为调用方按同步调用使用。这里只发一次：重试需要轮次上下文、取消信号
     * 和事件通道，由 {@code AgentTurn.callModel} 负责。
     *
     * @param request 请求
     * @return 200 响应
     * @throws LlmCallException 网络错误或非 200 响应；是否重试由调用方决定
     */
    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                return response;
            }
            throw toException(response);
        } catch (IOException e) {
            throw new LlmCallException(LlmCallException.Kind.NETWORK,
                    "网络错误: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException(LlmCallException.Kind.NETWORK,
                    "等待响应时被中断", e);
        }
    }

    private LlmCallException toException(HttpResponse<InputStream> response) {
        int status = response.statusCode();
        String body = readBodyQuietly(response.body());

        // 状态码映射到分类，分类决定是否重试；402 与 401 都需要人处理，但处理方式不同。
        String providerMessage = ProviderErrorBody.message(body, mapper);
        if (providerMessage.isEmpty()) {
            providerMessage = "(服务端未返回错误说明)";
        }

        LlmCallException.Kind kind = switch (status) {
            // 400/422 里“请求写错”和“上下文装不下”意思相反，只能在这里区分（见 contextOverflow）。
            case 400, 422 -> contextOverflow(body, providerMessage)
                    ? LlmCallException.Kind.CONTEXT_EXCEEDED
                    : LlmCallException.Kind.INVALID_REQUEST;
            case 401 -> LlmCallException.Kind.AUTH;
            case 402 -> LlmCallException.Kind.INSUFFICIENT_BALANCE;
            // 403（权限/账号被拒）、404（端点或模型写错）、413（请求体太大）重试结果相同。
            case 403 -> LlmCallException.Kind.AUTH;
            case 404, 413 -> LlmCallException.Kind.INVALID_REQUEST;
            // 408 是服务商读请求超时，值得重试。
            case 408 -> LlmCallException.Kind.NETWORK;
            case 429 -> LlmCallException.Kind.RATE_LIMITED;
            default -> status >= 500 ? LlmCallException.Kind.SERVER_ERROR : LlmCallException.Kind.NETWORK;
        };

        return new LlmCallException(kind,
                "模型调用失败 HTTP " + status + "：" + providerMessage, null,
                retryAfterMillis(response));
    }

    /**
     * 这个 400/422 是否表示上下文装不下。
     *
     * <p>适配器是唯一允许解析服务商文本的地方，恢复策略只看分类。先看结构化错误码，再看措辞；
     * 措辞表刻意小：误判为溢出的代价是多压缩一次，漏判的代价是这一轮如实失败。
     *
     * @param body            原始响应体
     * @param providerMessage 解析出的错误说明
     * @return 判断为上下文超限时为 true
     */
    private static boolean contextOverflow(String body, String providerMessage) {
        String haystack = ((body == null ? "" : body) + " " + providerMessage).toLowerCase(Locale.ROOT);
        return haystack.contains("context_length_exceeded")
                || haystack.contains("maximum context length")
                || haystack.contains("context length exceeded")
                || haystack.contains("prompt is too long")
                || haystack.contains("reduce the length of the messages");
    }

    private static String readBodyQuietly(InputStream body) {
        try (InputStream in = body) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 解析服务商的 {@code Retry-After} 头，秒数与 HTTP 日期两种写法都认。
     * 只在适配器里解析，结果跟着异常传给重试策略。
     *
     * @param response HTTP 响应
     * @return 毫秒；认不出来时为空，不猜
     */
    private static Long retryAfterMillis(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(OpenAiCompatibleClient::parseRetryAfter)
                .orElse(null);
    }

    static Long parseRetryAfter(String value) {
        String trimmed = value.strip();
        try {
            return Long.parseLong(trimmed) * 1000L;              // 秒数
        } catch (NumberFormatException ignored) {
            // 不是数字，那它多半是 HTTP 日期，见 RFC 7231
        }
        try {
            Instant when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            // 时间可能已经过去（时钟偏差或消息延迟），不能算出负数。
            return Math.max(0L, Duration.between(Instant.now(), when).toMillis());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
