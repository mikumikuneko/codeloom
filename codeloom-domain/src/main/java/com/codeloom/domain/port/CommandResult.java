package com.codeloom.domain.port;

/**
 * 命令执行的结果。
 *
 * <p>构造时拒绝负的 {@code durationMs}；{@code termination} 为 null 时按
 * {@code COMPLETED} 处理。
 *
 * @param exitCode    退出码；被我们杀掉的命令这里是 null
 * @param output      标准输出与标准错误的合并结果，超过上限已被截断
 * @param truncated   是否发生截断。必须如实传递：截断了却不说，模型会以为输出就这么多，
 *                    基于残缺信息做判断比直接报错还糟
 * @param durationMs  耗时
 * @param termination 进程是怎么结束的，见 {@link CommandTermination}
 */
public record CommandResult(Integer exitCode,
                            String output,
                            boolean truncated,
                            long durationMs,
                            CommandTermination termination) {

    public CommandResult {
        if (durationMs < 0) {
            throw new IllegalArgumentException("耗时不能为负");
        }
        termination = termination == null ? CommandTermination.COMPLETED : termination;
    }

    /**
     * 测试与便捷用的重载：假定 {@code COMPLETED}。
     *
     * <p>产品路径总是显式带上 {@code termination}（执行器知道是谁结束了进程）；这个
     * 重载省掉的正好是「命令有没有被我们杀掉」那条信息，而它不能靠默认值猜。
     *
     * @param exitCode   退出码
     * @param output     输出
     * @param truncated  是否截断
     * @param durationMs 耗时
     */
    public CommandResult(int exitCode, String output, boolean truncated, long durationMs) {
        this(exitCode, output, truncated, durationMs, CommandTermination.COMPLETED);
    }

    /**
     * 成功 = 跑完了，而且命令自己报的退出码是 0。
     *
     * <p>被强杀的进程不算成功：它的退出码不是它的结论 —— 操作系统也可能报 0，拿那个 0
     * 当成功，等于把「我们终止了它」说成「它干完了」，调用方会据此认为构建或验证通过。
     * 所以判据里「谁结束了它」排在退出码前面；被杀掉的命令也没有退出码（刻意不给，
     * 那个数字一旦交出去就一定有人拿它去判断）。真正的结论在 {@link #termination()} 里。
     * 见
     * {@code docs/decisions/architecture/2026-10-06-cancel-is-a-signal-timeout-is-a-result.md}。
     *
     * @return 终止方式为 {@code COMPLETED} 且退出码为 0
     */
    public boolean success() {
        return succeeded(termination, exitCode);
    }

    /**
     * 同一条判据的唯一实现。执行器的 {@code ProcessOutcome} 也调它 —— 两层各写一遍时，
     * 任何一边改动都会让「执行器说成功、工具层说失败」成为可能，而那种错只在某个
     * 终止方式上才显形。
     *
     * @param termination 终止方式
     * @param exitCode    退出码，可为 null
     * @return 是否成功
     */
    public static boolean succeeded(CommandTermination termination, Integer exitCode) {
        return termination == CommandTermination.COMPLETED && exitCode != null && exitCode == 0;
    }
}
