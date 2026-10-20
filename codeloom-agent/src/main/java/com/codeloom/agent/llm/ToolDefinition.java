package com.codeloom.agent.llm;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 暴露给模型的工具定义。
 *
 * @param name                 工具名
 * @param description          给模型看的说明
 * @param parametersJsonSchema 参数的 JSON Schema 文本
 */
public record ToolDefinition(String name, String description, String parametersJsonSchema) {

    /** 工具名只允许字母、数字、下划线和连字符，最多 64 字符；不合法应在注册时暴露。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJsonSchema, "parametersJsonSchema");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "工具名只允许 [a-zA-Z0-9_-] 且不超过 64 字符，收到: " + name);
        }
    }
}
