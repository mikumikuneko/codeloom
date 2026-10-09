package com.codeloom.agent.llm;

import java.util.Objects;

/**
 * 模型请求的一次工具调用。
 *
 * @param id            模型给出的调用 id，用于和工具结果配对
 * @param name          工具名
 * @param argumentsJson **原始 JSON 字符串**，不解析成对象
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
