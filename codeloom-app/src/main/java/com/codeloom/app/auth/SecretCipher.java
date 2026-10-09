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
 * 用户密钥的加解密。**AES-GCM**。
 *
 * <h2>为什么是加密而不是哈希</h2>
 * 因为我们要把原文**还原出来**才能拿去发请求 —— 而哈希是单向的。
 * 这一点和用户密码正相反（那个只需要"验一验对不对"，所以哈希就够了）。
 * 两种凭据、两种处理，不该混为一谈。
 *
 * <h2>选 GCM 而不是 CBC + 另一个哈希</h2>
 * GCM 是 AEAD：它同时给出机密性和完整性，密文被改一个字节就解不开。
 * 用 CBC 的话，得自己再拼一个 HMAC 上去，而那意味着**多一样要同步的东西** ——
 * 而"加密了但忘了校验"是这类代码里最经典的一种破法。
 *
 * <h2>主密钥从哪来，以及为什么不给默认值</h2>
 * {@code codeloom.secret-key}（base64 的 32 字节）。**没有默认值，而且刻意不自动生成**：
 * 自动生成的那个密钥会在下次重启时变成另一个，于是所有已经存进去的密钥
 * **一夜之间全都解不开** —— 那是比"启动失败"严重得多的故障。
 * 没配就报错，报错里告诉你怎么生成一个：
 *
 * <pre>openssl rand -base64 32</pre>
 *
 * <p>它保护的是**所有人**的密钥，所以它的保管级别要高于它保护的东西：
 * 生产上该放密钥管理服务或环境变量，绝不在版本库里。
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
     * 主密钥。配置为空时是 {@code null} —— 见构造器里那两条时机规则。
     *
     * <p>{@code final} 而不是可变字段：它只在构造期被定下来一次，之后没有任何
     * 换密钥的路径。写成可变的会让人以为存在这条路径。
     */
    private final SecretKey masterKey;

    /**
     * <h2>没配主密钥时**不拦启动**</h2>
     * 配置是空的就只是"不加密用不了"，而不是"这个应用起不来"。理由很实际：
     * 用开发用 key 跑演示、或者跑那些根本不碰密钥的测试，都不该被这个配置卡住。
     *
     * <p>但**配了却是错的**（不是合法 base64、长度不对）会立刻炸 —— 那种错当场发现最省事，
     * 拖到某次存密钥时才炸只会让人以为是业务问题。
     */
    public SecretCipher(@Value("${codeloom.secret-key:}") String base64Key) {
        // 刻意只用一个局部变量，不落成字段：它是**明文主密钥**的副本，
        // 没有任何理由让它跟着这个单例活到进程结束 —— 那比它保护的密文活得还久，
        // 而"明文只在两端各出现一瞬间"是这个类唯一的运行纪律
        String configured = base64Key == null ? "" : base64Key.strip();
        this.masterKey = configured.isEmpty() ? null : parse(configured);
    }

    /**
     * 加密。
     *
     * @return {@code base64(IV):base64(密文+认证标签)} —— 一列装得下，而且 IV 和密文
     *         永远是成对读写的，拆成两列只会多一个"只更新了其中一个"的机会
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
     * @throws IllegalStateException 密文被改过、或者主密钥换了 —— 两种情况都表现为解不开，
     *                               而它们该给的提示是一样的：**那把密钥需要重新配置**
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

    /** 真正用到主密钥时才检查"配了没有" —— 见构造器注释。 */
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
     * 「服务端自己没配好」该以什么形式抛出去。
     *
     * <h2>为什么不是 {@code IllegalStateException}</h2>
     * 因为它<b>到不了界面上</b>：{@code ApiExceptionHandler} 刻意不接
     * {@code IllegalStateException}（那可能是任何一处内部状态坏掉，把 message 漏出去等于
     * 泄露实现细节），而 Boot 默认的 {@code include-message=never} 会让响应只剩一句
     * {@code Internal Server Error}。于是"你少配了一个密钥"和"服务器崩了"在界面上长得一样 ——
     * <b>而前者一句话就能修好</b>。
     *
     * <p>{@code ResponseStatusException} 在这个项目里的含义就是"**这句话是要给调用方看的**"
     * （见 {@code ApiExceptionHandler.onResponseStatus}），而下面这几处的读者确实就是它：
     * 这是个自部署的两人工具，用的人和运维的人是同一个人。
     *
     * <p>文案里**不要写 markdown 记号** —— 它会原样出现在界面上（那里不解析 markdown）。
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
