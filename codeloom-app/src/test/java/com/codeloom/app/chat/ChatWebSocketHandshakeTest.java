package com.codeloom.app.chat;

import com.codeloom.app.support.TestBrowser;
import com.codeloom.domain.project.ProjectId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.codeloom.app.support.TestBrowser.newUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 聊天室的真实 WebSocket 握手。
 *
 * <p>{@code ChatWebSocketHandlerTest} 把 {@code session.getPrincipal()} 全桩死了，
 * 验的是“给我身份，我能正确处理消息”；真实握手到底给不给得出身份，只有这里能验。
 * 链路任一步断掉，所有聊天连接都会在建立时因拿不到身份而被关掉，而桩测试依然全绿。
 *
 * <p>真连接还能验身份来自连接、不是消息体：入站结构没有 authorId 字段。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
class ChatWebSocketHandshakeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private final List<String> createdUsernames = new ArrayList<>();
    private final List<ProjectId> createdProjects = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (ProjectId projectId : createdProjects) {
            jdbc.update("DELETE FROM chat_message WHERE project_id = ?", projectId.value());
            jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId.value());
            jdbc.update("DELETE FROM project WHERE id = ?", projectId.value());
        }
        for (String username : createdUsernames) {
            jdbc.update("DELETE FROM `user` WHERE username = ?", username);
        }
        createdProjects.clear();
        createdUsernames.clear();
    }

    @Test
    @DisplayName("【真实握手】登录之后连得上，发出去的话能从自己的连接上收回来 —— 身份来自 cookie")
    void anAuthenticatedBrowserCanActuallyChat() throws Exception {
        TestBrowser browser = newAccount();
        ProjectId projectId = createProject(browser);
        String authorId = jdbc.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", String.class, browser.username());

        ChatProbe probe = ChatProbe.connect(port, browser.sessionCookie(), projectId);

        probe.send(json.writeValueAsString(Map.of("text", "你这段不对")));
        JsonNode broadcast = json.readTree(probe.awaitMessage());

        assertThat(broadcast.get("text").asText()).isEqualTo("你这段不对");
        assertThat(broadcast.get("projectId").asText()).isEqualTo(projectId.value());
        // 作者是连接上的身份；入站结构没有 authorId，冒充在协议上没有入口。
        assertThat(broadcast.get("authorId").asText()).isEqualTo(authorId);
        // 连接还在：身份拿不到的话 handler 会在建立时就关掉它。
        probe.assertStillOpen();
    }

    @Test
    @DisplayName("【安全前提】没有会话的握手在 HTTP 层就被挡掉（401），到不了处理器")
    void anonymousHandshakesNeverReachTheHandler() {
        // 验的是“挡在哪儿”：不是 handler 的 POLICY_VIOLATION（那说明握手先成功了），
        // 而是握手请求本身返回 401。不断言具体异常类型：JDK 把它包成内部类，
        // 名字随版本会变。
        assertThatThrownBy(() -> ChatProbe.connect(port, null, ProjectId.generate()))
                .isInstanceOf(ExecutionException.class)
                .rootCause()
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("【安全】登录了但不是成员 → 连上就被立刻关掉，进不去别人的聊天室")
    void nonMembersCannotJoinSomeoneElsesChatRoom() throws Exception {
        // 主人建了项目（建的人自然是成员）
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);

        // 另一个人登录了 —— 认证没问题，但他不是这个项目的成员
        TestBrowser outsider = newAccount();

        // 端点若只检查“登录了吗”，任何登录用户填一个 projectId 就能读别人的聊天记录。
        //
        // 断言的是“连上之后被关掉”，不是“连不上”：握手本身会成功（认证过了），
        // 关闭发生在 afterConnectionEstablished。前端会看到 onopen 紧跟 onclose，
        // 而“不是成员”和“项目不存在”看起来完全一样。
        ChatProbe outsiderProbe = ChatProbe.connect(port, outsider.sessionCookie(), projectId);
        outsiderProbe.assertClosedByServer();

        // 成员照常连得上，证明拒绝的是“不是你”，不是整个功能。
        ChatProbe member = ChatProbe.connect(port, owner.sessionCookie(), projectId);
        member.assertStillOpen();
    }

    // ------------------------------------------------------------------

    private TestBrowser newAccount() throws Exception {
        TestBrowser browser = TestBrowser.at(port);
        String username = newUsername();
        createdUsernames.add(username);
        browser.register(username);
        return browser;
    }

    private ProjectId createProject(TestBrowser owner) throws Exception {
        var created = owner.post("/api/projects", """
                {"name":"项目-%s"}
                """.formatted(java.util.UUID.randomUUID()));
        assertThat(created.statusCode()).isEqualTo(201);
        ProjectId id = ProjectId.of(json.readTree(created.body()).get("id").asText());
        createdProjects.add(id);
        return id;
    }

    /**
     * 一个真的 WebSocket 客户端：等一条下行消息，并且看得见连接被关掉。
     *
     * <p>两个等待都带超时，否则“连上了但什么都收不到”会让测试挂住，
     * 排查体验和断言失败完全不同。
     */
    private static final class ChatProbe implements WebSocket.Listener {

        private static final Duration WAIT = Duration.ofSeconds(5);

        private final CompletableFuture<String> nextMessage = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder fragments = new StringBuilder();

        private WebSocket socket;

        static ChatProbe connect(int port, String sessionCookie, ProjectId projectId)
                throws Exception {
            ChatProbe probe = new ChatProbe();
            WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(WAIT);
            if (sessionCookie != null) {
                // JDK 的 WebSocket 客户端不共享 CookieManager，只能自己塞；
                // 这条通道上除了 cookie 也没有别的凭据方式（EventSource 同理）。
                builder.header("Cookie", sessionCookie);
            }
            probe.socket = builder.buildAsync(
                            URI.create("ws://localhost:" + port + ChatWebSocketHandler.PATH
                                    + "?" + ChatWebSocketHandler.PROJECT_ID_PARAM + "="
                                    + projectId.value()),
                            probe)
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return probe;
        }

        void send(String payloadJson) throws Exception {
            socket.sendText(payloadJson, true).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        String awaitMessage() throws Exception {
            return nextMessage.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        /**
         * 连接没有被服务端关掉。
         *
         * <p>刻意用非阻塞的 {@code isDone()} 而不是 {@code join()}：连接正常开着时，
         * 关闭那个 future 永远不会完成，{@code join()} 会让测试挂死。
         */
        void assertStillOpen() {
            assertThat(closed.isDone())
                    .as("服务端把连接关掉了（状态码 %s）", closed.getNow(null))
                    .isFalse();
        }

        /**
         * 连接被服务端关掉了。
         *
         * <p>这里必须等，不能像 {@link #assertStillOpen()} 那样看一眼就走：关闭是服务端
         * 异步做的，握手回来那一刻它可能还没关。
         */
        void assertClosedByServer() {
            try {
                closed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("服务端没有在 " + WAIT + " 内关掉这条连接", e);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                nextMessage.complete(fragments.toString());
                fragments.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }
    }
}
