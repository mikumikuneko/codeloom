package com.codeloom.app.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户 API Key 的加解密，AES-GCM。
 *
 * <p>必须能还原原文才能拿去发请求，所以是加密而不是哈希（用户密码相反）。
 * GCM 是 AEAD，同时提供机密性与完整性：密文被改一个字节就解不开。
 *
 * <p>主密钥来自 {@code codeloom.secret-key}（base64 的 32 字节），没有默认值，也不自动
 * 生成 —— 自动生成的密钥下次重启就变了，已存的密钥会全部解不开。未配置时只在使用处
 * 报错、不拦启动；配置了但格式或长度不对则在构造时立刻失败。见
 * {@code docs/decisions/architecture/2026-09-28-secret-key-has-no-default.md}。
 */
@Component
public class SecretCipher {

    /** GCM 的推荐 IV 长度。每条密文一个新 IV —— 重用 IV 会直接毁掉 GCM 的安全性。 */
    private static final int IV_BYTES = 12;

    /** 认证标签长度（位）。128 是上限，也是默认。 */
    private static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String SEPARATOR = ":";

    private final SecureRandom random = new SecureRandom();

    /**
     * 主密钥。配置为空时是 {@code null}。
     *
     * <p>{@code final}：它只在构造期被定下来一次，不存在换密钥的路径。
     */
    private final SecretKey masterKey;

    /**
     * 未配置主密钥不拦启动，只是加密用不了；配置了但非法（不是合法 base64、长度不对）
     * 则在构造时立刻失败。
     *
     * @param base64Key base64 编码的 32 字节主密钥；空表示未配置
     * @throws IllegalStateException 配置了但无法解析，或长度不是 32 字节
     */
    public SecretCipher(@Value("${codeloom.secret-key:}") String base64Key) {
        // 用一个局部变量，不落成字段：明文主密钥没有理由跟着单例活到进程结束。
        String configured = base64Key == null ? "" : base64Key.strip();
        this.masterKey = configured.isEmpty() ? null : parse(configured);
    }

    /**
     * 加密。
     *
     * @param plaintext 明文
     * @return {@code base64(IV):base64(密文+认证标签)}。IV 和密文永远成对读写，
     *         拆成两列只会多一个“只更新了其中一个”的机会
     * @throws ResponseStatusException 500：未配置主密钥
     * @throws IllegalStateException   加密算法不可用
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, requireMasterKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder encoder = Base64.getEncoder();
            return encoder.encodeToString(iv) + SEPARATOR + encoder.encodeToString(sealed);
        } catch (GeneralSecurityException e) {
            // 加密失败是我们自己的环境问题（算法不可用之类），不是用户输入的问题
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密。
     *
     * @param sealed {@code base64(IV):base64(密文+认证标签)}
     * @return 明文
     * @throws IllegalStateException   格式不对，缺少分隔符
     * @throws ResponseStatusException 500：密文被改过、主密钥换了，或未配置主密钥 ——
     *                                 它们该给的提示一样：那把密钥需要重新配置
     */
    public String decrypt(String sealed) {
        int separator = sealed.indexOf(SEPARATOR);
        if (separator <= 0) {
            throw new IllegalStateException("密文格式不对（应为 base64(IV):base64(密文)）");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(sealed.substring(0, separator));
            byte[] ciphertext = Base64.getDecoder().decode(sealed.substring(separator + 1));

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, requireMasterKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw unavailable(
                    "解不开这条密文：要么它被改过，要么 codeloom.secret-key 换了。"
                            + "前者说明数据有问题，后者说明那把密钥需要重新配置一次", e);
        }
    }

    /** 真正用到主密钥时才检查“配了没有”，见构造器注释。 */
    private SecretKey requireMasterKey() {
        if (masterKey == null) {
            throw unavailable(
                    "codeloom.secret-key 没有配置，所以存不了你的 API Key。"
                            + "它是保护所有人密钥的主密钥，刻意不给默认值也不自动生成 ——"
                            + "自动生成的那个下次重启就变了，已经存进去的密钥会全部解不开。"
                            + "生成一个（openssl rand -base64 32），设进环境变量 CODELOOM_SECRET_KEY，"
                            + "或者写进 application-local.yml（那个文件不进版本库）。", null);
        }
        return masterKey;
    }

    /**
     * 把“服务端自己没配好”以 {@link ResponseStatusException} 抛出，而不是
     * {@code IllegalStateException}。
     *
     * <p>{@code ApiExceptionHandler} 刻意不把 {@code IllegalStateException} 的消息带到
     * 界面上，加上 Boot 默认的 {@code include-message=never}，“少配一个密钥”和“服务器崩了”
     * 会看起来一样。{@code ResponseStatusException} 在本项目里的含义是“这句话要给调用方看”。
     * 文案不要写 markdown 记号，界面不解析它。
     */
    private static ResponseStatusException unavailable(String reason, Throwable cause) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, reason, cause);
    }

    private static SecretKey parse(String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("codeloom.secret-key 不是合法的 base64", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "codeloom.secret-key 必须是 32 字节（AES-256），收到 " + keyBytes.length
                            + " 字节。用 openssl rand -base64 32 生成。");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
