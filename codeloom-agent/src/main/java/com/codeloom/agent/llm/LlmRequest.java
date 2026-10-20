package com.codeloom.agent.llm;

import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用的请求，provider 无关。
 *
 * <p>不含 API Key：密钥由客户端在发请求前现取。也不含采样温度，请求体里不发该参数，
 * 由服务商默认值决定（见 {@code docs/decisions/architecture/2026-10-04-temperature-is-not-sent.md}）。
 *
 * @param model    模型标识
 * @param messages 完整上下文；顺序稳定才能命中服务商的前缀缓存
 * @param tools    暴露给模型的工具；为空表示本次不提供工具
 * @param maxTokens 单次输出上限
 */
public record LlmRequest(String model,
                          List<LlmMessage> messages,
                          List<ToolDefinition> tools,
                          int maxTokens) {

    public LlmRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(messages, "messages");
        tools = tools == null ? List.of() : List.copyOf(tools);
        messages = List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("消息列表不能为空");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须是正数，收到 " + maxTokens);
        }
    }

    /**
     * @return 本次是否提供工具
     */
    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
