package com.codeloom.workspace.exec;

import com.codeloom.domain.port.CommandTermination;

import java.util.Objects;

/**
 * 一次子进程执行的原始结果。
 *
 * @param exitCode   退出码。**调用方必须自己解释它** —— 不同命令的退出码含义完全不同，
 *                   比如 git 的 1 是"有冲突"（正常业务路径），128 才是致命错误。
 *
 *                   <p>被杀掉的进程这里报的是**操作系统给的那个数**（Windows 上多半是 1，
 *                   POSIX 上是 128+信号），它在这条命令的语义里没有意义 ——
 *                   所以下结论请看 {@link #termination()}，别拿这个数字当"命令失败了"。
 *                   （domain 那一层的 {@code CommandResult} 把它抹成了 null，理由见那边。）
 * @param stdout     标准输出，超出上限的部分已丢弃
 * @param stderr     标准错误，同上
 * @param truncated  是否发生了截断。**必须如实传递**，否则模型会以为输出就这么多
 * @param durationMs 耗时
 * @param termination 进程是怎么结束的
 */
public record ProcessOutcome(int exitCode,
                             String stdout,
                             String stderr,
                             boolean truncated,
                             long durationMs,
                             CommandTermination termination) {

    public ProcessOutcome {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        termination = termination == null ? CommandTermination.COMPLETED : termination;
    }

    /** 只用于测试与"跑完了"那条路的省事写法。 */
    public ProcessOutcome(int exitCode, String stdout, String stderr, boolean truncated,
                          long durationMs) {
        this(exitCode, stdout, stderr, truncated, durationMs, CommandTermination.COMPLETED);
    }

    /**
     * 命令成功了吗。
     *
     * <p>**"跑完了"排在退出码前面**：一条被我们强杀的进程，系统也可能报出退出码 0，
     * 而拿那个 0 当成功，等于把"我们掐断了它"读成"它干完了"。
     */
    public boolean succeeded() {
        return termination == CommandTermination.COMPLETED && exitCode == 0;
    }

    /** 标准输出与标准错误的合并，用于交给模型的工具结果。 */
    public String combinedOutput() {
        if (stdout.isBlank()) {
            return stderr;
        }
        if (stderr.isBlank()) {
            return stdout;
        }
        return stdout + System.lineSeparator() + stderr;
    }
}
