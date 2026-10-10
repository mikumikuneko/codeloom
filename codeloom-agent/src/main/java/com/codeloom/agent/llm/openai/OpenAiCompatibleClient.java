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
 * {@link LlmClient} 的实现：走 OpenAI 的 {@code chat/completions}，**只说流式**
 * （请求里 {@code stream} 恒为 true）—— 打字机效果和"实时观战"都要它；
 * 一次思考几十秒的调用如果不流式，用户看到的是一段什么都没有的空白。
 *
 * <p>自建而不是用官方 SDK，换来的是对协议的完全控制。
 * 增量怎么解析在 {@code OpenAiStreamAccumulator} 和 {@code SseParser} 里，不在这里。
 *
 * <h2>三个必须做对的地方</h2>
 *
 * <p><b>① 请求体必须显式 UTF-8 编码。</b>这里用 {@code writeValueAsBytes}
 * （Jackson 对字节输出恒定 UTF-8），<b>不用 {@code writeValueAsString(...).getBytes()}</b>。
 *
 * <p>后者跟的是 {@code Charset.defaultCharset()}，而它从 JDK 18 起默认就是 UTF-8（JEP 400），
 * 所以今天多半也对 —— 但它**能被 {@code -Dfile.encoding} 改掉**，那正是"编码不靠环境默认值"
 * 这条规矩存在的理由（对照 {@code ProcessRunner#decode}）。编码一旦跑偏，中文提示词会让
 * 服务商回 {@code 400 invalid unicode code point}，而且换个终端、换台机器就重现，
 * 所以宁可写死。
 *
 * <p><b>② 响应也按 UTF-8 解码。</b>中文正文同理 —— 这件事发生在 {@link #stream}
 * 交给 {@code SseParser} 的那个字符集上，不在这个类的别处（所以别在这一层找它）。
 *
 * <p><b>③ 密钥通过 {@link Supplier} 每次现取</b>，不作为字段常驻内存。它不属于任何
 * 领域对象（见 {@code ModelConfig}），只在拼请求头的那一瞬间存在。
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final int MAX_ERROR_BODY_CHARS = 4_000;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final Supplier<String> apiKeySupplier;
    private final Duration requestTimeout;
    /** 工具 schema 文本 → 解析后的树。schema 是常量，见 {@link #parsedSchema}。 */
    private final Map<String, JsonNode> schemaCache = new ConcurrentHashMap<>();

    /**
     * @param baseUrl OpenAI 兼容端点，如 {@code https://api.deepseek.com}
     *                （OpenAI 官方的要写成 {@code https://api.openai.com/v1}，版本路径由调用方带上）
     * @param apiKeySupplier 每次请求现取密钥，便于实现"解密后即用、用完即弃"
     */
    public OpenAiCompatibleClient(ObjectMapper mapper, String baseUrl, Supplier<String> apiKeySupplier) {
        this(mapper, baseUrl, apiKeySupplier, Duration.ofMinutes(5));
    }

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
            // 测试读 fixture 走的是同一个 drain —— 于是那些用例覆盖的就是这条真实路径
            //（[DONE] 处理、取消检查、收尾全都在里面）
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

    /** 密钥每次现取（见类注释③）。取不到就是 {@code AUTH} —— 那是要人去换一把，不是重试能解决的。 */
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
        // 让服务商在最后一个 chunk 里带上 usage —— token 预算要用真实数字，不靠估算
        payload.put("stream_options", Map.of("include_usage", true));
        payload.put("max_tokens", request.maxTokens());
        payload.put("messages", request.messages().stream()
                .map(message -> wireMessage(message, request.model())).toList());
        if (request.hasTools()) {
            payload.put("tools", request.tools().stream().map(this::wireTool).toList());
        }
        try {
            // 见类注释①：writeValueAsBytes 恒定 UTF-8，writeValueAsString().getBytes() 不是
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
        // 思考过程**原样回灌**。这是**协议要求**，不是可选的美化：
        // 思维链模式下，带 tool_calls 的那条 assistant 消息必须把它当时的
        // reasoning_content 一起带回去，不带就 400
        //（「The `reasoning_content` in the thinking mode must be passed back to the API.」）。
        //
        // 这个字段名只出现在**这一行**：领域的名字是中性的 reasoning，
        // 见 LlmMessage#assistant。换一家叫别的，改这里一处就够。
        //
        // 没有就不发（而不是发一个空串）：不产思考的模型这一项永远是 null，
        // 于是这个字段对它们根本不存在 —— **不需要一张"哪家要回传"的表**。
        //
        // 但"要不要发"还有**另一个判据**，换模型之后才显出来 —— 见 reasoningToSend。
        String reasoning = reasoningToSend(message, targetModel);
        if (reasoning != null) {
            node.put("reasoning_content", reasoning);
        }
        return node;
    }

    /**
     * 这条 assistant 消息的思考，**该不该发给目标模型**。
     *
     * <h2>为什么"有没有"不够</h2>
     * 上面那条协议要求（"必须带回来"）说的是**自己上一轮的思考**。
     * 而换模型之后就出现了另一类东西：历史里那些思考是**上一个模型产的**。
     * 照样发过去，等于让新模型去认一份不属于它的东西 —— 它那边这个字段
     * 意味着什么、会不会直接拒，**没人能替它回答**。
     *
     * <p>deepseek-harness 在这一处的判断是：**跨模型的签名不可移植**。它把"正文"
     * 和"原生元数据"分开存，原生元数据带着产出它的模型名，发请求时**拿目标模型去比对**，
     * 对不上就整份不重放。我们这边的对应物就是 {@code reasoning_content}
     *（它是纯文本，没有签名，但同样是"只对产生它的模型成立"）。
     *
     * <p>我们其实**一直有**这个来源（{@code AssistantMessage.model}），
     * 只是在折叠成 IR 的时候把它丢了。
     *
     * <h2>两个方向都不确定，所以取能自证的那一边</h2>
     * <ul>
     *   <li><b>来源未知</b>（{@code model == null}：老事件、测试）→ <b>发</b>。
     *       那是"维持旧行为"的一边，而且"该发而没发"是有案底的 400
     *       （上面那句协议要求）；</li>
     *   <li><b>来源明确且和目标不同</b> → <b>不发</b>。</li>
     * </ul>
     *
     * <p><b>还没验过的一件事</b>：目标模型自己也是思维链模型时，收到一份**别人的**
     * 历史（其中带 tool_calls 的那几条缺了 reasoning_content）会不会不认。
     * 这正是"先按 deepseek-harness 那条规则做，再拿两个模型真跑一次"要回答的问题 ——
     * 而不是在这儿猜一个。
     *
     * @return 该发就是那段思考；不该发、或者压根没有，就是 null
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
        // 参数原样回灌：任何"解析再序列化"的往返都可能改变字段顺序或数字精度，
        // 而模型对这两者敏感
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

    /**
     * 工具的 JSON Schema 是**不变的常量文本块**，但一轮 agent 最多调用模型 25 次，
     * 每次都重发同一批工具定义 —— 那是上百次"文本→树→文本"的纯重复往返。
     * 缓存一下，代价是一个只增不涨的 map（键是工具 schema，数量等于工具数）。
     */
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
     * 发一次请求，失败就按种类返回一个 {@link LlmCallException}。
     *
     * <p>用阻塞的 {@code send} 而不是 {@code sendAsync}：调用方本来就是把它当一次同步调用用的
     * （流要一边收一边按顺序交给 listener，见 {@link #stream}）。
     *
     * <h2>它**只发一次请求**，重试不在这里</h2>
     * 重试策略住在循环那一层（见 {@code AgentTurn.callModel}）：只重试
     * {@link LlmCallException#retryable()} 为真的（限流、服务商故障、连不上），每次退避
     * （服务商给了 {@code Retry-After} 就听它的），退避期间被中断当作用户取消。
     * 搬到那一层是因为适配器看不到"该不该再来一次"要看的上下文（这一轮是第几轮、
     * 用户还在不在等、这次错误值不值得再烧一次），而且这一层**没有任何事件通道** ——
     * 三次尝试之间那几秒，用户看到的是"什么都没发生"，重试在界面上看不见。
     *
     * @throws LlmCallException 这一次请求的失败。**重不重试由调用方决定**
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

        // 状态码 → 我们自己的分类。**分类的用处不是"报错更清楚"，是决定要不要重试**
        // （见 LlmCallException）：429 和 5xx 值得再来一次，400/401 再来一万次也一样。
        // 402 和 401 都属于"要人去处理"，但处理方式不同（充值 vs 换 key），所以分开。
        String providerMessage = extractProviderMessage(body, mapper);

        LlmCallException.Kind kind = switch (status) {
            // 400/422 里混着两种**意思正相反**的失败：请求写错了（该让模型改写法）
            // 和上下文装不下（该压缩）。分开它们只能在这里 —— 见 isContextOverflow
            case 400, 422 -> contextOverflow(body, providerMessage)
                    ? LlmCallException.Kind.CONTEXT_EXCEEDED
                    : LlmCallException.Kind.INVALID_REQUEST;
            case 401 -> LlmCallException.Kind.AUTH;
            case 402 -> LlmCallException.Kind.INSUFFICIENT_BALANCE;
            case 429 -> LlmCallException.Kind.RATE_LIMITED;
            default -> status >= 500 ? LlmCallException.Kind.SERVER_ERROR : LlmCallException.Kind.NETWORK;
        };

        return new LlmCallException(kind,
                "模型调用失败 HTTP " + status + "：" + providerMessage, null,
                retryAfterMillis(response));
    }

    /**
     * 这个 400/422 是不是"上下文装不下"。
     *
     * <h2>为什么这一处可以解析文本，别处不行</h2>
     * deepseek-harness 把这条线画得很清楚：**归类归适配器**（它知道自己在跟谁说话），
     * 而"恢复策略"那一层绝不许去认 provider 的措辞。这个方法就是那个
     * **唯一被允许解析文本的地方**。
     *
     * <p>先看**结构化字段**，再看措辞 —— 也是 deepseek-harness 的做法：
     * 有些 provider 在 {@code error.code} 里给了 {@code context_length_exceeded} 这种码，
     * 那比认句子可靠得多。
     *
     * <p>措辞表刻意小：认错了（把别的 400 认成溢出）代价是白压缩一次；
     * 认漏了的代价是这一轮如实失败 —— 而**如实失败**是这套东西的兜底，
     * 所以宁可少认几个，不要为了"多救几种"把措辞表堆成一团没人敢动的正则。
     */
    private static boolean contextOverflow(String body, String providerMessage) {
        String haystack = ((body == null ? "" : body) + " " + providerMessage).toLowerCase(Locale.ROOT);
        return haystack.contains("context_length_exceeded")
                || haystack.contains("maximum context length")
                || haystack.contains("context length exceeded")
                || haystack.contains("prompt is too long")
                || haystack.contains("reduce the length of the messages");
    }

    private static String extractProviderMessage(String body, ObjectMapper mapper) {
        if (body == null || body.isBlank()) {
            return "(服务端未返回错误说明)";
        }
        try {
            // 用注入的 mapper，不要为了解析一个小 JSON 体再 new 一个 ——
            // 构造 ObjectMapper 远比解析本身贵
            JsonNode error = mapper.readTree(body).path("error");
            if (error.isObject() && error.hasNonNull("message")) {
                return error.get("message").asText();
            }
        } catch (JsonProcessingException ignored) {
            // 错误响应体未必是 JSON，那就原样返回
        }
        return body.length() <= MAX_ERROR_BODY_CHARS ? body : body.substring(0, MAX_ERROR_BODY_CHARS) + "…";
    }

    private static String readBodyQuietly(InputStream body) {
        try (InputStream in = body) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 服务商说"等多久再来"（HTTP {@code Retry-After} 头）。两种写法都认。
     *
     * <h2>为什么要认 HTTP 日期那一种</h2>
     * 只认秒数的话，日期形式会被静默忽略、退回默认退避 —— 于是服务商明明说了"等到 12:05"，
     * 我们很快就又撞上去，往往换来又一次 429。认全了才有资格说"听服务商的"。
     *
     * <p>解析放在**适配器**里：只有它拿得到响应头。解出来的值跟着异常走
     *（见 {@code LlmCallException.retryAfterMs}），重试策略那一层只读字段、不碰文本。
     *
     * @return 毫秒；认不出来就是空（**不猜** —— 猜一个数等于替服务商做主）
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
            // 服务商给的时间可能已经过去了（时钟偏差、或者消息在路上耽搁了）——
            // 那就当场重试，别算出一个负数
            return Math.max(0L, Duration.between(Instant.now(), when).toMillis());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关闭失败无所谓，连接最终会自己回收
        }
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
