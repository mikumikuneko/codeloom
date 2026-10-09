package com.codeloom.app.auth;

import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.app.support.TestBrowser;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.session.ModelConfig;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BYOK 密钥的真 HTTP 测试。**密钥按"哪一家"各存一把**。
 *
 * <h2>这里最该看的两条</h2>
 * <ol>
 *   <li><b>库里存的是密文</b> —— 别的断言验的是接口行为，只有这条验的是
 *       "这件事本身有没有做对"，而它是那种做错了没有任何症状的错：接口全对、功能全通，
 *       只是所有人的密钥在数据库里躺着明文。
 *   <li><b>配了 A 家的 key 不等于配了 B 家</b> —— 这条盯的是一个安全问题，
 *       不是不方便：只看用户不看"哪一家"的话，一个只配了 DeepSeek key 的人把模型切成 Kimi，
 *       DeepSeek 的凭据就会被发到 moonshot 的服务器上。
 * </ol>
 *
 * <h2>测试里用的密钥都是编的</h2>
 * {@link #DEEPSEEK_KEY} 之类只为了有"一长串"可掩码。**真密钥只在界面上配**，
 * 从不进仓库；这里连一把真密钥都不需要。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isDatabaseReachable")
class ApiKeyApiTest {

    /** 预设里的一家：地址由服务端给，请求里不带。 */
    private static final String DEEPSEEK = "deepseek";
    /** 不被预设认识的一家（自定义 provider）：**必须连地址一起给**。 */
    private static final String MOONSHOT = "moonshot";
    private static final String MOONSHOT_BASE_URL = "https://api.moonshot.cn/v1";

    private static final String DEEPSEEK_KEY = "sk-example-key-not-a-real-credential";
    private static final String MOONSHOT_KEY = "sk-kimi-test-00000000000000000000000fake";

    /**
     * 给测试上下文配一把主密钥。用零字节而不是写一串字面量：要验的是"这套机制在工作"，
     * 而密钥的具体值在这里不重要。
     */
    @DynamicPropertySource
    static void masterKey(DynamicPropertyRegistry registry) {
        registry.add("codeloom.secret-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private UserRepository users;

    @Autowired
    private LlmClientProvider clients;

    private final List<String> createdUsernames = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String username : createdUsernames) {
            jdbc.update("DELETE FROM user_api_key WHERE user_id IN "
                    + "(SELECT id FROM `user` WHERE username = ?)", username);
            jdbc.update("DELETE FROM `user` WHERE username = ?", username);
        }
        createdUsernames.clear();
    }

    // ------------------------------------------------------------------
    // 按"哪一家"各存一把
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【核心】两家各配一把，互不覆盖、各查各的")
    void keysAreScopedToTheirProvider() throws Exception {
        TestBrowser alice = register();
        alice.put("/api/auth/api-key", presetBody(DEEPSEEK, DEEPSEEK_KEY));
        alice.put("/api/auth/api-key", customBody(MOONSHOT, MOONSHOT_BASE_URL, MOONSHOT_KEY));

        // **第二条没有把第一条覆盖掉** —— 只按用户存的话这里只会剩一条
        assertThat(listKeys(alice)).extracting(node -> node.get("provider").asText())
                .containsExactly("deepseek", "moonshot");

        // 各自的掩码来自各自的密钥，没有串。
        // 尾巴**从常量算出来**，不写字面量：写死的话，换一次假密钥就要改一次测试，
        // 而且改漏的那一半会以"期望值不对"的样子出现，看着像功能坏了
        assertThat(listKeys(alice)).anySatisfy(key -> {
            assertThat(key.get("provider").asText()).isEqualTo("deepseek");
            assertThat(key.get("hint").asText()).endsWith(tailOf(DEEPSEEK_KEY));
        }).anySatisfy(key -> {
            assertThat(key.get("provider").asText()).isEqualTo("moonshot");
            assertThat(key.get("hint").asText()).endsWith(tailOf(MOONSHOT_KEY));
        });
    }

    /** 掩码只露头尾，这里要断的是它的尾。 */
    private static String tailOf(String key) {
        return key.substring(key.length() - 4);
    }

    @Test
    @DisplayName("【安全】配了 DeepSeek 的 key，不等于能服务 Kimi 的模型")
    void aKeyForOneProviderDoesNotServeAnother() throws Exception {
        TestBrowser alice = register();
        alice.put("/api/auth/api-key", presetBody(DEEPSEEK, DEEPSEEK_KEY));
        var me = users.findByUsername(alice.username()).orElseThrow();

        // 这条断言盯的**不是不方便，是安全问题**：要是按用户存一把 key，
        // 下面这个 Kimi 模型会被发上 DeepSeek 的密钥 —— 把一家的凭据交给另一家
        assertThat(clients.findClient(me.id(), model(DEEPSEEK))).isPresent();
        assertThat(clients.findClient(me.id(), model(MOONSHOT))).isEmpty();

        // 补上这一家的 key 之后才行
        alice.put("/api/auth/api-key", customBody(MOONSHOT, MOONSHOT_BASE_URL, MOONSHOT_KEY));
        assertThat(clients.findClient(me.id(), model(MOONSHOT))).isPresent();
    }

    @Test
    @DisplayName("【地址不是身份】预设的那几家：请求里的地址不采纳，地址由服务端定")
    void presetProvidersTakeTheirAddressFromTheServer() throws Exception {
        TestBrowser alice = register();
        // 硬塞一个地址进来，想把它指到别处
        alice.put("/api/auth/api-key", customBody(DEEPSEEK, "https://evil.example.com", DEEPSEEK_KEY));

        var me = users.findByUsername(alice.username()).orElseThrow();
        // 客户端建出来了（有 key），但用的是**预设里的地址** —— 从外面看不出来，
        // 所以这条断言的落点在下一条：库里那一列对预设是空的，也就是"地址没有第二处来源"
        assertThat(clients.findClient(me.id(), model(DEEPSEEK))).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT base_url FROM user_api_key WHERE user_id = ?",
                String.class, me.id().value())).isNull();
    }

    @Test
    @DisplayName("认不出来的一家、又没给地址 → 400，而且说清认识的是哪几家")
    void unknownProviderWithoutAnAddressIsRejected() throws Exception {
        TestBrowser alice = register();

        HttpResponse<String> response = alice.put("/api/auth/api-key",
                presetBody("typo-provider", DEEPSEEK_KEY));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("typo-provider").contains("deepseek");
    }

    @Test
    @DisplayName("删掉一家不影响另一家")
    void removingOneProviderKeepsTheOther() throws Exception {
        TestBrowser alice = register();
        alice.put("/api/auth/api-key", presetBody(DEEPSEEK, DEEPSEEK_KEY));
        alice.put("/api/auth/api-key", customBody(MOONSHOT, MOONSHOT_BASE_URL, MOONSHOT_KEY));

        assertThat(alice.delete("/api/auth/api-key?provider=deepseek").statusCode())
                .isEqualTo(204);

        assertThat(listKeys(alice)).singleElement()
                .satisfies(key -> assertThat(key.get("provider").asText()).isEqualTo("moonshot"));
    }

    @Test
    @DisplayName("预设清单里**没有请求地址** —— 前端拿到它也没有用处")
    void providerListDoesNotLeakTheBaseUrl() throws Exception {
        TestBrowser alice = register();

        HttpResponse<String> response = alice.get("/api/providers");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("deepseek").contains("DeepSeek")
                .contains("https://platform.deepseek.com");   // 官网是给人点过去的
        assertThat(response.body()).doesNotContain("api.deepseek.com");   // 请求地址不出现
    }

    // ------------------------------------------------------------------
    // 安全约束
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【安全约束】库里存的是密文 —— 接口全对、功能全通的那种错，只有这条能抓住")
    void theStoredSecretIsCiphertext() throws Exception {
        TestBrowser alice = register();
        alice.put("/api/auth/api-key", presetBody(DEEPSEEK, DEEPSEEK_KEY));

        String stored = jdbc.queryForObject(
                "SELECT sealed_key FROM user_api_key WHERE user_id = "
                        + "(SELECT id FROM `user` WHERE username = ?)",
                String.class, alice.username());

        assertThat(stored).isNotBlank().isNotEqualTo(DEEPSEEK_KEY);
        assertThat(stored).doesNotContain(DEEPSEEK_KEY);
        // 连明文里的片段都不该出现 —— 只试整串是不够的
        assertThat(stored).doesNotContain(DEEPSEEK_KEY.substring(3, 20));
    }

    @Test
    @DisplayName("读回来的是掩码，不是原文 —— 而且响应里任何地方都不该有原文")
    void readingBackGivesAHintNotTheKey() throws Exception {
        TestBrowser alice = register();
        alice.put("/api/auth/api-key", presetBody(DEEPSEEK, DEEPSEEK_KEY));

        HttpResponse<String> response = alice.get("/api/auth/api-key");

        assertThat(response.statusCode()).isEqualTo(200);
        // 头八尾四、中间固定五个星：够人认出"那是我换上去的那把"，不足以还原。
        // 期望值**从常量算出来**，不抄一个示例字面量 —— 换了假密钥、或者服务端改了位数，
        // 抄来的那份就会以"功能坏了"的样子报错，而实际只是抄的那份过期了
        String hint = DEEPSEEK_KEY.substring(0, 8) + "*****" + tailOf(DEEPSEEK_KEY);
        assertThat(response.body()).contains(hint);
        // **整串不能出现在响应里的任何地方** —— 把密钥回传的收益是零，
        // 风险是每响应一次多一次泄漏机会
        assertThat(response.body()).doesNotContain(DEEPSEEK_KEY);
    }

    @Test
    @DisplayName("每个人只能看到自己配的")
    void keysAreScopedToTheirOwner() throws Exception {
        TestBrowser alice = register();
        TestBrowser bob = register();
        alice.put("/api/auth/api-key", presetBody(DEEPSEEK, DEEPSEEK_KEY));

        // 接口里没有"按用户查"这回事 —— 拿到的永远是自己的那些。
        // 这不是"权限没做"，是**根本没有那个入口**（见 ApiKeyStore 的类注释）
        assertThat(listKeys(bob)).isEmpty();
    }

    @Test
    @DisplayName("provider 或 key 为空 → 400，各说各的")
    void blankInputsAreRejected() throws Exception {
        TestBrowser alice = register();

        // **状态码也要断言**：只查文案的话，一个 500（校验抛的异常被兜成服务端错误）
        // 只要消息里带上字段名就能通过 —— 而"是客户端传错了"正是这条测试要说的事
        HttpResponse<String> noProvider = alice.put("/api/auth/api-key", presetBody("", DEEPSEEK_KEY));
        assertThat(noProvider.statusCode()).isEqualTo(400);
        assertThat(noProvider.body()).contains("provider");

        HttpResponse<String> noKey = alice.put("/api/auth/api-key", presetBody(DEEPSEEK, "  "));
        assertThat(noKey.statusCode()).isEqualTo(400);
        assertThat(noKey.body()).contains("apiKey");
    }

    @Test
    @DisplayName("未登录 → 401")
    void requiresLogin() throws Exception {
        TestBrowser anonymous = TestBrowser.at(port);

        assertThat(anonymous.get("/api/auth/api-key").statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------

    private static ModelConfig model(String provider) {
        return new ModelConfig(ProviderId.of(provider), "some-model", null);
    }

    /** 预设的那几家：只给 id 和 key。 */
    private String presetBody(String provider, String apiKey) throws Exception {
        return json.writeValueAsString(Map.of("provider", provider, "apiKey", apiKey));
    }

    /** 自定义 provider：地址得一起给，那是它唯一的来源。 */
    private String customBody(String provider, String baseUrl, String apiKey) throws Exception {
        return json.writeValueAsString(
                Map.of("provider", provider, "baseUrl", baseUrl, "apiKey", apiKey));
    }

    private TestBrowser register() throws Exception {
        String username = "tester-" + UUID.randomUUID();
        createdUsernames.add(username);
        TestBrowser browser = TestBrowser.at(port);
        browser.register(username);
        return browser;
    }

    /** 这个浏览器配过哪几家（掩码形式）。**没有任何接口会把原文读回来。** */
    private List<JsonNode> listKeys(TestBrowser browser) throws Exception {
        HttpResponse<String> response = browser.get("/api/auth/api-key");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readerForListOf(JsonNode.class).readValue(response.body());
    }
}
