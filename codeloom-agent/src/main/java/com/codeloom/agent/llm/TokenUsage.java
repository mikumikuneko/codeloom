package com.codeloom.agent.llm;

/**
 * 一次调用的 token 用量。字段名已归一化，成本统计不依赖具体 provider。
 *
 * @param inputTokens       输入 token
 * @param outputTokens      输出 token
 * @param totalTokens       总 token
 * @param cachedInputTokens 命中缓存的那部分输入；不支持缓存的 provider 恒为 0
 * @param reasoningTokens   输出中花在思考上的部分，是 {@link #outputTokens} 的细分；
 *                          provider 不报时恒为 0
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

    /**
     * @return 服务商是否返回了非零用量
     */
    public boolean isKnown() {
        return totalTokens > 0;
    }

    /**
     * 累加两次调用的用量；未知用量按零处理。预算判定看本轮总和，不是最后一次。
     *
     * @param other 另一次调用的用量
     * @return 相加结果
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
