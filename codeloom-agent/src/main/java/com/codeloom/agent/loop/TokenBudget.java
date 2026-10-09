package com.codeloom.agent.loop;

import com.codeloom.agent.llm.TokenUsage;

/**
 * 单轮的 token 熔断。
 *
 * <h2>超限时是"优雅收尾"，不是"硬杀"</h2>
 * 到上限时不能直接把连接掐掉 —— 那会留下半个 diff、半句解释，模型下一轮面对的
 * 是个残局。正确做法是**注入一条指令**（"预算将尽，请总结当前结论"），
 * 让它自己把话说完。所以这个类只负责判断"该收尾了"，怎么收尾由循环决定。
 *
 * <h2>只有单轮这一层，这是有意的</h2>
 * 本项目是 BYOK，用户填自己的 API key，花的是他自己的钱 —— "平台累计超限就拦住用户"
 * 防的那件事在这里没有主体。唯一"用户没操作却在花钱"的场景（崩溃恢复后自动续跑）
 * 也已经**整个删掉**了，不需要预算这一层再兜一道 —— 见 {@code CrashRecovery}。
 *
 * <p>单会话 / 当日那两层曾经存在，已删除，且不打算加回来：它们当时的输入恒为
 * {@code TokenUsage.UNKNOWN}，也就是**比较恒为假** —— 一个看起来能调、调了却什么都不会变的
 * 阈值，比没有它更糟：维护者会去调"日预算"，然后发现没反应，再花半天找原因。
 *
 * <p>留下来这一层管的是**自旋**，和谁付钱无关：一轮里模型可能陷进
 * "读文件→改文件→读文件"的死循环，那是单轮之内就会失控的地方。
 *
 * @param perTurnLimit 单轮上限。agent 自旋时最容易失控的地方
 */
public record TokenBudget(int perTurnLimit) {

    /** 默认：单轮 200k。够一次密集的工具往返，又不至于把一个失控的循环放过夜。 */
    public static final TokenBudget DEFAULT = new TokenBudget(200_000);

    public TokenBudget {
        if (perTurnLimit <= 0) {
            throw new IllegalArgumentException("单轮预算必须是正数，收到 " + perTurnLimit);
        }
    }

    /** 这一轮是不是该收尾了。 */
    public boolean shouldWrapUp(TokenUsage turnUsage) {
        return turnUsage.totalTokens() >= perTurnLimit;
    }

    /**
     * 收尾指令。**注入给模型而不是掐断连接** —— 让它把当前结论说完，
     * 而不是留下一句半截的话和一个写着改了一半的文件。
     */
    public static final String WRAP_UP_INSTRUCTION =
            "【系统提示】本轮的 token 预算即将用尽。请立刻停止调用工具，"
                    + "用几句话总结你已经完成了什么、还有什么没做、以及下一步建议。"
                    + "不要开始任何新的修改。";
}
