package com.codeloom.domain.event;

/**
 * 一次收尾时结算的用量；审批挂起会先收尾一次，批准续跑完成后再记一条。
 *
 * <p>按一次执行而非按次模型调用记：一次执行里可能调用很多次，逐次落库会让本就在膨胀的
 * {@code event} 表再涨，而中间数字没人看；这次执行的总量已由 {@code TokenUsage.plus} 累加好。
 *
 * <p>不进上下文：只有正常收尾的轮次才写它（失败、取消的那一轮没有），进去会让消息前缀随
 * 收尾变化、prompt 缓存失效；投影明确不选它，但仍穷尽列出该类型。
 *
 * <p>没有"缓存写入量"字段：{@code TokenUsage} 把缓存归一化成一个命中数，而 Anthropic 那类
 * provider 的缓存写入单独加价；归一化对多 provider 是对的，真接上时再加字段，不摆一个恒为
 * 0 的。
 *
 * @param inputTokens       输入总量，含命中缓存的部分（与 {@code TokenUsage} 同口径；
 *                          provider 报的 {@code prompt_tokens} 本来就是总数）
 * @param outputTokens      输出
 * @param reasoningTokens   输出里花在思考上的部分，是 {@code outputTokens} 的细分而非并列项，
 *                          和 {@code cachedInputTokens} 与 {@code inputTokens} 的关系一样；
 *                          provider 不报时是 0
 * @param cachedInputTokens 输入里命中缓存的部分。单价低得多，单独记才能算出省了多少
 * @param contextTokens     这一轮收尾时上下文的大小：最后一次模型调用由服务商数出的输入 token
 *                          数，不是前面几个数的加减。{@code null} = 没有读数（这一轮没调成
 *                          模型，或服务商没报用量）；不是 0，因为上下文为 0 不可能发生
 * @param contextWindow     当时的模型窗口。和 {@code model} 一起记，因为换模型会改变窗口，
 *                          旧轮次的分母应是旧的那个；没有读数时同样是 {@code null}
 * @param model             服务商回报的模型名，可能和请求时写的不同（别名、路由）
 */
public record TurnTokensUsed(int inputTokens,
                             int outputTokens,
                             int reasoningTokens,
                             int cachedInputTokens,
                             Integer contextTokens,
                             Integer contextWindow,
                             String model) implements PersistentEvent {

    public TurnTokensUsed {
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("用量不能为负，收到：" + inputTokens + "/" + outputTokens
                    + "/" + reasoningTokens + "/" + cachedInputTokens);
        }
        if (contextTokens != null && contextTokens < 0 || contextWindow != null && contextWindow < 0) {
            throw new IllegalArgumentException("上下文读数不能为负，收到："
                    + contextTokens + "/" + contextWindow);
        }
    }

    /**
     * 这次读数能不能拿来画：上下文读数和窗口都非空时为 true。缺一个，那个环要么没分子、要么
     * 没分母。"有没有"由 {@code null} 回答，见
     * {@code docs/decisions/architecture/2026-10-04-nullable-only-when-zero-is-a-lie}。
     *
     * @return 上下文读数和窗口都非空时为 true
     */
    public boolean hasContext() {
        return contextTokens != null && contextWindow != null;
    }

    /**
     * 计费口径上的总量。缓存命中的那部分也在内，只是单价低。
     *
     * @return 输入与输出之和，未扣除缓存命中
     */
    public long totalTokens() {
        return (long) inputTokens + outputTokens;
    }
}
