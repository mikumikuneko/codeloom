package com.codeloom.agent.llm;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 暴露给模型的工具定义。
 *
 * @param parametersJsonSchema JSON Schema 文本
 */
public record ToolDefinition(String name, String description, String parametersJsonSchema) {

    /**
     * 工具名的字符集约束。各家的具体上限不同（常见是 64 或 128），
     * 但**字符集几乎一致**，所以在这里按最紧的共同子集校验 —— 名字不合法是
     * 接入时就该暴露的问题，不该等到第一次调用才 400。
     */
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
