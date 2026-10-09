package com.codeloom.agent.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 「这个端点上现在有哪些模型」—— **问服务商本人**，不维护一份写死的清单。
 *
 * <h2>为什么不做成一份内置的模型表</h2>
 * 那等于把"支持哪些模型"写进代码里，而模型是**每周都在变**的东西：新模型上线、
 * 旧模型下线、别名指向换掉。一份内置的表从写下那天起就在过期，而它过期的表现是
 * "下拉里有这个模型、选了却报错" —— 用户完全无从判断。
 *
 * <p>换句话说：**这个问题只有服务商本人知道答案**，那就去问它。
 *
 * <h2>用的是 OpenAI 兼容的那个约定</h2>
 * {@code GET <baseUrl>/models}，和聊天端点同一种拼法（见 {@link OpenAiCompatibleClient}
 * 里的 {@code /chat/completions}）。这不是"绑死某一家"：这个项目的 {@code baseUrl}
 * 从头到尾就要求是 **OpenAI 兼容端点**，而这正是那个兼容约定的一部分。
 *
 * <h2>它拿的是**明文密钥**</h2>
 * 这是整个项目里**除了发聊天请求之外，唯一会解开密钥的地方**。边界要说清：
 * 它只发给**这把密钥自己的归属方** —— 也就是它的 host 对应的那个端点，
 * 不存在"把 A 家的凭据发给 B 家"。这不是靠自觉，是靠调用方只拿得到
 * `(那个端点的 baseUrl, 那个端点自己的密钥)` 这一对。
 */
public final class ModelCatalog {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private ModelCatalog() {
    }

    /**
     * 列出这个端点上可用的模型 id。
     *
     * @param baseUrl 端点地址，如 {@code https://api.deepseek.com}
     * @param apiKey  **该端点自己的**明文密钥
     * @return 模型 id，按原样返回（**不排序、不筛选**：服务商给什么就是什么）
     * @throws ModelCatalogException 拿不到模型列表。消息里说得清是**哪一种**拿不到 ——
     *                               密钥不对、端点不认这个路径、还是根本连不上
     */
    public static List<String> list(ObjectMapper mapper, String baseUrl, String apiKey) {
        String url = stripTrailingSlash(baseUrl) + "/models";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ModelCatalogException("连不上 " + url + "：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCatalogException("读取模型列表被中断了", e);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ModelCatalogException(
                    endpointHost(baseUrl) + " 说这把密钥不对（我们请求的是 " + url
                            + "，HTTP " + response.statusCode() + "）");
        }
        if (response.statusCode() >= 400) {
            // **把实际请求的 URL 说出来。** 没有它，这句话就只是"地址填得不对"，
            // 而人看着自己填的地址是不知道哪儿不对的 —— 我们明明知道。
            // 常见错法是把平时发消息的完整地址（结尾 /chat/completions）填进来，
            // 我们又往后接一截 /models，于是 404。
            throw new ModelCatalogException(
                    "这个端点不接受 " + url + "（HTTP " + response.statusCode() + "）。"
                            + "地址只要写到域名那一段就够了（如 https://api.deepseek.com，"
                            + "带 /v1 也行），不要填你平时发消息那个完整地址 —— "
                            + "它结尾是 /chat/completions，而我们会在后面接 /models。");
        }
        return parseIds(mapper, response.body(), url);
    }

    private static List<String> parseIds(ObjectMapper mapper, String body, String url) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new ModelCatalogException(url + " 返回的不是 JSON —— 那多半不是一个模型端点。", e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new ModelCatalogException(url + " 的响应里没有 data 数组 —— 那多半不是一个模型端点。");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode item : data) {
            String id = item.path("id").asText("");
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private static String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 只用于消息里那半句"谁说的"，读不出来就返回原串。 */
    private static String endpointHost(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException e) {
            return baseUrl;
        }
    }

    /** 拿不到模型列表。**消息是写给用户看的** —— 它要说清下一步该改什么。 */
    public static class ModelCatalogException extends RuntimeException {
        public ModelCatalogException(String message) {
            super(message);
        }

        public ModelCatalogException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
