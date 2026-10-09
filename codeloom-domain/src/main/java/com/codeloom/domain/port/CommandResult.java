package com.codeloom.domain.port;

/**
 * 命令执行的结果。
 *
 * @param exitCode   退出码。**被我们杀掉的命令这里是 null** —— 见下面那段
 * @param output     标准输出与标准错误的合并结果，超过上限已被截断
 * @param truncated  是否发生了截断。**必须如实传递** —— 截断了却不说，模型会以为
 *                   "输出就这么多"，然后基于残缺信息做判断，比直接报错还糟
 * @param durationMs 耗时
 * @param termination 进程是怎么结束的。见 {@link CommandTermination}
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
     * 跑完了的那一种 —— 绝大多数调用都是它。
     *
     * <p>同 {@code ToolOutcome} 的那个重载：{@code COMPLETED} 是常态，
     * 每个调用点都补一个只会让人以为那里漏了什么。
     */
    public CommandResult(int exitCode, String output, boolean truncated, long durationMs) {
        this(exitCode, output, truncated, durationMs, CommandTermination.COMPLETED);
    }

    /**
     * 成功 = **跑完了**，而且它自己报的退出码是 0。
     *
     * <h2>为什么被杀掉的命令不能算成功</h2>
     * 因为它的退出码不是它的结论。一条被强杀的进程，操作系统也可能报出一个 0；
     * 拿那个 0 当"命令成功了"，就等于把"我们掐断了它"说成"它干完了"——
     * 而调用方会据此认为构建通过、验证通过。所以成功的判据里，
     * "谁结束了它"这一条必须排在退出码前面。
     *
     * <h2>为什么被杀掉的命令也没有退出码</h2>
     * 不是"拿不到"，是**不想给**：那个数字一旦交出去，就一定有人拿它去判断，
     * 而它在这里唯一可能的用途是误导。真正的结论在 {@link #termination()} 里。
     */
    public boolean success() {
        return termination == CommandTermination.COMPLETED && exitCode != null && exitCode == 0;
    }
}
