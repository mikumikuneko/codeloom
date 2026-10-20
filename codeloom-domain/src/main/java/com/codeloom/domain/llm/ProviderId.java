package com.codeloom.domain.llm;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 用户使用的是哪一家模型服务——身份，不是地址。
 *
 * <p>密钥按 provider 存（见 {@link com.codeloom.domain.port.ApiKeyStore}），会话也记
 * provider；地址是这一家的属性，住在 {@link Providers} 表里，因此同一个服务只有一种地址。
 *
 * <p>只校验形状，不要求必须在预设表里：自定义 provider 迟早要来，id 可由界面生成；
 * "认不认识这一家"由取地址那一步回答（见 {@link Providers#find}），取不到就没有地址可用。
 *
 * @param value 形如 {@code deepseek} 的小写短标识；见
 *              {@code docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider.md}
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

    /**
     * 从文本构造：先去掉首尾空白并转小写，再校验形状。
     *
     * @param value provider 标识文本
     * @return provider 标识
     * @throws IllegalArgumentException 形状不合法时
     */
    public static ProviderId of(String value) {
        return new ProviderId(value);
    }
}
