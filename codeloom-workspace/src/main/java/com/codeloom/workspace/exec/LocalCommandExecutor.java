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
 * {@link CommandExecutor} 的**进程实现**：在宿主机上直接拉起子进程。
 *
 * <h2>它只做三件事：包一层 shell、跑起来、把结果带回来</h2>
 * 边界不在这里：
 * <ul>
 *   <li><b>能不能跑</b> —— 由人决定。审批那一层把命令和理由摆给人看，人点是或否。</li>
 *   <li><b>能不能免审批</b> —— 由 {@code CommandPolicy}（程序名）和
 *       {@code CommandPathScope}（路径）判，判完再把命令交到这里。</li>
 *   <li><b>能碰到什么</b> —— 由工作区决定。文件工具走 {@code WorkspacePathGuard}，
 *       命令以工作区为工作目录。</li>
 * </ul>
 *
 * <p>它**不能**拿可执行文件白名单当"准入"：那个白名单同时被用作"要不要问人"的判据
 * （见 app 侧的审批），拿它决定"跑不跑"就成了一句自相矛盾的话 ——
 * **需要审批的命令 ≡ 不在白名单 ≡ 这里一定拒绝**，审批点十次也跑不了，
 * 因为挡住它的根本不是审批。真实会话里的形态就是：用户批准了 {@code javac -version}，
 * 回来的却是「命令被拒绝: javac，允许的可执行文件 [python, node, mvn, …]」。
 *
 * <p>它同样**不改写**模型给的命令（比如把第一个词剥掉目录再执行）—— 改写了，
 * 人批准的和实际跑的就不是同一条。堵住那个洞的办法在判据那一边：
 * **带路径的第一个词一律先问人**，见 {@link CommandPolicy}。
 *
 * <h2>三道真正的限制</h2>
 * <ol>
 *   <li><b>没有 shell 就跑不了。</b>命令是一行文本，只有 shell 能解释它。
 *       找不到 shell 时这里直接失败（{@link CommandShell#argvFor} 抛），
 *       没有"退回直接拉起可执行文件"那条路 —— 那条路对一行文本不成立。</li>
 *   <li><b>超时可以被调用方缩短，但不能被拉长。</b>模型没法通过传一个
 *       {@code Duration.ofDays(1)} 把执行器占住。</li>
 *   <li><b>输出有上限。</b>那种刷几万行的构建输出一次就能把上下文窗口和数据库同时打爆。</li>
 * </ol>
 *
 * <h2>要诚实的地方</h2>
 * 这只是"边界在工作区"的一半。文件工具是真的被关在里面的，命令不是 ——
 * 子进程能碰这台机器上它能碰到的一切（含网络）。真隔离要 cgroup + mount namespace +
 * 只读挂载，那要等容器实现（接口不用变）。在那之前，
 * **批准一条命令等于把这条命令交给它执行**，这是使用者要知道的事。
 * 参考实现里 deepseek-harness 是唯一真做了这层的：它的沙箱按文件效果分三档
 * （只读 / 工作区可写 / 全权），明说"网络与进程可见性不在这套词汇里"。
 */
public final class LocalCommandExecutor implements CommandExecutor {

    /** 无论调用方要多久，都不超过这个上限。 */
    private static final Duration HARD_LIMIT = Duration.ofMinutes(15);

    private static final int DEFAULT_MAX_OUTPUT_CHARS = 200_000;

    private final Charset outputCharset;
    private final CommandShell shell;

    /**
     * 就这么一个构造器 —— 两个参数都是外面决定的，这里不自己猜：
     *
     * @param fallbackCharset 子进程输出**不是合法 UTF-8** 时用来兜底的字符集
     *                        （中文 Windows 上是 GBK），见
     *                        {@link ProcessRunner#decode(byte[], Charset)}
     * @param shell           命令行交给哪一层 shell 解释。传进来的通常是
     *                        {@code CommandShell.detect(配置值)} 探测出来的那个：
     *                        探测得有配置才叫探测，而配置、启动日志、"配置写错了"
     *                        那声警告都在 {@code WorkspaceConfig} 那边。
     *                        这里自己 {@code detect(null)} 一次也行，但那等于把
     *                        "这台机器上用的是哪个 bash"变成第二处需要看日志才知道的东西
     */
    public LocalCommandExecutor(Charset fallbackCharset, CommandShell shell) {
        this.outputCharset = Objects.requireNonNull(fallbackCharset, "fallbackCharset");
        this.shell = Objects.requireNonNull(shell, "shell");
    }

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
            // 回车会让"一行"变成"两行"：脚本文件里的 \r 是 bash 认不出来的一串字符，
            // 报出来的错还指着行尾，很难看出跟回车有关。这里直接挡住，说清是什么
            throw new IllegalArgumentException("命令里不能有回车符（\\r）");
        }

        Duration effective = clamp(timeout);
        int limit = maxOutputChars > 0 ? maxOutputChars : DEFAULT_MAX_OUTPUT_CHARS;

        // 命令先落到一份脚本文件里，再把**文件路径**交给 shell —— 为什么不直接把那一行
        // 交给 `bash -c`，见 CommandShell.argvFor：那样送过去引号会被吃掉。
        // 文件放系统临时目录，不进项目树，跑完就删
        Path script = null;
        try {
            script = Files.createTempFile("codeloom-cmd-", ".sh");
            Files.writeString(script, commandLine, StandardCharsets.UTF_8);

            // 没有 shell 时这里会抛，见类注释第 1 条
            List<String> toRun = shell.argvFor(script);

            ProcessOutcome outcome = ProcessRunner.run(toRun, worktree, Map.of(),
                    effective, limit, outputCharset, cancellation);

            return new CommandResult(exitCodeOf(outcome), outcome.combinedOutput(),
                    outcome.truncated(), outcome.durationMs(), outcome.termination());
        } catch (IOException e) {
            throw new ProcessException(List.of(commandLine), ProcessException.Reason.START_FAILED,
                    "命令没能落盘执行: " + e.getMessage(), e);
        } finally {
            deleteQuietly(script);
        }
    }

    /**
     * 交给 domain 的退出码：**被我们杀掉的命令不给**。
     *
     * <p>不是"拿不到"（操作系统其实报了一个数），是**不想给** ——
     * 那个数字一旦交出去就一定有人拿它判断，而它在这里唯一可能的用途是误导：
     * 一条被强杀的进程可能报 0，而 0 在这门语言里读作"成功"。
     * 真正的结论在 {@link CommandTermination} 里。
     */
    private static Integer exitCodeOf(ProcessOutcome outcome) {
        return outcome.termination() == CommandTermination.COMPLETED
                ? outcome.exitCode()
                : null;
    }

    /**
     * 收尾删掉那份临时脚本。
     *
     * <p>**删不掉不能影响结果** —— 命令已经跑完了，此时抛异常等于把一个成功的执行
     * 报成失败。文件在系统临时目录里，留一份也不会碰到用户的项目。
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
     * 把超时夹到上限以内：**可以要短，不许要长**。
     *
     * <p>秒数是模型自己填的（{@code run_command} 的参数，不填是 300 秒），想填多大都行 ——
     * 没有这一刀，它写个 86400 就能把执行器占住一整天。上限本身写死：它不该可配，
     * 也不该被谁临时放宽。
     *
     * <p>没给、给 0、给负数，一律按上限算。（真实链路上其实到不了这一支：
     * {@code RunCommandTool} 已经先把秒数抬到至少 1 秒。）
     *
     * <p>包级可见是留给测试的：验"超过上限会被砍"只要叫一下这个函数，
     * 否则得真跑一条 15 分钟的命令。纯函数，这一层可见性不影响任何行为。
     */
    static Duration clamp(Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return HARD_LIMIT;
        }
        return requested.compareTo(HARD_LIMIT) > 0 ? HARD_LIMIT : requested;
    }
}
