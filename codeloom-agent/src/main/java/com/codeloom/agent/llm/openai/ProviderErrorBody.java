package com.codeloom.agent.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 从 OpenAI 兼容格式的错误体里取出说明。模型调用和联网工具使用同一套错误格式，
 * 收在一处避免两边的空体处理、截断和措辞逐渐分叉。
 *
 * <p>空体返回空串；要不要补“服务端未返回说明”由调用方决定。
 */
public final class ProviderErrorBody {

    /** 原样带出去的上限：错误体有可能是一整页 HTML。 */
    public static final int MAX_CHARS = 4_000;

    private ProviderErrorBody() {}

    /**
     * 取 {@code {"error":{"message":…}}} 里的说明；取不到就原样截一段。
     * 空体返回空串。
     *
     * @param body   响应体
     * @param mapper JSON 解析器
     * @return 错误说明；空体时为空串
     */
    public static String message(String body, ObjectMapper mapper) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode error = mapper.readTree(body).path("error");
            if (error.isObject() && error.hasNonNull("message")) {
                return error.get("message").asText();
            }
        } catch (IOException ignored) {
            // 错误响应体未必是 JSON，那就原样返回
        }
        return body.length() <= MAX_CHARS ? body : body.substring(0, MAX_CHARS) + "…";
    }
}
