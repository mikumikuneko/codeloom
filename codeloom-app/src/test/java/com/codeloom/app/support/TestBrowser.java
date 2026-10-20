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
 * 带 cookie 罐子的"浏览器"，是真 HTTP 测试里一个用户的替身。
 *
 * <p>每个实例一个 cookie 罐子：本项目的会话身份就是 cookie（不用 Bearer，理由见
 * {@code SecurityConfig}），所以"两个用户"等于"两个罐子"。同一实例的请求自动携带同一会话，
 * 与真实浏览器一致，测试无需手动搬 cookie。
 *
 * <p>不做 JSON 解析：各测试类有自己的 {@code ObjectMapper}，这里只负责发送请求并原样
 * 返回响应，因此不必对响应体形状做任何假设。
 */
public final class TestBrowser {

    /**
     * 测试账号的统一密码。集中一处是因为这里从不验证密码规则，
     * 各测试各写字面量只会让规则变更时多改几处。
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
     * 当前会话 cookie 的原始形式（{@code SESSION=…}），供无法自动携带 cookie 的地方使用，
     * 例如 WebSocket 握手：JDK 的 WebSocket 客户端不共享 {@link CookieManager}。
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
     * 注册账号并让浏览器进入已登录状态（注册响应的会话 cookie 进入罐子）。
     *
     * <p>这里自己断言注册成功：调用方紧接着就会用该账号发其他请求，注册失败却继续，
     * 后续失败会指向无关位置。
     *
     * <p>返回原始响应，供需要检查响应的测试使用（例如 set-cookie 属性、确认没有密码哈希）；
     * 不需要时忽略即可。
     *
     * @param username 账号名；调用方负责保证不重复并登记清理
     */
    public HttpResponse<String> register(String username) throws Exception {
        return register(username, PASSWORD);
    }

    /**
     * 用指定密码注册，只有验证密码行为的测试需要；其余使用上面的重载，避免密码字面量散落。
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
     * 用已有账号登录，成功后浏览器携带该会话。
     *
     * <p>刻意不断言状态码：登录的失败也是待验证行为（密码错误必须 401 且不留已认证会话），
     * 由调用方自行检查结果。
     */
    public HttpResponse<String> login(String username, String password) throws Exception {
        return post("/api/auth/login", loginBody(username, password));
    }

    /**
     * 发送 SSE 请求并以流形式返回响应体：响应头一到就返回，不等待永不结束的流。
     * 真 HTTP 测试用它验证连接本身，例如 {@code Content-Type} 是否为
     * {@code text/event-stream}。
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
     * 不会与其他测试冲突的账号名。用随机值而非递增序号：测试共享真数据库，
     * 重名会让两次运行互相干扰，失败看起来像业务逻辑错误。
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
