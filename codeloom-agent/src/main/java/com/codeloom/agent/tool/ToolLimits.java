package com.codeloom.agent.tool;

/**
 * 工具之间共用的量。只有两个以上工具使用、且必须是同一个数时才放到这里。
 */
public final class ToolLimits {

    /**
     * 单次调用交给模型的输出上限（字符）。这不是每轮总预算；
     * 后者在循环层的 {@code MAX_TOOL_OUTPUT_CHARS_PER_TURN}。
     */
    public static final int MAX_OUTPUT_CHARS = 100_000;

    private ToolLimits() {
    }
}
