package com.codeloom.app.web;

import com.codeloom.agent.web.WebAccess;
import com.codeloom.agent.web.WebAccessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用 JDK 自带的 {@code com.sun.net.httpserver} 回放服务商响应。
 *
 * <p>整条链路（拼路径、带密钥、发请求、解析、翻译错误）均可离线测试，
 * 不需要网络，也不需要真实 API Key。
 */
class DeepSeekWebAccessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("搜索：打到文档写的 /v1/tools/search，带上密钥，不取正文")
    void searchUsesTheDocumentedPath() throws Exception {
        String baseUrl = startServer(200, """
                {"results":[
                  {"title":"DeepSeek V4 发布","url":"https://example.com/a",
                   "snippet":"短摘要","summary":"长摘要","content":"",
                   "site_name":"Example News","date":"2026-06-01T00:00:00+00:00"},
                  {"title":"缺字段的一条","url":"https://example.com/b"}
                ]}""");

        List<WebAccess.Hit> hits = client(baseUrl).search("DeepSeek 新闻", 3);

        CapturedRequest request = captured.get();
        assertThat(request.path).isEqualTo("/v1/tools/search");
        assertThat(request.authorization).isEqualTo("Bearer test-key");
        JsonNode body = MAPPER.readTree(request.body);
        assertThat(body.get("query").asText()).isEqualTo("DeepSeek 新闻");
        assertThat(body.get("count").asInt()).isEqualTo(3);
        assertThat(body.get("need_content").asBoolean()).isFalse();

        assertThat(hits).hasSize(2);
        assertThat(hits.getFirst().title()).isEqualTo("DeepSeek V4 发布");
        assertThat(hits.getFirst().snippet()).isEqualTo("短摘要");
        assertThat(hits.getFirst().siteName()).isEqualTo("Example News");
        // 缺的字段是 null，不是空串 —— 工具按"有没有"决定省不省那一行
        assertThat(hits.get(1).snippet()).isNull();
        assertThat(hits.get(1).date()).isNull();
    }

    @Test
    @DisplayName("抓取：打到 /v1/tools/fetch，正文按 Markdown 原样回来")
    void fetchUsesItsOwnPath() throws Exception {
        String baseUrl = startServer(200, """
                {"title":"Example article","content":"# Example article\\n\\nArticle content."}""");

        WebAccess.Page page = client(baseUrl).fetch("https://example.com/a");

        assertThat(captured.get().path).isEqualTo("/v1/tools/fetch");
        assertThat(MAPPER.readTree(captured.get().body).get("url").asText())
                .isEqualTo("https://example.com/a");
        assertThat(page.title()).isEqualTo("Example article");
        assertThat(page.content()).contains("Article content.");
    }

    @Test
    @DisplayName("429 翻成模型能据此行动的话：等一会儿再来")
    void rateLimitIsActionable() throws Exception {
        String baseUrl = startServer(429, """
                {"error":{"message":"rate limit exceeded","type":"invalid_request_error","param":null}}""");

        assertThatThrownBy(() -> client(baseUrl).search("x", 5))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("5 QPS")
                .hasMessageContaining("rate limit exceeded");
    }

    @Test
    @DisplayName("402 是余额不足，不是「稍后再试」")
    void insufficientBalanceIsNotRetryable() throws Exception {
        String baseUrl = startServer(402, """
                {"error":{"message":"insufficient balance"}}""");

        assertThatThrownBy(() -> client(baseUrl).fetch("https://example.com/a"))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("余额不足");
    }

    @Test
    @DisplayName("404 说明取不到正文 —— 该换个链接，而不是重试")
    void notFoundExplainsItself() throws Exception {
        String baseUrl = startServer(404, """
                {"error":{"message":"no content"}}""");

        assertThatThrownBy(() -> client(baseUrl).fetch("https://example.com/gone"))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("没能取到可用的网页内容");
    }

    @Test
    @DisplayName("错误体不是 JSON 时，把原文截一段带上")
    void nonJsonErrorsKeepTheRawBody() throws Exception {
        String baseUrl = startServer(503, "upstream exploded");

        assertThatThrownBy(() -> client(baseUrl).search("x", 5))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("暂时不可用")
                .hasMessageContaining("upstream exploded");
    }

    @Test
    @DisplayName("密钥每次请求现取 —— 中途换了，下一次就用新的")
    void readsTheKeyPerRequest() throws Exception {
        String baseUrl = startServer(200, "{\"results\":[]}");
        AtomicInteger calls = new AtomicInteger();
        DeepSeekWebAccess client = new DeepSeekWebAccess(MAPPER, HttpClient.newHttpClient(), baseUrl,
                () -> "key-" + calls.incrementAndGet());

        client.search("a", 1);
        assertThat(captured.get().authorization).isEqualTo("Bearer key-1");
        client.search("b", 1);
        assertThat(captured.get().authorization).isEqualTo("Bearer key-2");
    }

    // ------------------------------------------------------------------

    private DeepSeekWebAccess client(String baseUrl) {
        return new DeepSeekWebAccess(MAPPER, HttpClient.newHttpClient(), baseUrl, () -> "test-key");
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new CapturedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, status, body);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record CapturedRequest(String path, String authorization, String body) {
    }
}
