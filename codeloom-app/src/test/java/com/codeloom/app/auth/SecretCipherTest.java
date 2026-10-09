package com.codeloom.app.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加解密本身。**不连任何东西** —— 它是纯计算，就该能这样测。
 */
class SecretCipherTest {

    private static final String KEY = keyOf("codeloom-test-master-key");
    private static final String API_KEY = "sk-example-key-not-a-real-credential";

    /**
     * 从一段可读的种子凑出 32 字节再 base64。
     *
     * <p>不写字面量：手数长度会错（曾写成 33 字节，被生产代码的"必须是 32 字节"拦下）。
     * 这里用补齐的办法，让它从构造上就不可能有第二种长度。
     */
    private static String keyOf(String seed) {
        byte[] raw = seed.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[32];
        System.arraycopy(raw, 0, bytes, 0, Math.min(raw.length, bytes.length));
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    @DisplayName("往返：加完再解，拿回原文")
    void roundTrips() {
        SecretCipher cipher = new SecretCipher(KEY);

        assertThat(cipher.decrypt(cipher.encrypt(API_KEY))).isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("密文里看不到原文，一字节都看不到")
    void ciphertextDoesNotLeakThePlaintext() {
        SecretCipher cipher = new SecretCipher(KEY);

        String sealed = cipher.encrypt(API_KEY);

        assertThat(sealed).doesNotContain(API_KEY);
        // 明文里的片段也搜不到。只试整串是不够的 —— 挑一段长的来试
        assertThat(sealed).doesNotContain(API_KEY.substring(3, 20));
    }

    @Test
    @DisplayName("同样的明文加两次得到不同的密文 —— IV 每次都是新的")
    void sameInputProducesDifferentCiphertext() {
        // 这条不是形式主义：GCM 下**重用 IV 会直接毁掉安全性**，
        // 而"每次都换一个"正是靠这一段代码保证的
        SecretCipher cipher = new SecretCipher(KEY);

        assertThat(cipher.encrypt(API_KEY)).isNotEqualTo(cipher.encrypt(API_KEY));
    }

    @Test
    @DisplayName("换了主密钥就解不开 —— 而且错误信息告诉人该怎么办")
    void aDifferentMasterKeyCannotDecrypt() {
        String sealed = new SecretCipher(KEY).encrypt(API_KEY);
        SecretCipher other = new SecretCipher(otherKey());

        assertThatThrownBy(() -> other.decrypt(sealed))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("secret-key");
    }

    @Test
    @DisplayName("密文被改过一个字节就解不开（GCM 是 AEAD，不只是加密）")
    void tamperedCiphertextIsRejected() {
        SecretCipher cipher = new SecretCipher(KEY);
        String sealed = cipher.encrypt(API_KEY);
        // 把密文最后一位改掉，再解
        char last = sealed.charAt(sealed.length() - 1);
        String tampered = sealed.substring(0, sealed.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("没配主密钥：不拦启动，但用到时报错 —— 而且那句话**能到界面上**")
    void missingMasterKeyFailsWithInstructions() {
        // 构造不抛 —— 不碰密钥的部署（用开发 key 跑演示、跑不涉及密钥的测试）不该被卡住
        SecretCipher cipher = new SecretCipher("");

        // ★ 异常**类型**在这里是语义的一部分，不只是写法：
        //   `IllegalStateException` 会被 ApiExceptionHandler 放过，加上 Boot 默认的
        //   include-message=never，界面上就只剩一句 Internal Server Error —— 开发时
        //   真发生过：加供应商报 500，界面上什么都没有，翻服务端日志才看见原因。
        //   所以这几处抛的是 `ResponseStatusException`，项目的约定是"这个 message 要给调用方看"。
        assertThatThrownBy(() -> cipher.encrypt(API_KEY))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR))
                // 错误信息要能直接照着做，而不是只说"配错了"
                .hasMessageContaining("openssl rand -base64 32")
                // 而且要说清"为什么不给你自动生成一个"
                .hasMessageContaining("下次重启就变了")
                // 界面不解析 markdown，所以文案里不能出现记号
                .hasMessageNotContaining("**");
    }

    @Test
    @DisplayName("配了但是错的：**启动就炸** —— 那种错当场发现最省事")
    void malformedMasterKeyFailsFast() {
        assertThatThrownBy(() -> new SecretCipher("这不是 base64"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");

        assertThatThrownBy(() -> new SecretCipher(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    private static String otherKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
