package com.codeloom.app.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个入参 record 都带着明文凭据，而 record 默认的 {@code toString} 会把字段原样打出来。
 *
 * <p>测的不是文案，是"**没有任何一条日志路径能带出凭据**"这条约定 —— 撑着它的就是各自的
 * `toString()` 覆写。有人在日志里打一次请求对象，这条约定就得靠它们接住。
 *
 * <p>三处一起测，是因为它们是同一个病：漏掉任何一处，另外两处的覆盖就是自欺。
 * 理由写在 {@link ApiKeyController.ConfigureRequest#toString()} 上，另两处指过去。
 */
class RequestRedactionTest {

    /** 和 `ApiKeyApiTest` 用同一串：一眼假，形状也不同于真 Key。 */
    private static final String KEY = "sk-example-key-not-a-real-credential";
    private static final String PASSWORD = "example-password-not-a-real-one";

    @Test
    @DisplayName("配置密钥的请求：打印时不带密钥")
    void configureRequestHidesTheKey() {
        assertRedacted(new ApiKeyController.ConfigureRequest("deepseek", null, "我的 key", KEY), KEY);
    }

    @Test
    @DisplayName("注册与登录的请求：打印时不带口令")
    void authRequestsHideThePassword() {
        assertRedacted(new AuthController.RegisterRequest("someone", PASSWORD, "某人"), PASSWORD);
        assertRedacted(new AuthController.LoginRequest("someone", PASSWORD), PASSWORD);
    }

    @Test
    @DisplayName("遮的是打印，不是取值 —— 三处都照常取得到凭据")
    void theCredentialsAreStillReadable() {
        // 遮过头就会把这些请求直接弄坏，而那种坏法要到线上第一次登录才看得出来
        assertThat(new ApiKeyController.ConfigureRequest("deepseek", null, "n", KEY).apiKey())
                .isEqualTo(KEY);
        assertThat(new AuthController.RegisterRequest("u", PASSWORD, "d").password())
                .isEqualTo(PASSWORD);
        assertThat(new AuthController.LoginRequest("u", PASSWORD).password())
                .isEqualTo(PASSWORD);
    }

    private static void assertRedacted(Object request, String credential) {
        assertThat(request.toString())
                .as("%s 的打印里不该出现凭据", request.getClass().getSimpleName())
                .doesNotContain(credential)
                .contains("[已隐去]");
    }
}
