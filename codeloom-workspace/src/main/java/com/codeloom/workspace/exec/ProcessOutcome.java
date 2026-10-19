package com.codeloom.workspace.exec;

import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.port.CommandTermination;

import java.util.Objects;

/**
 * 一次子进程执行的原始结果。
 *
 * <h2>一个例子</h2>
 * 跑 {@code bash -c 'echo 结果; echo 警告 >&2; echo 更多结果'}：stdout 是
 * 「结果 / 更多结果」，stderr 是「警告」。{@link #combinedOutput()} 拼出来是
 * 「结果 / 更多结果 / 警告」—— 原来的先后顺序没了。
 *
 * <p>成功命令的 stderr 也可以有内容：{@code curl} 的进度条、{@code mvn} 的警告都走
 * stderr；而失败原因既可能在 stdout 的测试报告里，也可能在 stderr 的编译错误里。
 * 所以**stderr 非空不等于失败**，看 {@link #termination()} 和退出码。
 *
 * <p>超限时保留的是**最后一段**（构建失败的原因通常在那里）；{@code truncated=true}
 * 说的是开头被丢了，不是结尾。
 *
 * @param exitCode   退出码。**调用方必须自己解释它** —— 不同命令的退出码含义完全不同，
 *                   比如 git 的 1 是"有冲突"（正常业务路径），128 才是致命错误。
 *
 *                   <p>被杀掉的进程这里报的是**操作系统给的那个数**（Windows 上多半是 1，
 *                   POSIX 上是 128+信号），它在这条命令的语义里没有意义 ——
 *                   所以下结论请看 {@link #termination()}，别拿这个数字当"命令失败了"。
 *                   （domain 那一层的 {@code CommandResult} 把它抹成了 null，理由见那边。）
 * @param stdout     标准输出：程序写出的正常结果。超出上限时**保留尾部**，开头丢弃
 * @param stderr     标准错误：诊断、进度、警告。**非空不代表失败** —— 成败看
 *                   {@link #termination()} 与 {@link #exitCode()}
 * @param truncated  stdout 或 stderr **任意一路**的开头被丢弃了；不区分是哪一路。
 *                   **必须如实传递**，否则模型会以为输出就这么多
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

    /**
     * 命令成功了吗。
     *
     * <p>**"跑完了"排在退出码前面**：一条被我们强杀的进程，系统也可能报出退出码 0，
     * 而拿那个 0 当成功，等于把"我们掐断了它"读成"它干完了"。
     */
    public boolean succeeded() {
        // 和 CommandResult.success() **同一份判据**：两层各写一遍，
        // 改一边就会让"执行器说成功、工具层说失败"
        return CommandResult.succeeded(termination, exitCode);
    }

    /**
     * 标准输出与标准错误的合并，用于交给模型的工具结果。
     *
     * <p>**先 stdout 后 stderr，不是按时间混排**：两路各自完整，但原始先后顺序不保留。
     * 空的那一边跳过。
     */
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
