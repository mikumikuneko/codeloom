package com.codeloom.agent.tool;

/**
 * 工具之间共用的量。
 *
 * <p>放这里的判据只有一个：**两个以上的工具在用，而且它们说的必须是同一个数**。
 * 各写各的时候，读文件 100,000、跑命令 100,000、抓网页 100,000 看起来像三件事，
 * 改一个漏两个也不会报错。
 */
public final class ToolLimits {

    /**
     * 单次调用交给模型的输出上限（字符）。
     *
     * <p>读文件、跑命令、抓网页都是"一次调用的产出"—— 同一个概念，就该是同一个数。
     * 它**不是**这一轮的总预算：那是循环那一层的 {@code MAX_TOOL_OUTPUT_CHARS_PER_TURN}。
     */
    public static final int MAX_OUTPUT_CHARS = 100_000;

    private ToolLimits() {
    }
}
