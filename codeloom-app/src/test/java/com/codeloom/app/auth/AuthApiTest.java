package com.codeloom.app.auth;

import com.codeloom.app.support.TestBrowser;
import com.codeloom.app.support.TestSessions;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.codeloom.app.support.TestBrowser.newUsername;
import static com.codeloom.app.support.TestBrowser.registerBody;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证接口的真 HTTP 测试：起真容器、发真请求，用 {@link TestBrowser} 的 cookie 罐
 * 当浏览器。会话 cookie 只有在真 HTTP 往返里才验得了，mock 掉这一层等于把要验的东西
 * 验没了。
 *
 * <p>不加 {@code @Transactional}：请求跑在容器线程、用另一条连接，测试事务里未提交的行
 * 它看不见。所以数据是真提交的，收尾要自己清（见 {@link #cleanUp()}）；账号一律随机，
 * 清漏了也不会让下一次测试莫名地红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
// 把 SSE 的寿命压到 1 秒：不然那条开的流要挂 30 分钟，而容器关闭时会一直等它 ——
// 表现是整个测试进程卡死，而不是某条断言失败
@TestPropertySource(properties = "codeloom.stream.timeout=1s")
class AuthApiTest {

    /** 和 application.yml 里的 {@code spring.session.redis.namespace} 保持一致。 */
    private static final String SESSION_NAMESPACE = "codeloom:session:";

    /** 登录成功/失败两种结果都要验，所以密码得自己定，不能用 TestBrowser 那个默认的。 */
    private static final String RIGHT_PASSWORD = "正确的密码";
    private static final String WRONG_PASSWORD = "猜的密码";

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private WorkspaceRepository worktrees;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private final List<String> registeredUsernames = new ArrayList<>();
    private final List<SessionId> createdSessions = new ArrayList<>();
    private final List<ProjectId> createdProjects = new ArrayList<>();
    private final Set<String> newSessionKeys = new HashSet<>();

    @AfterEach
    void cleanUp() {
        // 真提交的东西自己收。项目里刻意不建外键，删除顺序不重要。
        for (SessionId sessionId : createdSessions) {
            jdbc.update("DELETE FROM `event` WHERE session_id = ?", sessionId.value());
            // 树也要收：新会话会复用同一个 (owner, project) 的行，留着会把 fencing_token
            // 一起继承过去。会话行删掉后就反查不到树了，所以这条必须先跑。
            jdbc.update("""
                    DELETE w FROM workspace w
                    JOIN session s ON s.owner_id = w.owner_id AND s.project_id = w.project_id
                    WHERE s.id = ?
                    """, sessionId.value());
            jdbc.update("DELETE FROM session WHERE id = ?", sessionId.value());
        }
        for (ProjectId projectId : createdProjects) {
            jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId.value());
            jdbc.update("DELETE FROM project WHERE id = ?", projectId.value());
        }
        for (String username : registeredUsernames) {
            jdbc.update("DELETE FROM `user` WHERE username = ?", username);
        }
        // 只删本次新造的会话键；按前缀全删会把别人（比如手工登录的）也踢掉，
        // 而这里是共享的开发用 Redis。
        if (!newSessionKeys.isEmpty()) {
            redis.delete(newSessionKeys);
        }
        createdSessions.clear();
        createdProjects.clear();
        registeredUsernames.clear();
        newSessionKeys.clear();
    }

    private Set<String> sessionKeys() {
        return redis.keys(SESSION_NAMESPACE + "*");
    }

    /**
     * 注册一个新账号，返回一个已经带着会话 cookie 的浏览器。
     *
     * <p>账号从 {@link TestBrowser#username()} 拿；它同时被登记进收尾清单。
     */
    private TestBrowser newAccount() throws Exception {
        return newAccount(TestBrowser.PASSWORD);
    }

    private TestBrowser newAccount(String password) throws Exception {
        TestBrowser browser = TestBrowser.at(port);
        String username = newUsername();
        registeredUsernames.add(username);
        browser.register(username, password);
        return browser;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("未登录访问受保护接口 → 401，而不是重定向到登录页")
    void protectedEndpointsRequireAuthentication() throws Exception {
        HttpResponse<String> response = TestBrowser.at(port).get("/api/auth/me");

        // 401 而不是 302：重定向是给传统页面应用用的，API 客户端拿到它只会困惑。
        assertThat(response.statusCode()).isEqualTo(401);
        // 身体里得有人话：这一支到不了 ApiExceptionHandler，而前端直接显示 detail。
        assertThat(response.body()).contains("请先登录");
    }

    @Test
    @DisplayName("注册 → 201，而且直接就是登录状态")
    void registeringLogsYouIn() throws Exception {
        TestBrowser browser = TestBrowser.at(port);
        String username = newUsername();
        registeredUsernames.add(username);

        HttpResponse<String> registered = browser.register(username);

        assertThat(registered.body()).contains(username).doesNotContain("passwordHash");
        // 刚注册的这个"浏览器"已经带着会话 cookie 了
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("登录：密码对就 200，密码错就 401")
    void loginChecksThePassword() throws Exception {
        TestBrowser owner = newAccount(RIGHT_PASSWORD);
        String username = owner.username();

        TestBrowser right = TestBrowser.at(port);
        assertThat(right.login(username, RIGHT_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(right.get("/api/auth/me").statusCode()).isEqualTo(200);

        TestBrowser wrong = TestBrowser.at(port);
        HttpResponse<String> rejected = wrong.login(username, WRONG_PASSWORD);
        assertThat(rejected.statusCode()).isEqualTo(401);
        // 那句话刻意不说是账号还是密码（见 AuthController）。
        assertThat(rejected.body()).contains("账号或密码不对");
        // 失败的登录不该留下已认证的会话。
        assertThat(wrong.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("登出之后那个会话就作废了")
    void loggingOutInvalidatesTheSession() throws Exception {
        TestBrowser browser = newAccount();

        assertThat(browser.post("/api/auth/logout", "{}").statusCode()).isEqualTo(204);
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("账号被占用 → 409（不是 400：请求本身没问题，是和现有资源冲突）")
    void duplicateUsernamesAreRejected() throws Exception {
        TestBrowser owner = newAccount();

        HttpResponse<String> again = TestBrowser.at(port).post("/api/auth/register",
                registerBody(owner.username(), "另一个密码", "冒充者"));

        assertThat(again.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("用户名被占用 → 409 —— 一个系统里不能有两个同名的人")
    void duplicateDisplayNamesAreRejected() throws Exception {
        TestBrowser owner = newAccount();

        // 换账号、照抄用户名：账号查重排在前头，不换账号就撞不到用户名那一条。
        HttpResponse<String> again = TestBrowser.at(port).post("/api/auth/register",
                registerBody("user-" + UUID.randomUUID(), "另一个密码", owner.username()));

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("这个用户名已被占用");
    }

    @Test
    @DisplayName("【安全约束】密码哈希绝不出现在响应体里")
    void passwordHashesNeverLeaveTheServer() throws Exception {
        TestBrowser owner = newAccount();
        String username = owner.username();

        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM `user` WHERE username = ?", String.class, username);

        assertThat(storedHash).isNotBlank();
        // 库里确实存了哈希（而且不是明文），但响应体里一个字都不该有
        assertThat(storedHash).isNotEqualTo(RIGHT_PASSWORD);
        assertThat(owner.get("/api/auth/me").body()).doesNotContain(storedHash);
    }

    // ------------------------------------------------------------------
    // 会话状态放在哪
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【水平扩展】登录之后的会话在 Redis 里 —— 换个实例也认识它")
    void sessionsLiveInRedisNotInThisInstancesHeap() throws Exception {
        Set<String> before = sessionKeys();

        TestBrowser browser = newAccount();

        // 会话必须写进共享存储，这是“实例 A 登录、实例 B 认得”的前提；
        // 留在 Tomcat 堆里的话这一条会红。
        Set<String> created = new HashSet<>(sessionKeys());
        created.removeAll(before);
        newSessionKeys.addAll(created);

        assertThat(created).as("注册之后 Redis 里应该多出这个会话").isNotEmpty();

        // 而且那个会话确实能被另一条连接当作身份用 —— 这就是"换个实例"在测试里能做到的近似
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("【安全前提】会话 cookie 带着 HttpOnly 和 SameSite=Lax")
    void theSessionCookieCarriesItsSafetyAttributes() throws Exception {
        TestBrowser browser = TestBrowser.at(port);
        String username = newUsername();
        registeredUsernames.add(username);

        HttpResponse<String> response = browser.register(username);

        String cookie = response.headers().allValues("set-cookie").stream()
                .filter(value -> value.contains("SESSION"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("注册响应里没有会话 cookie"));

        // SameSite=Lax 是关掉 CSRF 校验的唯一依据；改成 none 或删掉那行配置，
        // CSRF 的论证当场失效，而且不会表现为任何功能故障。
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax");
    }

    // ------------------------------------------------------------------
    // 实时事件流
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【解锁】SSE 端点：未登录 401；登录之后 200、是 text/event-stream，而且立刻收到开流注释帧")
    void theSessionStreamIsReachableOnceAuthenticated() throws Exception {
        // 未登录返回 401，而且挡在控制器之前；用不存在的会话 id 就够，
        // 它连授权那一步都到不了。
        String notMine = "/api/sessions/" + SessionId.generate().value() + "/stream";
        assertThat(TestBrowser.at(port).get(notMine).statusCode()).isEqualTo(401);

        TestBrowser browser = newAccount();
        String path = "/api/sessions/" + sessionOf(browser).id().value() + "/stream";

        // 用流式拿法：响应头到了就返回，不会等这个永不结束的流
        HttpResponse<InputStream> streaming = browser.getStreaming(path);

        try (InputStream body = streaming.body()) {
            assertThat(streaming.statusCode()).isEqualTo(200);
            assertThat(streaming.headers().firstValue("content-type"))
                    .contains("text/event-stream");

            // 这是开流时同步写出的第一个字节：少了它，响应头要等第一条事件才出去，
            // 安静的会话会让客户端一直停在“连接中”。
            assertThat(readBounded(body, 64)).startsWith(":codeloom stream open");
        }
    }

    @Test
    @DisplayName("【安全】登录了但不是成员 → 404，订阅不到别人的事件流")
    void nonMembersCannotSubscribeToSomeoneElsesStream() throws Exception {
        TestBrowser owner = newAccount();
        Session session = sessionOf(owner);

        // 另一个人：认证没问题，但他不在那个项目里
        TestBrowser outsider = newAccount();

        // 404 而不是 403：对“不是我的东西”一律装作不存在，免得拿一批 id 枚举出会话。
        // 少了这一道，任何登录用户只要知道 id 就能订阅别人的事件流。
        assertThat(outsider.get("/api/sessions/" + session.id().value() + "/stream").statusCode())
                .isEqualTo(404);
    }

    /**
     * 给这个浏览器的人造一个项目和他自己的会话，返回那条会话。
     *
     * <p>走 jdbc 而不是接口：接口建会话要求先配 API Key，而本类没有桩模型，
     * 为验一条流去准备模型凭据会把测试绑到无关的事上。成员关系必须是真的 ——
     * SSE 通道要过 {@code ProjectAccess.requireVisible}，随机 uuid 连不上。
     */
    private Session sessionOf(TestBrowser browser) {
        String userId = jdbc.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", String.class, browser.username());
        ProjectId projectId = ProjectId.generate();
        createdProjects.add(projectId);
        // owner_id 要一起给：这一列没有默认值，而"房主必须是成员"那条不变量
        // 也要求下面那行成员关系里就是他
        jdbc.update("INSERT INTO project (id, owner_id, name, repo_path) VALUES (?, ?, ?, ?)",
                projectId.value(), userId, "流测试项目", "D:/repos/" + projectId.value());
        jdbc.update("INSERT INTO project_member (project_id, user_id) VALUES (?, ?)",
                projectId.value(), userId);

        Session session = TestSessions.persist(sessions, worktrees, SessionId.generate(), projectId,
                UserId.of(userId), "D:/ws/" + UUID.randomUUID());
        createdSessions.add(session.id());
        return session;
    }

    /**
     * 从永不结束的流上读一小段。
     *
     * <p>必须有超时：帧没发出来时裸 {@code read} 会一直挂着，表现为整轮测试卡死，
     * 而不是任何断言失败。
     */
    private static String readBounded(InputStream body, int maxBytes) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> read = pool.submit(() -> {
                byte[] buffer = new byte[maxBytes];
                int readBytes = body.read(buffer);
                return readBytes <= 0 ? "" : new String(buffer, 0, readBytes, StandardCharsets.UTF_8);
            });
            return read.get(5, TimeUnit.SECONDS);
        }
    }
}
