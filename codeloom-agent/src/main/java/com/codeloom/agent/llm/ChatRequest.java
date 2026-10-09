package com.codeloom.agent.llm;

import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用的请求 —— IR 层，provider 无关。
 *
 * <p>注意这里**没有 API Key**。密钥不属于领域对象，它由客户端在发起请求的最后一刻
 * 从密钥库里取（见 {@code OpenAiCompatibleClient} 的构造参数）。
 *
 * <h2>也**没有采样温度**</h2>
 * 请求体里不发这个参数，服务商的默认值说了算。理由见 {@code ModelConfig} 的类注释：
 * 发了就得挑一个值，而挑值没有依据；而"挑一个值"这件事本身也不该由我们替使用者决定。
 *
 * @param model       模型标识
 * @param messages    完整上下文。**顺序有意义**：前缀顺序稳定才能命中模型服务商的前缀缓存
 * @param tools       暴露给模型的工具；为空表示这次不提供工具
 * @param maxTokens   单次输出上限
 */
public record ChatRequest(String model,
                          List<ChatMessage> messages,
                          List<ToolDefinition> tools,
                          int maxTokens) {

    public ChatRequest {
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

    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
