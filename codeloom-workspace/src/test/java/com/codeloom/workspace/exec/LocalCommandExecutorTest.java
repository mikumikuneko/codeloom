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
 * <h2>它收的是**一行命令**</h2>
 * 一行交给一层 shell 解释（见 {@link CommandShell}），所以这里的命令写成
 * {@code "git --version"} 这个样子，而不是一串分词好的参数。
 *
 * <p>被测命令只用这台机器上**必然有**的那几个：{@code git}（项目必需）、
 * {@code java}（跑测试就要）、{@code mvn}（构建就要）、{@code node}（前端就要）。
 * 不引入新的环境依赖。
 */
class LocalCommandExecutorTest {

    @TempDir
    Path tempDir;

    /** 执行器只收工作目录，不要整个 Workspace 聚合 —— 不可信路径不需要会话身份。 */
    private Path workspace;

    @BeforeEach
    void setUp() {
        workspace = tempDir.toAbsolutePath();
    }

    /** 两个参数显式写出来，读测试的人能看到用了哪个 shell。 */
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
        // 执行器不判断准入，只负责包 shell + 跑 + 带回结果。它一度拿可执行文件白名单当准入判断，
        // 而同一个白名单又是"要不要问人"的判据 —— 于是**需要审批的命令一定被它拒绝**。
        //
        // 用一条**必然存在**的命令来验：不在任何清单里（这里根本没有清单），但它跑得起来。
        CommandResult result = executor().execute(
                workspace, "java -version",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsIgnoringCase("version");
    }

    @Test
    @DisplayName("不存在的程序：包了 shell 之后它变成**一次普通的命令失败**，而不是起不来")
    void aMissingExecutableIsAnOrdinaryCommandFailure() {
        // 现在是 **bash 报的 command not found，退出码 127** —— 和终端里打错一个命令
        // 看到的一模一样。包 shell 之后，"命令不存在"本来就是 shell 的活，不该再由我们判断：
        // 模型拿到的是标准报错，而不是我们编的一句话（从前它先是"越权异常"，
        // 后来是"进程起不来"）。
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
        // 这一行原样交给 shell：改写了的话，人批准的和实际跑的就成了两条命令。
        // 防"模型指着自己塞进来的程序"这件事换到了判据那一边 ——
        // 带目录的词一律不免审批（见 CommandPolicy）。
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

        // 用 isSameFile 而不是字符串比较 —— Windows 上短路径名会让字面比较失败
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
        // 上界要贴着实现算出来，不能随手写个 1000 —— 那样"截断到多少"等于没验。
        // 两个流各自被截到 limit，合并体最多是 limit + 一个换行 + limit
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
        // **不是失败，是"没跑完"** —— 两者的区别正是这次改动要立起来的东西
        assertThat(result.success()).isFalse();
        // 退出码不给：被强杀的进程那个数字没有意义，而它一旦交出去就会被拿去判断
        assertThat(result.exitCode()).isNull();
        // ★ 这一条是重点：那半截输出恰恰是最该看的 —— "跑到哪一步卡住的"全在里面，
        // 而从前超时抛异常时会把它**一起扔掉**
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

            // 不用先睡一觉等它起来：取消的检查点在等待循环里每 100ms 看一次，
            // 所以信号早到（进程还没拉起来）晚到（正跑着）结果都一样 ——
            // 都是"发现已取消 → 杀掉整棵进程树"。固定 sleep 只是让快机器白等
            cancellation.cancel();

            // 取消和超时一样，是**结果**而不是异常 —— 只是终止原因不同。
            // 上层据此落 ToolCancelled（用户意图），而不是一条"工具失败了"
            CommandResult result = running.get();
            assertThat(result.termination()).isEqualTo(CommandTermination.CANCELLED);
            assertThat(result.success()).isFalse();
            assertThat(result.exitCode()).isNull();
        }
    }

    @Test
    @DisplayName("超时只允许更短：【荒唐的超时会被夹到内部上限】，而正常请求原样通过")
    void clampNeverLengthensTheTimeout() {
        // 直接测 clamp 本身。写成"跑一条命令看它多久被杀"的话，要么真等满 15 分钟，
        // 要么把上限做成可注入的配置 —— 而它本来就该是个不可调的硬上限。
        // 见 clamp 的注释：它包级可见就是为了这条断言
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
        // `cat` 不带参数会一直读标准输入。从前那条管道我们**从没关过**，于是它就一直等
        // —— 用户看到的是"命令卡住了"，而真相是在等一个永远不来的字节。
        // 超时只有 5 秒：真挂了这条测试会以超时异常失败，而不是慢慢跑完
        CommandResult result = executor().execute(
                workspace, "cat",
                Duration.ofSeconds(5), 10_000, CancellationToken.none());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isBlank();
    }

    @Test
    @DisplayName("【引号能活到子进程手里】带空格的参数原样到达 —— 走脚本文件换来的")
    void quotedArgumentsArriveIntact() {
        // 若直接把那一行交给 `bash -c`，引号会在"JVM → MSYS"那段路上被吃掉 ——
        // 这一条实测会输出 "x"（而不是 "x y"）。先落一份脚本再跑，
        // 整条路上只有一个纯 ASCII 的路径作为参数
        CommandResult result = executor().execute(
                workspace, "printf '%s\\n' \"x y\"",
                Duration.ofSeconds(30), 10_000, CancellationToken.none());

        assertThat(result.output().strip()).isEqualTo("x y");
    }

    @Test
    @DisplayName("【编码】UTF-8 的输出照常 —— 兜底逻辑不能把本来对的那条路弄坏")
    void utf8OutputStillDecodes() {
        // 命令行里**真的有中文**（不是 \\u 转义）：这也是上面那条引号性质的延伸 ——
        // 从前送 `-c` 时，中文那些字节会吃掉紧挨着它的引号，shell 会报"引号没配平"
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
        // 这条走的是**兜底分支**：裸 JVM 的 stdout 跟随 native.encoding
        // （中文 Windows 上是 GBK），解不出合法 UTF-8，于是按本机编码重解。
        // 上面那条 node 的测试走的是另一条分支（node 说 UTF-8）。两条都得有。
        //
        // 源码刻意写成**纯 ASCII**（中文用 \\u 转义）：javac 会按本机编码读源文件，
        // 而这里要验的是"子进程输出"的编码，不是"源文件"的编码 ——
        // 把两件事缠在一起，失败了会分不清是哪一边
        Path source = workspace.resolve("EchoChinese.java");
        Files.writeString(source, """
                public class EchoChinese {
                    public static void main(String[] args) {
                        System.out.println("\\u6784\\u5efa\\u5b8c\\u6210");
                    }
                }
                """, StandardCharsets.US_ASCII);

        // 路径要**引用**之后再拼进那一行，空格和反斜杠才不出事；
        // 斜杠换成 / 是给 shell 看的（Windows 上两种都认）
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
        // Windows 上 mvn 实际是 mvn.cmd，而 ProcessBuilder 不按 PATHEXT 解析 ——
        // 从前必须写成 mvn.cmd 才起得来。包了一层 shell 之后，这件事归 shell 管
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
