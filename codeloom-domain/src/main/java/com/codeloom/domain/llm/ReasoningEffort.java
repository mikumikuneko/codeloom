package com.codeloom.domain.llm;

import java.util.Locale;
import java.util.Optional;

/**
 * 思考强度，对应 DeepSeek 的 reasoning_effort。
 *
 * <p>{@link #NONE} 关闭思考；其余三档开启并控制强度。空值（没有配置）表示不发送这个
 * 字段，由服务商决定默认值。DeepSeek 的默认是 high。
 *
 * <p>接受服务商的兼容别名：minimal 算 low，medium 和 xhigh 算 high；对外只暴露
 * none/low/high/max 四个规范值，别让界面也去背别名。
 */
public enum ReasoningEffort {

    NONE("none"),
    LOW("low"),
    HIGH("high"),
    MAX("max");

    private final String wireValue;

    ReasoningEffort(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * @return 请求体里写出去的值
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * 解析配置值；null 和空串表示"没有配置"。
     *
     * @param raw 配置里的原文
     * @return 对应档位；没有配置时为空
     * @throws IllegalArgumentException 值不认识
     */
    public static Optional<ReasoningEffort> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "none" -> Optional.of(NONE);
            case "low", "minimal" -> Optional.of(LOW);
            case "high", "medium", "xhigh" -> Optional.of(HIGH);
            case "max" -> Optional.of(MAX);
            default -> throw new IllegalArgumentException(
                    "不认识的思考强度：" + raw + "；可用值为 none、low、high、max");
        };
    }
}
