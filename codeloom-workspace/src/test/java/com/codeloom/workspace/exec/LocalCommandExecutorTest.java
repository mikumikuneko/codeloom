package com.codeloom.workspace.exec;

import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.port.CommandTermination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真跑子进程。
 *
 * <p>命令是一行文本，由一层 shell 解释（见 {@link CommandShell}）。被测命令只使用这台
 * 机器上必然存在的工具：git、java、mvn、node。
 */
class LocalCommandExecutorTest {

    @TempDir
    Path tempDir;

    /** 执行器只接收工作目录，不需要会话身份。 */
    private Path workspace;

    @BeforeEach
    void setUp() {
        workspace = tempDir.toAbsolutePath();
    }

    /** 两个参数显式写出，便于看出用的是哪个 shell。 */
    private LocalCommandExecutor executor() {
        return new LocalCommandExecutor(ProcessRunner.nativeCharset(), CommandShell.detect(null));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("命令能跑，标准输出被带回来")
    void runsCommand() {
        CommandResult result = executor().execute(
                workspace, "git --version",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("git version");
    }

    @Test
    @DisplayName("【执行器不做准入判断】不在任何清单里的程序照样交给操作系统去跑")
    void doesNotGateOnAnExecutableList() {
        // 执行器不判断准入，只负责包 shell、执行、带回结果；拿白名单当准入会让
        // 需要审批的命令永远跑不起来。用一条必然存在的命令验证：不在任何清单里也能跑。
        CommandResult result = executor().execute(
                workspace, "java -version",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsIgnoringCase("version");
    }

    @Test
    @DisplayName("不存在的程序：包了 shell 之后它变成**一次普通的命令失败**，而不是起不来")
    void aMissingExecutableIsAnOrdinaryCommandFailure() {
        // 包 shell 之后由 bash 报 command not found、退出码 127，与终端里一致；
        // 模型拿到的是标准报错，不是平台编的提示
        CommandResult result = executor().execute(
                workspace, "codeloom-no-such-program-xyz --help",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("codeloom-no-such-program-xyz");
    }

    @Test
    @DisplayName("【管道能用】这是收整串换来的东西 —— 从前参数是数组，一行里的 | 只是普通字符")
    void aPipelineRuns() {
        CommandResult result = executor().execute(
                workspace, "git --version | head -1",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("git version");
    }

    @Test
    @DisplayName("【重定向能用】写出来的文件落在工作目录里（重定向的基准就是它）")
    void redirectionWritesIntoTheWorkspace() throws IOException {
        CommandResult result = executor().execute(
                workspace, "echo 构建完成 > out.txt",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(workspace.resolve("out.txt"), StandardCharsets.UTF_8).strip())
                .isEqualTo("构建完成");
    }

    @Test
    @DisplayName("【那一行不被我们改写】带了目录的程序名就照那个路径跑，跑不到就失败")
    void theLineIsNotRewritten() {
        // 这一行原样交给 shell；改写了的话，人批准的和实际执行的就是两条命令。
        // 带目录的词由 CommandPolicy 判为不免审批。
        CommandResult result = executor().execute(
                workspace, "/definitely/not/here/git --version",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("/definitely/not/here/git");
    }

    @Test
    @DisplayName("【没有 shell 就跑不了】不是「换个方式跑」—— 明确失败，并且说清怎么办")
    void withoutAShellTheCommandCannotRun() {
        LocalCommandExecutor noShell =
                new LocalCommandExecutor(StandardCharsets.UTF_8, CommandShell.none());

        assertThatThrownBy(() -> noShell.execute(
                workspace, "git --version",
                Duration.ofSeconds(30), 10_000, CancellationToken.none()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shell");
    }

    @Test
    @DisplayName("非零退出码不是异常，而是正常返回 —— 命令失败≠程序出错")
    void nonZeroExitIsAResultNotAnException() {
        CommandResult result = executor().execute(
                workspace, "git rev-parse --verify refs/heads/definitely-not-a-ref",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    @DisplayName("工作目录就是这条会话的 worktree")
    void runsInsideTheWorkspaceDirectory() throws IOException {
        runQuietly("git", "init", "-q", "-b", "main", tempDir.toString());

        CommandResult result = executor().execute(
                workspace, "git rev-parse --show-toplevel",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        // 用 isSameFile 而不是字符串比较：Windows 的短路径名会让字面比较失败
        assertThat(Files.isSameFile(Path.of(result.output().strip()), tempDir)).isTrue();
    }

    @Test
    @DisplayName("输出超限会被截断，并且【如实置起 truncated 标志】")
    void truncatesAndFlagsIt() {
        int limit = 200;
        CommandResult result = executor().execute(
                workspace, "git --help",
                Duration.ofSeconds(30), limit, CancellationToken.none());

        assertThat(result.truncated()).isTrue();
        // 上界按实现算，不能随手写一个宽松值；两个流各截到 limit，
        // 合并体最多是 limit + 一个换行 + limit
        assertThat(result.output().length()).isLessThanOrEqualTo(2 * limit + 1);
    }

    @Test
    @DisplayName("超时会【杀掉整棵进程树】，而且**把已经打出来的输出还回来**")
    void killsProcessTreeOnTimeout() {
        LocalCommandExecutor executor = executor();

        CommandResult result = executor.execute(
                workspace, "node -e 'console.log(\"构建进行到一半\"); setTimeout(()=>{}, 30000)'",
                Duration.ofMillis(900), 10_000, CancellationToken.none());

        assertThat(result.termination()).isEqualTo(CommandTermination.TIMED_OUT);
        // 超时是"没跑完"，不是失败
        assertThat(result.success()).isFalse();
        // 退出码不给：被强杀进程的数字没有业务意义，交出去就会被拿去判断
        assertThat(result.exitCode()).isNull();
        // 已产生的输出必须还回来，它包含"跑到哪一步卡住"的信息
        assertThat(result.output()).contains("构建进行到一半");
    }

    @Test
    @DisplayName("取消信号能在执行中途把进程树掐掉 —— 用户按 Esc 就靠这个")
    void cancelKillsRunningProcess() throws Exception {
        LocalCommandExecutor executor = executor();
        CancellationToken cancellation = new CancellationToken();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = pool.submit(() -> executor.execute(
                    workspace, "node -e 'setTimeout(()=>{}, 30000)'",
                    Duration.ofSeconds(60), 10_000, cancellation));

            // 不用 sleep 等进程起来：等待循环每 100ms 检查一次取消信号，
            // 信号早到或晚到都会杀掉整棵进程树
            cancellation.cancel();

            // 取消与超时一样是结果而不是异常，只是终止原因不同；
            // 上层据此记录 ToolCancelled（用户意图），而不是工具失败
            CommandResult result = running.get();
            assertThat(result.termination()).isEqualTo(CommandTermination.CANCELLED);
            assertThat(result.success()).isFalse();
            assertThat(result.exitCode()).isNull();
        }
    }

    @Test
    @DisplayName("超时只允许更短：【荒唐的超时会被夹到内部上限】，而正常请求原样通过")
    void clampNeverLengthensTheTimeout() {
        // 直接测 clamp：真跑的话要等满 15 分钟，或把硬上限做成可配置。
        // 它包级可见就是为了这条断言
        assertThat(LocalCommandExecutor.clamp(Duration.ofDays(30)))
                .as("超过上限的请求被夹到上限")
                .isEqualTo(LocalCommandExecutor.clamp(null))
                .isLessThanOrEqualTo(Duration.ofMinutes(15));

        assertThat(LocalCommandExecutor.clamp(Duration.ofSeconds(5)))
                .as("没超上限的请求不被改动")
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("荒谬的超时不会让命令执行本身出问题")
    void absurdTimeoutStillRunsTheCommand() {
        CommandResult result = executor().execute(
                workspace, "git --version",
                Duration.ofDays(30), 10_000, CancellationToken.none());

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("【不该挂】不带参数读标准输入的命令立刻结束 —— 子进程的 stdin 一启动就关了")
    void commandsThatReadStdinFinishInsteadOfHanging() {
        // 不带参数的 cat 会一直读标准输入；子进程的 stdin 一启动就关闭，它应立刻结束。
        // 超时设为 5 秒：真挂了会以超时失败
        CommandResult result = executor().execute(
                workspace, "cat",
                Duration.ofSeconds(5), 10_000, CancellationToken.none());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isBlank();
    }

    @Test
    @DisplayName("【引号能活到子进程手里】带空格的参数原样到达 —— 走脚本文件换来的")
    void quotedArgumentsArriveIntact() {
        // 直接走 bash -c 时引号会在 JVM 到 MSYS 的路上被吃掉（实测输出 "x" 而不是
        // "x y"）；落成脚本后，命令行上只有一个独立的路径参数
        CommandResult result = executor().execute(
                workspace, "printf '%s\\n' \"x y\"",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.output().strip()).isEqualTo("x y");
    }

    @Test
    @DisplayName("【编码】UTF-8 的输出照常 —— 兜底逻辑不能把本来对的那条路弄坏")
    void utf8OutputStillDecodes() {
        // 命令行里真的有中文：走 -c 时中文字节会吃掉紧挨着它的引号，shell 会报引号未配平
        CommandResult result = executor().execute(
                workspace, "node -e 'console.log(\"中文 abc\")'",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.output()).contains("中文 abc");
    }

    @Test
    @DisplayName("【回车】命令里有 \\r 直接拒 —— 它会让「一行」变成「两行」，而报错指着别处")
    void carriageReturnIsRejected() {
        assertThatThrownBy(() -> executor().execute(
                workspace, "git --version\rrm -rf /",
                Duration.ofSeconds(30), 10_000, CancellationToken.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回车");
    }

    @Test
    @DisplayName("【编码·端到端】跟随本机编码的子进程也解得对 —— 不是所有工具都说 UTF-8")
    void decodesAChildThatFollowsTheNativeEncoding() throws IOException {
        // 这条走兜底分支：裸 JVM 的 stdout 跟随 native.encoding（中文 Windows 上是 GBK），
        // 解不出合法 UTF-8 时按本机编码重解；node 那条走的是 UTF-8 分支，两条都要覆盖。
        // 源码刻意写成纯 ASCII（中文用 \\u 转义），避免把源文件编码与输出编码混在一起
        Path source = workspace.resolve("EchoChinese.java");
        Files.writeString(source, """
                public class EchoChinese {
                    public static void main(String[] args) {
                        System.out.println("\\u6784\\u5efa\\u5b8c\\u6210");
                    }
                }
                """, StandardCharsets.US_ASCII);

        // 路径加引号后再拼进命令行，避免空格与反斜杠出问题；
        // 斜杠换成 /（Windows 上两种都认）
        String javaFile = source.toString().replace('\\', '/');

        CommandResult result = executor().execute(
                workspace, "java \"" + javaFile + "\"",
                Duration.ofSeconds(60), 10_000, CancellationToken.none());

        assertThat(result.success()).as("子进程本身跑成功：%s", result.output()).isTrue();
        assertThat(result.output()).contains("构建完成");
    }

    @Test
    @DisplayName("【Windows】裸名 mvn 由 shell 解析 —— 从前必须写成 mvn.cmd 才起得来")
    void bareNamesAreResolvedByTheShell() {
        // Windows 上 mvn 实际是 mvn.cmd，ProcessBuilder 不按 PATHEXT 解析；
        // 包一层 shell 后，名字解析归 shell 管
        CommandResult result = executor().execute(
                workspace, "mvn -v",
                Duration.ofSeconds(60), 10_000, CancellationToken.none());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("Apache Maven");
    }

    // ------------------------------------------------------------------

    private static void runQuietly(String... command) throws IOException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("被中断", e));
        }
    }
}
