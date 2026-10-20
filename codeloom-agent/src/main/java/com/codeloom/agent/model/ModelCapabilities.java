package com.codeloom.agent.model;

/**
 * 一个模型的能力描述。只保留生产代码真正读取的字段：上下文窗口供压缩算阈值，
 * 输出上限填入请求的 {@code max_tokens}。
 *
 * @param supportsTools   是否支持工具调用
 * @param contextWindow   上下文窗口（token 数）
 * @param maxOutputTokens 单次输出上限，是模型属性而非用户选择
 */
public record ModelCapabilities(boolean supportsTools, int contextWindow, int maxOutputTokens) {

    /**
     * 认不出模型时的上下文窗口。刻意取小：估大导致超限失败，估小只多压缩一次。
     * 这不是 DeepSeek 的窗口，后者的值在 {@link ModelCapabilitiesResolver} 的表里。
     */
    public static final int DEFAULT_CONTEXT_WINDOW = 64_000;

    /**
     * 认不出模型时的输出上限。猜大可能被服务商直接拒绝，猜小只会截断，而截断已有处理。
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

    /** 未知模型的默认值：假定支持工具调用，而不是拒绝一个可能正常的模型。 */
    public static final ModelCapabilities UNKNOWN =
            new ModelCapabilities(true, DEFAULT_CONTEXT_WINDOW, DEFAULT_MAX_OUTPUT_TOKENS);
}
