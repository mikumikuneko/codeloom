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
 * 从服务商的 {@code GET BASE_URL/models} 读取可用模型，不维护内置清单：
 * 模型会下线、别名会变，内置表过期的表现是“下拉里能选、选了却报错”。
 *
 * <p>{@code baseUrl} 必须是 OpenAI 兼容端点。密钥只发给它自己的端点，调用方只能拿到
 * “该端点的 baseUrl + 该端点的密钥”这一对。见
 * {@code docs/decisions/feature/2026-09-29-model-list-fetched-not-typed.md}。
 */
public final class ModelCatalog {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private ModelCatalog() {
    }

    /**
     * 列出这个端点上可用的模型 id。
     *
     * @param mapper  JSON 解析器
     * @param baseUrl 端点地址，如 {@code https://api.deepseek.com}
     * @param apiKey  该端点自己的明文密钥
     * @return 模型 id，按服务商返回的顺序；缺少 id 的条目跳过
     * @throws ModelCatalogException 拿不到列表；消息说明是密钥不对、路径不对还是连不上
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
            // 必须报出实际请求的 URL：常见错误是把 /chat/completions 的完整地址填进来，
            // 这里又接上 /models，于是 404。
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

    static String stripTrailingSlash(String baseUrl) {
        String trimmed = baseUrl.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** 只用于消息里那半句"谁说的"，读不出来就返回原串。 */
    private static String endpointHost(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException e) {
            return baseUrl;
        }
    }

    /** 拿不到模型列表；消息写给用户看，说明下一步该改什么。 */
    public static class ModelCatalogException extends RuntimeException {
        public ModelCatalogException(String message) {
            super(message);
        }

        public ModelCatalogException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
