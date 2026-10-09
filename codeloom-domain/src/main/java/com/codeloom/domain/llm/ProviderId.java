package com.codeloom.domain.llm;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 用户用的是**哪一家**模型服务 —— 身份，不是地址。
 *
 * <h2>为什么身份是 id 而不是地址</h2>
 * 密钥按"哪一家"存（见 {@link com.codeloom.domain.port.ApiKeyStore}），会话也记"哪一家"。
 * 而地址是**这一家的一个属性**，住在 {@link Providers} 那张表里 —— 于是同一个服务只可能
 * 有一种地址，"同一个服务的两种写法"这件事从根上不存在。
 *
 * <h2>为什么只校验形状，不校验"必须在预设表里"</h2>
 * 因为**自定义 provider** 迟早要来（用户填自己的地址）：那时 id 由界面生成，不在预设表里，
 * 而这个类型不该拦它。是不是我们认识的一家，由**取地址**那一步回答（见 {@link Providers#find}）
 * —— 认不出来就没有地址可用，那时才报错，报的也正是那件事本身。
 *
 * @param value 形如 {@code deepseek} 的小写短标识
 */
public record ProviderId(String value) {

    /** 允许的写法：小写字母开头，后面是字母/数字/连字符。自定义 provider 的 id 也走这一条。 */
    private static final Pattern SHAPE = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public ProviderId {
        Objects.requireNonNull(value, "value");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "provider 只能是小写字母开头的短标识（字母/数字/连字符，如 deepseek），"
                            + "收到「" + value + "」");
        }
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }
}
