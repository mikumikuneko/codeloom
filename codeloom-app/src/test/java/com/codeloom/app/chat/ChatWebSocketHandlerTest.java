package com.codeloom.app.chat;

import com.codeloom.app.support.TestUsers;
import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天室的真库测试，连接是 Mockito 桩出来的 {@code WebSocketSession}。
 *
 * <p>这里验处理器：身份从哪来、消息发给谁、坏消息怎么处理。握手另由
 * {@code ChatWebSocketHandshakeTest} 覆盖，分开验失败时指向的原因才清楚。
 * 假连接记下所有推出的帧，断言直接看“这人收到了什么”，不数方法调用次数。
 */
// MOCK 而不是 NONE：处理器要过 ProjectAccess，而 app 层这套图整体是 Web 有条件的，
// 不起 Web 上下文这个 bean 就不存在。MOCK 只搭假的 servlet 环境，不真开端口。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isDatabaseReachable")
@Transactional
class ChatWebSocketHandlerTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-09-25T10:00:00Z");
    private static final String BCrypt_HASH = TestUsers.PASSWORD_HASH;

    @Autowired
    private ChatWebSocketHandler handler;

    @Autowired
    private UserRepository users;

    @Autowired
    private ChatMessageRepository messages;

    @Autowired
    private ProjectRepository projects;

    /** 每个测试一个新项目：聊天消息按项目隔离，这样断言就不会被别人的数据干扰。 */
    private final ProjectId projectId = ProjectId.generate();

    private User alice;
    private User bob;
    /** 一个不在项目里的人，用来验“登录了不等于能进这个聊天室”。 */
    private User stranger;

    @BeforeEach
    void seedTwoUsers() {
        // 账号和用户名都要随机：两条唯一键，写死会让测试在库里真有同名行时莫名地红。
        alice = register("alice");
        bob = register("bob");
        stranger = register("stranger");

        // 项目必须真的存在、alice/bob 真的是成员：建连要过
        // ProjectAccess.requireMember，随机 uuid 进不来。
        projects.save(new Project(projectId, alice.id(), "聊天测试项目",
                "D:/repos/" + projectId.value(), Set.of(alice.id(), bob.id())));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("没有身份的连接会被直接关掉")
    void anonymousConnectionsAreRejected() throws Exception {
        FakeClient anonymous = new FakeClient(null, projectId);

        handler.afterConnectionEstablished(anonymous.session());

        // 假连接没有 principal（过滤器链没参与），所以走到防御分支 —— 这里验的正是它。
        verify(anonymous.session()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("没有 projectId 的连接也会被关掉")
    void connectionsWithoutAProjectAreRejected() throws Exception {
        FakeClient noProject = new FakeClient(alice.username(), projectId);
        when(noProject.session().getUri())
                .thenReturn(URI.create(ChatWebSocketHandler.PATH));

        handler.afterConnectionEstablished(noProject.session());

        verify(noProject.session()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("【安全】登录了但不是成员 → 照样关掉，而且和「项目不存在」看起来一样")
    void nonMembersAreRejected() throws Exception {
        FakeClient outsider = connect(stranger, projectId);

        // projectId 是调用方写在 URL 里的，光有它什么都证明不了；
        // 少了这一道，任何登录用户填一个 uuid 就能读别人的聊天记录。
        verify(outsider.session()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("【安全】项目根本不存在时，和被拒绝是同一个结果 —— 枚举不出东西来")
    void unknownProjectsLookExactlyLikeRejections() throws Exception {
        FakeClient onNothing = connect(alice, ProjectId.generate());

        // 对越权装作不存在：拿一批 id 挨个试，试不出系统里有哪些项目。
        verify(onNothing.session()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("一条消息：落库、时间由服务端盖、广播给同项目的每个连接（包括发送者自己）")
    void messageIsPersistedAndBroadcast() throws Exception {
        FakeClient aliceClient = connect(alice);
        FakeClient bobClient = connect(bob);

        handler.handleTextMessage(aliceClient.session(), new TextMessage(
                "{\"text\":\"你这段不对\",\"anchorEventSeq\":42,"
                        + "\"anchorText\":\"编辑了 OrderService.java\"}"));

        ChatMessage saved = messages.findRecent(projectId, 10).getFirst();
        assertThat(saved.text()).isEqualTo("你这段不对");
        assertThat(saved.authorId()).isEqualTo(alice.id());
        assertThat(saved.anchorEventSeq()).isEqualTo(42L);
        // 摘要随消息一起存：界面上显示“引用了 · 编辑了 OrderService.java”靠它，
        // 而不是回头查那条事件（那属于对方的流，且会随会话清理消失）。
        assertThat(saved.anchorText()).isEqualTo("编辑了 OrderService.java");
        assertThat(saved.createdAt()).isNotNull();

        // 发送者也收到自己那条：前端只有一条渲染路径，不另发 ack。
        assertThat(aliceClient.received()).singleElement()
                .satisfies(json -> assertThat(json).contains("\"text\":\"你这段不对\""));
        assertThat(bobClient.received()).singleElement()
                .satisfies(json -> assertThat(json).contains("\"text\":\"你这段不对\""));
    }

    @Test
    @DisplayName("【安全约束】说话的人来自连接上的身份，消息体里塞 authorId 不起作用")
    void theAuthorComesFromTheConnectionNotThePayload() throws Exception {
        // 从消息体取作者等于允许冒充；ChatInbound 没有 authorId 字段，这个键会被忽略。
        FakeClient aliceClient = connect(alice);

        handler.handleTextMessage(aliceClient.session(), new TextMessage(
                "{\"text\":\"我是 bob\",\"authorId\":\"" + bob.id().value() + "\"}"));

        assertThat(messages.findRecent(projectId, 10).getFirst().authorId())
                .isEqualTo(alice.id());
    }

    @Test
    @DisplayName("别的项目的人收不到")
    void otherProjectsDoNotHearIt() throws Exception {
        FakeClient aliceClient = connect(alice);
        FakeClient outsider = connect(bob, ProjectId.generate());

        handler.handleTextMessage(aliceClient.session(), new TextMessage("{\"text\":\"悄悄话\"}"));

        assertThat(aliceClient.received()).hasSize(1);
        assertThat(outsider.received()).isEmpty();
    }

    @Test
    @DisplayName("连接断开之后就不再收到")
    void disconnectedClientsStopReceiving() throws Exception {
        FakeClient aliceClient = connect(alice);
        FakeClient bobClient = connect(bob);
        handler.afterConnectionClosed(bobClient.session(), CloseStatus.NORMAL);

        handler.handleTextMessage(aliceClient.session(), new TextMessage("{\"text\":\"走了之后发的\"}"));

        assertThat(aliceClient.received()).hasSize(1);
        assertThat(bobClient.received()).isEmpty();
    }

    @Test
    @DisplayName("坏消息：不落库、连接不断，但回一个 error 帧")
    void malformedMessagesGetAnErrorFrame() throws Exception {
        // 坏消息不值得断开连接（客户端可能只发了半截），但必须回错误帧，
        // 否则前端表现为“消息发出去了，但谁也没看见”。
        FakeClient aliceClient = connect(alice);

        handler.handleTextMessage(aliceClient.session(), new TextMessage("这不是 JSON"));

        assertThat(aliceClient.received()).singleElement()
                .satisfies(json -> assertThat(json).contains("\"error\""));
        assertThat(messages.findRecent(projectId, 10)).isEmpty();
    }

    // ------------------------------------------------------------------

    private FakeClient connect(User user) throws Exception {
        return connect(user, projectId);
    }

    private FakeClient connect(User user, ProjectId project) throws Exception {
        FakeClient client = new FakeClient(user.username(), project);
        handler.afterConnectionEstablished(client.session());
        return client;
    }

    private User register(String namePrefix) {
        // 用户名带随机尾巴、但留着可读的前缀：断言和排障时认得出是谁
        User user = User.register(namePrefix + "-" + UUID.randomUUID(), BCrypt_HASH,
                namePrefix + "-" + UUID.randomUUID().toString().substring(0, 8), REGISTERED_AT);
        users.save(user);
        return user;
    }

    /** 一个假的浏览器连接：记下所有推给它的帧。 */
    private static final class FakeClient {

        private final WebSocketSession session;
        private final List<String> received = new ArrayList<>();

        /** {@code throws IOException} 是桩 {@code sendMessage} 带出来的 —— 它声明了受检异常。 */
        private FakeClient(String username, ProjectId projectId) throws IOException {
            this.session = mock(WebSocketSession.class);
            if (username != null) {
                Principal principal = () -> username;
                when(session.getPrincipal()).thenReturn(principal);
            }
            when(session.getUri()).thenReturn(URI.create(
                    ChatWebSocketHandler.PATH + "?" + ChatWebSocketHandler.PROJECT_ID_PARAM + "="
                            + projectId.value()));
            when(session.isOpen()).thenReturn(true);
            doAnswer(invocation -> {
                received.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(session).sendMessage(any());
        }

        WebSocketSession session() {
            return session;
        }

        List<String> received() {
            return received;
        }
    }
}
