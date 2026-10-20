package com.codeloom.workspace.exec;

import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.port.CommandTermination;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link CommandExecutor} 的宿主机进程实现：包一层 shell、执行、带回结果。
 *
 * <p>它不做准入判断：能不能跑由人决定，是否免审批由 {@link CommandPolicy} 与
 * {@link CommandPathScope} 判断，能碰到什么由工作区和 {@link ProcessRunner} 的环境
 * 清洗约束。它也不改写模型给的命令，否则人批准的和实际执行的不是同一条。
 *
 * <p>硬限制：没有 shell 就无法执行；超时只能缩短不能延长；输出有上限并保留尾部。
 * 子进程仍能访问本机的文件与网络，批准一条命令等于允许执行这条命令。
 */
public final class LocalCommandExecutor implements CommandExecutor {

    /** 调用方请求的超时不得超过此上限。 */
    private static final Duration HARD_LIMIT = Duration.ofMinutes(15);

    /** 调用方没有给出正数输出上限时使用的字符上限。 */
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 200_000;

    private final Charset outputCharset;
    private final CommandShell shell;

    /**
     * @param fallbackCharset 子进程输出不是合法 UTF-8 时的兜底字符集（中文 Windows 上
     *                        通常是 GBK），见 {@link ProcessRunner#decode(byte[], Charset)}
     * @param shell 解释命令行的那层 shell，通常来自 {@code CommandShell.detect}
     */
    public LocalCommandExecutor(Charset fallbackCharset, CommandShell shell) {
        this.outputCharset = Objects.requireNonNull(fallbackCharset, "fallbackCharset");
        this.shell = Objects.requireNonNull(shell, "shell");
    }

    /**
     * 执行一行命令并等待结束。
     *
     * <p>超时会被夹到 {@link #HARD_LIMIT} 以内；{@code maxOutputChars} 非正数时使用默认
     * 上限。命令先写入系统临时目录的脚本文件，执行完删除。
     *
     * @param worktree 工作目录，命令在此目录下执行
     * @param commandLine 整行命令，交给 shell 解释
     * @param timeout 请求的超时；只能缩短，不能超过硬上限
     * @param maxOutputChars 单个流的字符上限；非正数使用默认值
     * @param cancellation 取消信号
     * @return 命令结果；超时和取消同样通过返回值报告
     * @throws IllegalArgumentException 命令为空或包含回车符
     * @throws ProcessException 脚本无法写入或进程无法启动
     * @throws IllegalStateException 本机没有可用的 shell
     */
    @Override
    public CommandResult execute(Path worktree,
                                 String commandLine,
                                 Duration timeout,
                                 int maxOutputChars,
                                 CancellationToken cancellation) {
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(cancellation, "cancellation");
        if (commandLine == null || commandLine.isBlank()) {
            throw new IllegalArgumentException("命令不能为空");
        }
        if (commandLine.indexOf('\r') >= 0) {
            // 回车会让"一行"变成"两行"；bash 的报错指向行尾，很难看出与回车有关
            throw new IllegalArgumentException("命令里不能有回车符（\\r）");
        }

        Duration effective = clamp(timeout);
        int limit = maxOutputChars > 0 ? maxOutputChars : DEFAULT_MAX_OUTPUT_CHARS;

        // 命令先落到脚本文件，再把文件路径交给 shell；直接走 bash -c 会吃掉引号，
        // 见 CommandShell.argvFor。文件在系统临时目录，不进项目树，跑完删除
        Path script = null;
        try {
            script = Files.createTempFile("codeloom-cmd-", ".sh");
            Files.writeString(script, commandLine, StandardCharsets.UTF_8);

            // 没有 shell 时这里会抛，见类注释
            List<String> toRun = shell.argvFor(script);

            ProcessOutcome outcome = ProcessRunner.run(toRun, worktree, Map.of(),
                    effective, limit, outputCharset, cancellation);

            return new CommandResult(exitCodeOf(outcome), outcome.combinedOutput(),
                    outcome.truncated(), outcome.durationMs(), outcome.termination());
        } catch (IOException e) {
            throw new ProcessException("命令没能落盘执行: " + e.getMessage(), e);
        } finally {
            deleteQuietly(script);
        }
    }

    /**
     * 交给 domain 的退出码：被我们杀掉的命令不给。
     *
     * <p>被强杀的进程可能报 0，而 0 会被读作成功；结论在 {@link CommandTermination} 里。
     */
    private static Integer exitCodeOf(ProcessOutcome outcome) {
        return outcome.termination() == CommandTermination.COMPLETED
                ? outcome.exitCode()
                : null;
    }

    /**
     * 收尾删除临时脚本；删除失败不影响结果。
     *
     * <p>命令已经跑完，此时抛异常会把成功的执行报成失败。文件在系统临时目录，留下也
     * 不会碰到用户的项目。
     */
    private static void deleteQuietly(Path script) {
        if (script == null) {
            return;
        }
        try {
            Files.deleteIfExists(script);
        } catch (IOException ignored) {
            // 见方法注释：这里是收尾，不是主流程
        }
    }

    /**
     * 把超时夹到上限以内：可以要短，不许要长。
     *
     * <p>上限写死，不可配置。没给、给 0 或给负数，一律按上限算。包级可见是让测试能
     * 直接验证夹取，而不必真跑一条 15 分钟的命令；纯函数，可见性不影响行为。
     *
     * @param requested 调用方请求的超时
     * @return 不超过 {@link #HARD_LIMIT} 的有效超时
     */
    static Duration clamp(Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return HARD_LIMIT;
        }
        return requested.compareTo(HARD_LIMIT) > 0 ? HARD_LIMIT : requested;
    }
}
