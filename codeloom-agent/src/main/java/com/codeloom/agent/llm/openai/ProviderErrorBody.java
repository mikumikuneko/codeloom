package com.codeloom.agent.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 从 OpenAI 兼容格式的错误体里取出那句说明。
 *
 * <h2>为什么它单独一个类</h2>
 * 模型调用和联网工具（Search / Fetch）打的是**同一家、同一套错误格式**。
 * 两处各写一份的结果不是"重复几行"，而是**慢慢分叉**：一处空体返回空串、
 * 另一处返回"（服务端未返回错误说明）"；一处截断加省略号、另一处不加 ——
 * 同一个服务端故障，模型和用户看到的说法不一样。
 *
 * <p>"服务端什么都没说"要不要另外写一句话，由调用方决定：模型调用那边必须写
 * （否则报错里少一截），联网工具那边不写（外面已经有一句"搜索失败（原因）"）。
 */
public final class ProviderErrorBody {

    /** 原样带出去的上限：错误体有可能是一整页 HTML。 */
    public static final int MAX_CHARS = 4_000;

    private ProviderErrorBody() {}

    /**
     * {@code {"error":{"message":…}}} 里的那一句；取不到就原样截一段。
     *
     * <p>**空体返回空串**，理由见类注释。
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
