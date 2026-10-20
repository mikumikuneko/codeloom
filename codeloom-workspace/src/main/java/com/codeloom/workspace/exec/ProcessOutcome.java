package com.codeloom.workspace.exec;

import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.port.CommandTermination;

import java.util.Objects;

/**
 * 一次子进程执行的原始结果。
 *
 * <p>{@link #combinedOutput()} 先 stdout 后 stderr，不保留两路之间的时间顺序。
 * stderr 非空不代表失败：进度条和警告也走 stderr，成败看 {@link #termination()} 与
 * {@link #exitCode()}。输出超限时保留尾部，{@code truncated} 表示开头被丢弃。
 *
 * @param exitCode 退出码，由调用方按命令语义解释；被杀掉的进程这里是操作系统给的值，
 *                 没有业务意义
 * @param stdout 标准输出；超限时保留尾部
 * @param stderr 标准错误；非空不代表失败
 * @param truncated stdout 或 stderr 任意一路的开头被丢弃
 * @param durationMs 耗时
 * @param termination 进程是怎么结束的；null 按 {@link CommandTermination#COMPLETED} 处理
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
     * 命令成功了吗：先要求 {@link #termination()} 为
     * {@link CommandTermination#COMPLETED}，再看退出码。
     *
     * <p>被强杀的进程也可能报退出码 0，不能把它读成成功。
     *
     * @return 正常结束且退出码为 0 时为 true
     */
    public boolean succeeded() {
        // 与 CommandResult.success() 共用同一份判据，避免两层结论相反
        return CommandResult.succeeded(termination, exitCode);
    }

    /**
     * 标准输出与标准错误的合并，用于交给模型的工具结果。
     *
     * <p>先 stdout 后 stderr，不按时间混排；空的一边跳过。
     *
     * @return 合并后的文本
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
