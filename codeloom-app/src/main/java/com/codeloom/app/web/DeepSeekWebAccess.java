package com.codeloom.app.web;

import com.codeloom.agent.llm.openai.ProviderErrorBody;
import com.codeloom.agent.web.WebAccess;
import com.codeloom.agent.web.WebAccessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * DeepSeek 的 Search / Fetch 接口（{@code /v1/tools/search}、{@code /v1/tools/fetch}）。
 *
 * <p>两个路径写死为 {@code baseUrl + "/v1/tools/..."}，与聊天接口的
 * {@code baseUrl + "/chat/completions"} 版本段位置不同；混用会打到 404，看起来却像服务商故障。
 *
 * <p>密钥经 {@code Supplier} 每次请求现取：中途被删时立即报清楚，而不是拿作废的值继续发送。
 *
 * <p>错误码翻译成模型能据此行动的话：频率超限、余额不足、网址抓不到是三种不同处境，
 * 只回状态码会让模型重复发送同一请求。
 */
public final class DeepSeekWebAccess implements WebAccess {

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;
    private final Supplier<String> apiKeySupplier;

    public DeepSeekWebAccess(ObjectMapper mapper, HttpClient http, String baseUrl,
                             Supplier<String> apiKeySupplier) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.http = Objects.requireNonNull(http, "http");
        String base = Objects.requireNonNull(baseUrl, "baseUrl").strip();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
    }

    /**
     * @param query 搜索词
     * @param count 最多返回多少条
     * @return 搜索结果；不包含长摘要与正文
     */
    @Override
    public List<Hit> search(String query, int count) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query);
        body.put("count", count);
        // 不取正文：十条正文既没必要也装不下；需要正文时用 web_fetch 取链接
        body.put("need_content", false);

        JsonNode response = post("/v1/tools/search", body, "搜索");
        List<Hit> hits = new ArrayList<>();
        for (JsonNode item : response.path("results")) {
            // 不取 summary 和 content：长摘要会让模型不去读原文，正文只由 web_fetch 按需取
            hits.add(new Hit(
                    text(item, "title"),
                    text(item, "url"),
                    text(item, "snippet"),
                    text(item, "site_name"),
                    text(item, "date")));
        }
        return hits;
    }

    /**
     * @param url 要抓取的网页
     * @return 标题与正文
     */
    @Override
    public Page fetch(String url) {
        ObjectNode body = mapper.createObjectNode();
        body.put("url", url);

        JsonNode response = post("/v1/tools/fetch", body, "抓取");
        return new Page(text(response, "title"), text(response, "content"));
    }

    // ------------------------------------------------------------------

    private JsonNode post(String path, ObjectNode body, String operation) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKeySupplier.get())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(encode(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new WebAccessException("网络错误：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebAccessException("等待响应时被中断", e);
        }

        if (response.statusCode() != 200) {
            throw toException(response, operation);
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new WebAccessException("服务商返回的内容读不出来：" + e.getMessage(), e);
        }
    }

    private WebAccessException toException(HttpResponse<String> response, String operation) {
        int status = response.statusCode();
        // 与模型调用共用同一份解析：同一个服务端、同一套错误格式
        String detail = ProviderErrorBody.message(response.body(), mapper);

        String reason = switch (status) {
            case 400 -> "请求不合法";
            case 401 -> "DeepSeek 的 API Key 无效或已过期";
            case 402 -> "DeepSeek 账户余额不足";
            case 403 -> "这个网址被安全策略拒绝了";
            case 404 -> "没能取到可用的网页内容（链接不可用、被站点拦截，或没有可读的正文）";
            case 408 -> "服务商读取请求超时";
            case 413 -> "请求体太大";
            case 429 -> "请求太频繁（这两个接口按账号限 5 QPS），稍后再试";
            default -> status >= 500 ? "服务商暂时不可用，稍后再试" : "服务商返回了 HTTP " + status;
        };
        String suffix = detail.isEmpty() ? "" : "：" + detail;
        return new WebAccessException(operation + "失败（" + reason + "）" + suffix);
    }

    private byte[] encode(JsonNode body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            // 写一个内存里的树不会失败；真失败了也不是模型能自修的事
            throw new IllegalStateException("拼请求体失败", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
