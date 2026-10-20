package com.codeloom.domain.session;

import com.codeloom.domain.llm.ProviderId;

import java.util.Objects;

/**
 * 一条会话使用的模型配置，用户可自由选择。
 *
 * <p>没有 API Key，且永远不该有：本对象随 {@link Session} 被序列化、打日志、经
 * {@code toString()} 输出，密钥会泄漏。密钥按 {@code (userId, provider)} 加密存放，只在发起 HTTP 请求时取出，不进入领域对象。
 *
 * <p>记 {@link ProviderId} 而非地址（地址是 {@link com.codeloom.domain.llm.Providers} 的
 * 属性）；不发采样温度，服务商默认值说了算；输出上限在 {@code ModelCapabilities.maxOutputTokens}。
 *
 * @param provider     用的是哪一家，如 {@code deepseek}；见
 *                     {@code docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider.md}
 * @param modelId      模型标识，如 {@code deepseek-chat}
 * @param systemPrompt 会话级系统提示词，为 null 表示不加
 */
public record ModelConfig(ProviderId provider,
                          String modelId,
                          String systemPrompt) {

    public ModelConfig {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelId, "modelId");
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
    }

    /**
     * 换一段系统提示词，别的原样。给"建会话时写入平台提示词"用；避免调用方逐字段重新
     * 构造成员而抄漏一个。
     *
     * @param prompt 新的系统提示词；null 表示不加
     * @return 换了系统提示词的配置
     */
    public ModelConfig withSystemPrompt(String prompt) {
        return new ModelConfig(provider, modelId, prompt);
    }
}
