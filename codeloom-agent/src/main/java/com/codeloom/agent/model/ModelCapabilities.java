package com.codeloom.agent.model;

/**
 * 一个模型的**能力描述**。
 *
 * <h2>这两个字段都是"有代码在读"才留着的</h2>
 * 一个字段**在生产代码里没有任何读点**，比没有它更糟：维护者看到它，会以为改它能改变
 * 循环行为，而实际上什么也不会发生。（是否支持并行工具调用、缓存模式两个字段就是这么
 * 删掉的。）
 *
 * <p>{@code contextWindow} 是压缩阈值要算它，{@code maxOutputTokens} 是**发请求时要填
 * {@code max_tokens}** —— 各自有明确的读点。
 *
 * @param supportsTools   是否支持工具调用
 * @param contextWindow   上下文窗口（token 数）。压缩阈值按它算
 * @param maxOutputTokens 单次回复的**上限**，也就是请求体里那个 {@code max_tokens}。
 *                        它是**模型的属性**而不是使用者的选择，所以挂在能力描述上，
 *                        不由建会话请求填（那样会填一个没有道理的小数 —— DeepSeek 的上限是 38.4 万）
 */
public record ModelCapabilities(boolean supportsTools, int contextWindow, int maxOutputTokens) {

    /**
     * **认不出模型时**按这个窗口算。
     *
     * <p>**刻意取得偏小**：估大了的后果是这一轮直接超限失败，用户丢掉整段对话；
     * 估小了的后果只是多压缩一次、多花一次生成摘要的钱。两者不对称，所以往小的取。
     *
     * <p>注意它是"认不出来"的默认，**不是 DeepSeek 的窗口** —— 那个是 1,000,000，
     * 在 {@link ModelCapabilitiesResolver} 的表里。把它当成所有模型的窗口用过，
     * 代价见那个类的注释。
     */
    public static final int DEFAULT_CONTEXT_WINDOW = 64_000;

    /**
     * **认不出模型时**按这个输出上限发。
     *
     * <p>也取得保守，但理由和窗口那边**不是同一条**：窗口猜大了会静默超限，而输出上限
     * 猜大了是**服务商直接拒**（"max_tokens 超过这个模型的能力"），整轮请求连发都发不出去。
     * 猜小了的代价只是回复被截断 —— 而截断这条路我们本来就有处理（见 {@code AgentTurn}
     * 那条"拆成更小的几步"）。
     *
     * <p>所以就取那个最不容易被拒的值，也是这个项目一直在用的那个。
     */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4_096;

    public ModelCapabilities {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("上下文窗口必须为正数，收到：" + contextWindow);
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("输出上限必须为正数，收到：" + maxOutputTokens);
        }
    }

    /**
     * 未知模型用的默认值：**假定支持**工具调用。
     *
     * <p>这个方向是刻意的：不支持工具调用的模型在这个 agent 里根本没法用
     * （循环的每一步都建立在工具调用上），所以"未知"应当退化到"能用、但可能不灵"，
     * 而不是"直接拒绝一个也许完全正常的模型"。
     */
    public static final ModelCapabilities UNKNOWN =
            new ModelCapabilities(true, DEFAULT_CONTEXT_WINDOW, DEFAULT_MAX_OUTPUT_TOKENS);
}
