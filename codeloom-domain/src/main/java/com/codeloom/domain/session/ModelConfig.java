package com.codeloom.domain.session;

import com.codeloom.domain.llm.ProviderId;

import java.util.Objects;

/**
 * 一条会话用的模型配置。用户可自由选择自己喜好的模型，这是配置文件。
 *
 * <h2>这里刻意没有 API Key，且永远不该有</h2>
 * 本对象挂在 {@link Session} 上，而 Session 会被序列化、被日志打印、被 {@code toString()}
 * 带出去。密钥一旦进来，就会跟着这些路径泄漏到某个日志文件或某条历史事件里。
 *
 * <p>密钥单独存放（AES-GCM 加密落库），按 {@code (userId, provider)} 关联 ——
 * **一家一把**，因为每家模型服务的密钥是各自独立的。它只在真正发起 HTTP 请求
 * 的那一刻取出并注入请求头，不进入任何领域对象。
 *
 * <h2>为什么记的是"哪一家"而不是地址</h2>
 * 因为地址是**这一家的属性**（见 {@code Providers}）。会话记 {@link ProviderId}，
 * 于是"这一家的请求打到哪儿"只有一个答案，改地址也不会让任何东西对不上 ——
 * 而会话和密钥认的是同一个 id，天然就是同一家。
 *
 * <h2>为什么没有采样温度</h2>
 * 请求体里**不发这个参数**，服务商的默认值说了算。发了就得挑一个值，而挑值没有依据 ——
 * 少一个"看起来能调、其实没人调"的旋钮，也少一个要跟着 provider 变的参数。
 *
 * <h2>为什么也没有单次输出上限</h2>
 * 它和温度是同一类东西（建会话请求里有个可选项、界面上没有、永远是同一个值），
 * 但它**不是被删掉，而是搬了家**：它是**模型的属性**而不是会话的属性 ——
 * 不同模型的上限差着数量级，而我们那张能力表本来就在管模型的事。
 * 见 {@code ModelCapabilities.maxOutputTokens}。
 *
 * @param provider     用的是哪一家，如 {@code deepseek}
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
     * 换一段系统提示词（别的原样）。
     *
     * <p>给"建会话时把平台那一段写进去"用的。做成方法而不是让调用方重新 {@code new} 一个：
     * 那个调用方得把字段一个不差地抄一遍，而抄漏一个不会有任何报错。
     */
    public ModelConfig withSystemPrompt(String prompt) {
        return new ModelConfig(provider, modelId, prompt);
    }
}
