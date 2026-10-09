package com.codeloom.app.support;

import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一个带 cookie 罐子的"浏览器" —— 真 HTTP 测试里「一个用户」的替身。
 *
 * <h2>为什么要有这个类</h2>
 * 它管的这几样（端口怎么拼、cookie 怎么存、注册的请求体长什么样）在每个测试里各抄一遍
 * 的话，就是"改一处要记得改几处"。
 *
 * <h2>为什么每个实例一个 cookie 罐子</h2>
 * 因为**会话身份就是 cookie**（这个项目不用 Bearer，理由见 {@code SecurityConfig}），
 * 所以"两个用户"和"两个 cookie 罐子"是同一件事。一个 {@code TestBrowser}
 * 有一个罐子，于是它的两次请求之间自动带着同一个会话 —— 这正是真浏览器的行为，
 * 而不需要测试里手动搬 cookie。
 *
 * <h2>为什么它不做 JSON 解析</h2>
 * 各测试类有自己的 {@code ObjectMapper}（配置未必相同），而这里只负责"发请求、
 * 把响应原样拿回来"。要断言内容就由调用方去解析 —— 这样这个类不需要对响应体
 * 的形状有任何假设。
 */
public final class TestBrowser {

    /**
     * 测试账号统一用这个密码。
     *
     * <p>放这里而不是让每个调用方传：这里要验的从来不是密码规则，
     * 而"每个测试各写一个密码字面量"只会让改密码规则时多几处要改。
     */
    public static final String PASSWORD = "pwd12345";

    private final CookieManager cookieJar = new CookieManager();

    private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(cookieJar).build();

    private final String origin;

    private String username;

    private TestBrowser(String origin) {
        this.origin = origin;
    }

    /**
     * @param port 被测应用实际监听的端口（{@code @LocalServerPort} 给的那个）
     */
    public static TestBrowser at(int port) {
        return new TestBrowser("http://localhost:" + port);
    }

    /** 这个浏览器当前登录的账号名；没注册过就是 null。 */
    public String username() {
        return username;
    }

    /**
     * 当前会话 cookie 的原始形式（{@code SESSION=…}）。
     *
     * <p>给**没法自动带 cookie** 的地方用 —— 目前只有 WebSocket 握手：
     * JDK 的 WebSocket 客户端不共享 {@link CookieManager}，得自己塞请求头。
     *
     * @throws AssertionError 这个浏览器还没有会话（没注册也没登录过）
     */
    public String sessionCookie() {
        return cookieJar.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().contains("SESSION"))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("这个浏览器还没有会话 cookie（先注册或登录）"));
    }

    /**
     * 注册一个账号，并让这个浏览器处于已登录状态（注册响应里的会话 cookie 会进罐子）。
     *
     * <p>注册成功是**这里自己断言的**：每一个调用方都紧接着拿这个账号去发别的请求，
     * 注册失败却继续往下走的话，后面的失败会指向一个完全无关的地方。
     *
     * <p>返回原始响应，因为有的测试要检查响应本身（比如 set-cookie 上的属性、
     * 或者确认响应体里没夹带密码哈希）。不需要的话忽略返回值即可。
     *
     * @param username 账号名。调用方负责保证它在库里不重复、并登记进清理列表
     */
    public HttpResponse<String> register(String username) throws Exception {
        return register(username, PASSWORD);
    }

    /**
     * 用指定密码注册。只有"要验密码对不对"的测试需要它 —— 其余一律走上面那个重载，
     * 免得每个测试各写一个密码字面量。
     */
    public HttpResponse<String> register(String username, String password) throws Exception {
        HttpResponse<String> response =
                post("/api/auth/register", registerBody(username, password, username));
        assertThat(response.statusCode())
                .as("注册 %s 应该成功，实际 %d：%s", username, response.statusCode(), response.body())
                .isEqualTo(201);
        this.username = username;
        return response;
    }

    /**
     * 用已有账号登录，成功后这个浏览器就带着那个会话了。
     *
     * <p>**刻意不在这里断言状态码**：登录是少数几个"失败也是要验的行为"的地方
     * （密码错必须 401、而且不能留下已认证的会话），调用方得自己看结果。
     */
    public HttpResponse<String> login(String username, String password) throws Exception {
        return post("/api/auth/login", loginBody(username, password));
    }

    /**
     * 发一个 SSE 请求并把响应体当流拿回来 —— 响应头一到就返回，不会等那个永不结束的流。
     *
     * <p>只有真 HTTP 测试才需要它，而"连上了"这件事正是要验的：
     * {@code Content-Type} 是不是 {@code text/event-stream}。
     */
    public HttpResponse<InputStream> getStreaming(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    public HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public HttpResponse<String> post(String path, String jsonBody) throws Exception {
        return send(withJson(HttpRequest.newBuilder(uri(path))).POST(
                HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)));
    }

    public HttpResponse<String> put(String path, String jsonBody) throws Exception {
        return send(withJson(HttpRequest.newBuilder(uri(path))).PUT(
                HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)));
    }

    public HttpResponse<String> delete(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    /** 注册请求体。写在这里，接口加字段时只有这一处要改。 */
    public static String registerBody(String username, String password, String displayName) {
        return """
                {"username":"%s","password":"%s","displayName":"%s"}
                """.formatted(username, password, displayName);
    }

    public static String loginBody(String username, String password) {
        return """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);
    }

    /**
     * 一个不会和别的测试撞上的账号名。
     *
     * <p>随机而不是递增序号：测试之间共享一个真数据库，名字撞了会让两次运行互相干扰，
     * 而那种红看起来像是业务逻辑错了。
     */
    public static String newUsername() {
        return "tester-" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------

    private static HttpRequest.Builder withJson(HttpRequest.Builder builder) {
        return builder.header("Content-Type", "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create(origin + path);
    }
}
