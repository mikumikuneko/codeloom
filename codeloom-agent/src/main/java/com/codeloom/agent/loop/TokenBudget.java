package com.codeloom.agent.loop;

import com.codeloom.agent.llm.TokenUsage;

/**
 * 单轮 token 熔断：超限时由循环注入收尾指令让模型自己收束，而不是掐断连接。
 *
 * <p>只有单轮这一层：BYOK 下费用属于用户，不存在平台累计限额；本层管的是单轮自旋。
 * 单会话与当日预算曾存在，但其输入恒为 {@code TokenUsage.UNKNOWN}，比较恒为假，已删除。
 *
 * @param perTurnLimit 单轮上限
 */
public record TokenBudget(int perTurnLimit) {

    /** 默认单轮 200k token。 */
    public static final TokenBudget DEFAULT = new TokenBudget(200_000);

    public TokenBudget {
        if (perTurnLimit <= 0) {
            throw new IllegalArgumentException("单轮预算必须是正数，收到 " + perTurnLimit);
        }
    }

    /**
     * 本轮是否该收尾。
     *
     * @param turnUsage 本轮累计用量
     * @return 达到上限时为 true
     */
    public boolean shouldWrapUp(TokenUsage turnUsage) {
        return turnUsage.totalTokens() >= perTurnLimit;
    }

    /** 收尾指令；注入给模型而不是掐断连接。 */
    public static final String WRAP_UP_INSTRUCTION =
            "【系统提示】本轮的 token 预算即将用尽。请立刻停止调用工具，"
                    + "用几句话总结你已经完成了什么、还有什么没做、以及下一步建议。"
                    + "不要开始任何新的修改。";
}
