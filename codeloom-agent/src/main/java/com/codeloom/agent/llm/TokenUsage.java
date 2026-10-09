package com.codeloom.agent.llm;

/**
 * 一次调用的 token 用量。
 *
 * <p>字段名是**归一化**过的，不是某家的原样：DeepSeek 的
 * {@code prompt_cache_hit_tokens} 与 OpenAI 的 {@code prompt_tokens_details.cached_tokens}
 * 都映射到 {@link #cachedInputTokens}。这样成本统计的代码不用关心 provider。
 *
 * @param cachedInputTokens 命中缓存的那部分输入。没有缓存机制的 provider 恒为 0
 * @param reasoningTokens   输出里**花在思考上的**那部分。它是 {@link #outputTokens}
 *                          的细分，不是并列的一项 —— 两者的关系就像
 *                          {@code cachedInputTokens} 和 {@code inputTokens}。
 *                          provider 不报这个数时恒为 0
 */
public record TokenUsage(int inputTokens, int outputTokens, int totalTokens,
                         int cachedInputTokens, int reasoningTokens) {

    /** 服务商没返回用量时的占位。 */
    public static final TokenUsage UNKNOWN = new TokenUsage(0, 0, 0, 0, 0);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0
                || cachedInputTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("token 用量不能为负");
        }
    }

    public boolean isKnown() {
        return totalTokens > 0;
    }

    /**
     * 累加。一轮 agent 会调用模型多次（每次工具往返一次），
     * 预算判定要看的是它们的**总和**，不是最后一次。
     */
    public TokenUsage plus(TokenUsage other) {
        if (other == null || !other.isKnown()) {
            return this;
        }
        if (!isKnown()) {
            return other;
        }
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                totalTokens + other.totalTokens,
                cachedInputTokens + other.cachedInputTokens,
                reasoningTokens + other.reasoningTokens);
    }
}
