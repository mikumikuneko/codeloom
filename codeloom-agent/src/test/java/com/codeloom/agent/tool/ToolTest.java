package com.codeloom.agent.tool;

import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.event.TodoListUpdated;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.CommandTermination;
import com.codeloom.domain.port.CommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTest {

    @TempDir
    Path worktree;

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolContext context;

    @BeforeEach
    void setUp() {
        context = context((workspace, command, timeout, limit, cancellation) ->
                new CommandResult(0, "假装跑过了", false, 1), command -> false);
    }

    /**
     * 一次工具调用需要的上下文。**五个参数一样不少** —— 生产里就那么一个构造器。
     *
     * <p>图省事的活儿收在这里：测试要么给判据，要么明说它永远是 false ——
     * 而不是靠一个能少填两个字段的重载。
     */
    private ToolContext context(CommandExecutor executor, Predicate<String> asksApproval) {
        // 判据现在回来的是**理由**而不是布尔（见 CommandApproval）。这里把它收成一句固定的，
        // 免得每个调用点都写一遍 —— 要验"理由真的传下去了"的那条测试自己给判据
        return asking(executor, commandLine -> asksApproval.test(commandLine)
                ? Optional.of("测试：这条命令要人批一下")
                : Optional.empty());
    }

    /**
     * 判据直接给理由的那种。
     *
     * <p>名字不叫 {@code context} 是因为**它不能被重载**：两个重载都收"一个 String 的函数"，
     * 而 {@code command -> true} 这种隐式类型的 lambda 在它们之间选不出来（编译器报"引用不明确"）。
     */
    private ToolContext asking(CommandExecutor executor, CommandApproval requiresApproval) {
        return new ToolContext(worktree, executor, CancellationToken.none(), requiresApproval,
                new ReadLedger());
    }

    /**
     * 记一笔"这个文件读过了"。
     *
     * <p>{@code edit_file} 现在要求**改之前观测过**（见 {@link ReadLedger}），
     * 所以每条"改文件"的测试都得先过这一下 —— 真实的会话本来也是读了才改。
     *
     * <p>**必须在文件写好之后再叫它**：账上存的是那一刻的 mtime，
     * 先记账再写文件的话，写这个动作本身就会让版本对不上。
     */
    private void readFirst(String path) {
        context.reads().mark(worktree.resolve(path), true);
    }

    private JsonNode args(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String path, String content) {
        try {
            Path file = worktree.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("read_file")
    class ReadFile {

        @Test
        @DisplayName("返回带行号的内容")
        void returnsNumberedLines() {
            write("src/Foo.java", "class Foo {\n    int x;\n}\n");

            ToolOutcome outcome = new ReadFileTool().execute(context, args("""
                    {"path":"src/Foo.java"}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.output()).contains("1| class Foo {").contains("2|     int x;");
        }

        @Test
        @DisplayName("越界路径作为【失败结果】返回，不抛异常 —— 模型要能看见并自己纠正")
        void pathEscapeIsAFailureResultNotAnException() {
            ToolOutcome outcome = new ReadFileTool().execute(context, args("""
                    {"path":"../../../etc/passwd"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("越出");
        }

        @Test
        @DisplayName("文件不存在时给出下一步提示，而不是只说 not found")
        void missingFileSuggestsNextStep() {
            ToolOutcome outcome = new ReadFileTool().execute(context, args("""
                    {"path":"nope.txt"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("glob").contains("grep");
        }

        @Test
        @DisplayName("offset/limit 能读指定行范围")
        void supportsLineRange() {
            write("big.txt", "a\nb\nc\nd\ne\n");

            ToolOutcome outcome = new ReadFileTool().execute(context, args("""
                    {"path":"big.txt","offset":2,"limit":2}"""));

            assertThat(outcome.output()).contains("2| b").contains("3| c");
            assertThat(outcome.output()).doesNotContain("1| a").doesNotContain("4| d");
        }

        @Test
        @DisplayName("【真有上界】行数远没到，总量也该到顶 —— 并说清接着用 offset 从哪行读")
        void totalCharsAreBoundedToo() {
            // 100 行 × 每行 5000 字符：行数离 2000 还远，字符数早就过了 10 万。
            // 一行的大的 JSON、压过的 JS 就是这个形状 —— 只按行数设上界拦不住它
            write("huge.json", ("x".repeat(5_000) + "\n").repeat(100));

            ToolOutcome outcome = new ReadFileTool().execute(context, args("""
                    {"path":"huge.json"}"""));

            assertThat(outcome.truncated()).isTrue();
            assertThat(outcome.output()).hasSizeLessThan(110_000);
            // 只说"后面还有"没用：模型手里就有 offset，它要的是**接着从哪一行读**
            assertThat(outcome.output()).contains("接着读就用 offset=");
        }

        @Test
        @DisplayName("这个工具声明了自己有界 —— 总量闸据此放行（见 Tool#selfBounded）")
        void declaresItselfBounded() {
            assertThat(new ReadFileTool().selfBounded()).isTrue();
            // 别的工具**不该**跟着声明：声明它 = 断言"我的产出自带分段入口"，
            // 而 run_command 的输出模型没法"接着跑一半"
            assertThat(new RunCommandTool().selfBounded()).isFalse();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("edit_file")
    class EditFile {

        @Test
        @DisplayName("唯一匹配时替换成功")
        void replacesUniqueMatch() {
            write("Foo.java", "class Foo {\n    void a() {}\n}\n");
            readFirst("Foo.java");

            ToolOutcome outcome = new EditFileTool().execute(context, args("""
                    {"path":"Foo.java","old_string":"void a() {}","new_string":"void b() {}"}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(readBack("Foo.java")).contains("void b() {}").doesNotContain("void a()");
        }

        @Test
        @DisplayName("【关键】匹配不到时，提示里必须包含『重新读取文件』—— 这是分叉后的自愈入口")
        void noMatchTellsTheModelToReRead() {
            write("Foo.java", "class Foo {\n    void changed() {}\n}\n");
            readFirst("Foo.java");

            ToolOutcome outcome = new EditFileTool().execute(context, args("""
                    {"path":"Foo.java","old_string":"void a() {}","new_string":"void b() {}"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output())
                    .contains("没有找到")
                    .contains("其他会话")     // 告诉它可能是别人的改动
                    .contains("read_file");   // 告诉它该怎么做
        }

        @Test
        @DisplayName("匹配到多处时拒绝执行，要求补充上下文使其唯一")
        void ambiguousMatchIsRejected() {
            write("Foo.java", "int x = 1;\nint x = 1;\n");
            readFirst("Foo.java");

            ToolOutcome outcome = new EditFileTool().execute(context, args("""
                    {"path":"Foo.java","old_string":"int x = 1;","new_string":"int x = 2;"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("2").contains("唯一");
            assertThat(readBack("Foo.java")).isEqualTo("int x = 1;\nint x = 1;\n");   // 文件没被动过
        }

        @Test
        @DisplayName("【没读过就不许改】拿不到「它原来是什么」，就无从判断改的是哪一份")
        void refusesToEditAFileItNeverRead() {
            write("Foo.java", "class Foo {\n    void a() {}\n}\n");

            ToolOutcome outcome = new EditFileTool().execute(context, args("""
                    {"path":"Foo.java","old_string":"void a() {}","new_string":"void b() {}"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("read_file");
            // 一个字都没写进去
            assertThat(readBack("Foo.java")).contains("void a()");
        }

        @Test
        @DisplayName("【被人改过】读完之后文件变了 → 拒绝，要求重读 —— 不然改的不是你以为的那一份")
        void refusesToEditAFileChangedSinceItWasRead() throws Exception {
            write("Foo.java", "class Foo {\n    void a() {}\n}\n");
            readFirst("Foo.java");

            // 别人（另一个 agent、或者人自己）在这个空档里动了它。
            // 时间戳往前拨一秒：同一毫秒内写完的话，mtime 可能和账上记的一样
            Files.writeString(worktree.resolve("Foo.java"), "class Foo {\n    void a() {}\n}\n// 别人加的\n",
                    StandardCharsets.UTF_8);
            Files.setLastModifiedTime(worktree.resolve("Foo.java"),
                    FileTime.fromMillis(System.currentTimeMillis() + 1000));

            ToolOutcome outcome = new EditFileTool().execute(context, args("""
                    {"path":"Foo.java","old_string":"void a() {}","new_string":"void b() {}"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("改过").contains("重新读");
            // 别人的那一行还在 —— 没有被覆盖掉
            assertThat(readBack("Foo.java")).contains("别人加的");
        }

        @Test
        @DisplayName("越界路径被拒绝")
        void refusesEscape() {
            ToolOutcome outcome = new EditFileTool().execute(context, args("""
                    {"path":"../outside.txt","old_string":"a","new_string":"b"}"""));

            assertThat(outcome.success()).isFalse();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("write_file")
    class WriteFile {

        @Test
        @DisplayName("能新建文件，并自动建父目录")
        void createsFileAndParentDirectories() {
            // 注意 \\n：text block 里写 \n 会被 Java 编译器变成【真实换行】，
            // 而 JSON 字符串里不允许有裸换行。要 JSON 收到转义序列，得写两个反斜杠。
            ToolOutcome outcome = new WriteFileTool().execute(context, args("""
                    {"path":"src/main/java/New.java","content":"class New {}\\n"}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(readBack("src/main/java/New.java")).isEqualTo("class New {}\n");
        }

        @Test
        @DisplayName("【关键约束】文件已存在时拒绝 —— 逼它走 edit_file，否则整文件覆盖的保护全被绕过")
        void refusesToOverwriteExistingFile() {
            write("Existing.java", "class Existing {}\n");

            ToolOutcome outcome = new WriteFileTool().execute(context, args("""
                    {"path":"Existing.java","content":"class Replaced {}\\n"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("edit_file");
            assertThat(readBack("Existing.java")).isEqualTo("class Existing {}\n");   // 原文件完好
        }

        @Test
        @DisplayName("读过了就许覆盖 —— 账是跨轮的，记的是「这条对话读过」")
        void overwritesAFileThisConversationHasRead() {
            write("Existing.java", "class Existing {}\n");
            readFirst("Existing.java");

            ToolOutcome outcome = new WriteFileTool().execute(context, args("""
                    {"path":"Existing.java","content":"class Replaced {}\\n"}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(readBack("Existing.java")).isEqualTo("class Replaced {}\n");
        }

        @Test
        @DisplayName("【只读了一部分】整份覆盖要拦住 —— 没看到的那部分会被**静默抹掉**")
        void refusesToOverwriteAfterAPartialRead() {
            write("Big.java", "a\nb\nc\n");
            // 只读了第 2 行（offset=2&limit=1）—— 那是一次**部分**观测
            new ReadFileTool().execute(context, args("""
                    {"path":"Big.java","offset":2,"limit":1}"""));

            ToolOutcome outcome = new WriteFileTool().execute(context, args("""
                    {"path":"Big.java","content":"只剩这一行\\n"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("只读了它的一部分");
            // 一个字节都没被抹掉
            assertThat(readBack("Big.java")).isEqualTo("a\nb\nc\n");
        }

        @Test
        @DisplayName("【被人改过】读完之后文件变了 → 拒绝，要求重读 —— 不然覆盖的是别人的改动")
        void refusesToOverwriteAFileChangedSinceItWasRead() throws Exception {
            write("Shared.java", "class Shared {}\n");
            readFirst("Shared.java");

            // 另一个人的 agent（或者人自己）在这个空档里动了它
            Files.writeString(worktree.resolve("Shared.java"), "class Shared {}\n// 他加的\n",
                    StandardCharsets.UTF_8);
            Files.setLastModifiedTime(worktree.resolve("Shared.java"),
                    FileTime.fromMillis(System.currentTimeMillis() + 1000));

            ToolOutcome outcome = new WriteFileTool().execute(context, args("""
                    {"path":"Shared.java","content":"class Mine {}\\n"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("改过").contains("重新读");
            assertThat(readBack("Shared.java")).contains("他加的");
        }

        @Test
        @DisplayName("【写完紧接再改】写完要把账刷新 —— 不然它会被自己刚写的东西挡住")
        void aFreshWriteCountsAsAnObservation() {
            ToolOutcome created = new WriteFileTool().execute(context, args("""
                    {"path":"New.java","content":"class New {\\n    int x = 1;\\n}\\n"}"""));

            assertThat(created.success()).isTrue();

            // 紧接着改它 —— 模型刚亲手写的那份，它当然"知道"内容
            ToolOutcome edited = new EditFileTool().execute(context, args("""
                    {"path":"New.java","old_string":"int x = 1;","new_string":"int x = 2;"}"""));

            assertThat(edited.success()).isTrue();
            assertThat(readBack("New.java")).contains("int x = 2;");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("grep 与 glob")
    class Search {

        @BeforeEach
        void seed() {
            write("src/OrderService.java", "public class OrderService {\n    // TODO 幂等\n}\n");
            write("src/UserService.java", "public class UserService {}\n");
            write("target/OrderService.class", "TODO 这是构建产物，不该被搜到\n");
        }

        @Test
        @DisplayName("grep 按正则找内容，输出 文件:行号:内容")
        void grepFindsMatches() {
            ToolOutcome outcome = new GrepTool().execute(context, args("""
                    {"pattern":"class \\\\w+Service"}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.output())
                    .contains("src/OrderService.java:1:")
                    .contains("src/UserService.java:1:");
        }

        @Test
        @DisplayName("grep 跳过 target/ 这类构建产物，否则结果会被淹没")
        void grepSkipsBuildOutput() {
            ToolOutcome outcome = new GrepTool().execute(context, args("""
                    {"pattern":"TODO"}"""));

            assertThat(outcome.output()).contains("OrderService.java").doesNotContain("target/");
        }

        @Test
        @DisplayName("grep 的 include 能限定文件类型")
        void grepHonoursIncludeFilter() {
            ToolOutcome outcome = new GrepTool().execute(context, args("""
                    {"pattern":"class","include":"*.java"}"""));

            assertThat(outcome.output()).contains(".java:");
        }

        @Test
        @DisplayName("glob 按模式列路径")
        void globListsPaths() {
            ToolOutcome outcome = new GlobTool().execute(context, args("""
                    {"pattern":"**/*.java"}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.output())
                    .contains("src/OrderService.java")
                    .contains("src/UserService.java")
                    .doesNotContain("target/");
        }

        @Test
        @DisplayName("正则不合法时给出说明，而不是抛异常")
        void invalidRegexIsAFailureResult() {
            ToolOutcome outcome = new GrepTool().execute(context, args("""
                    {"pattern":"[unclosed"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("不合法");
        }
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    /**
     * glob 模式的语义。
     *
     * <p>这一组盯的是**换掉 JDK 那个 glob 引擎**这件事。JDK 把 {@code **&#47;} 当成
     * "至少一层目录"，于是 {@code **&#47;*.java} 漏掉根目录的文件、{@code src/**&#47;*.java}
     * 一条都匹配不到 —— 而后者是我们自己写在工具 description 里的例子。一次真实会话里
     * agent 刚建好文件，glob 说"没有匹配的"，它以为自己建的东西不见了。
     */
    @Nested
    @DisplayName("glob 模式")
    class GlobPattern {

        private boolean matches(String pattern, String path) {
            return GlobTool.compile(pattern).matcher(path).matches();
        }

        @Test
        @DisplayName("`**/` 是**零层或更多** —— 根目录下的文件必须命中")
        void doubleStarSlashMatchesZeroDirectories() {
            assertThat(matches("**/*.java", "helloworld.java")).as("根目录的文件").isTrue();
            assertThat(matches("**/*", "helloworld.java")).isTrue();
            assertThat(matches("**/*.java", "src/A.java")).as("子目录的也要命中").isTrue();
        }

        @Test
        @DisplayName("`src/**/*.java` 命中 src 底下的，**一层也算**")
        void doubleStarInTheMiddleMatchesOneLevelToo() {
            assertThat(matches("src/**/*.java", "src/A.java")).as("就一层").isTrue();
            assertThat(matches("src/**/*.java", "src/a/b/A.java")).as("好几层").isTrue();
            assertThat(matches("src/**/*.java", "other/A.java")).as("不在 src 底下的不该命中").isFalse();
        }

        @Test
        @DisplayName("单个 `*` **不跨目录** —— 这是标准语义，别学 JDK 那个")
        void singleStarDoesNotCrossDirectories() {
            assertThat(matches("*.java", "helloworld.java")).isTrue();
            assertThat(matches("*.java", "src/A.java")).isFalse();
            assertThat(matches("src/*.java", "src/A.java")).isTrue();
            assertThat(matches("src/*.java", "src/a/A.java")).isFalse();
        }

        @Test
        @DisplayName("模式里的点在正则里不是通配符 —— 字面量就是字面量")
        void dotsAreLiteral() {
            // 翻成正则时最容易漏的就是这一条：`a.java` 里的 `.` 不转义的话，
            // 它会匹配任意字符，于是 `axjava` 也会命中 —— 而模型会因此以为那个文件存在
            assertThat(matches("a.java", "a.java")).isTrue();
            assertThat(matches("a.java", "axjava")).isFalse();
        }
    }

    // ------------------------------------------------------------------

    /**
     * 「读过才许覆盖」这条约束，以及它必须放行的那个空文件。
     *
     * <p>这一组对应一次真实会话：agent 想往平台种下的那个空 {@code README.md} 里写点东西，
     * 三步全被挡回来（{@code write_file} 说已存在、{@code edit_file} 要非空唯一的
     * {@code old_string}、而 {@code read_file} 读空文件直接报错），最后放弃了。
     */
    @Nested
    @DisplayName("覆盖与空文件")
    class Overwrite {

        @Test
        @DisplayName("空文件读得动 —— 这是覆盖那道门的前提")
        void anEmptyFileCanBeRead() throws IOException {
            Files.createFile(worktree.resolve("README.md"));

            ToolOutcome outcome = new ReadFileTool().execute(context, args("{\"path\":\"README.md\"}"));

            assertThat(outcome.success()).as("空文件是一次成功的读取，不是失败").isTrue();
            assertThat(outcome.output()).contains("空的");
        }

        @Test
        @DisplayName("没读过就不许覆盖 —— 那道门防的是盲改你没看过的东西")
        void overwritingAnUnreadFileIsRefused() throws IOException {
            Files.writeString(worktree.resolve("已有.txt"), "原来的内容");

            ToolOutcome outcome = new WriteFileTool().execute(context,
                    args("{\"path\":\"已有.txt\",\"content\":\"新的\"}"));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("还没读过").contains("read_file");
            // 而且**一个字都没动**
            assertThat(Files.readString(worktree.resolve("已有.txt"))).isEqualTo("原来的内容");
        }

        @Test
        @DisplayName("读过了就能覆盖 —— 用户那个场景：空的 README 终于写得进去")
        void readingFirstUnlocksOverwriting() throws IOException {
            Files.createFile(worktree.resolve("README.md"));

            assertThat(new ReadFileTool().execute(context, args("{\"path\":\"README.md\"}")).success())
                    .as("先读").isTrue();
            ToolOutcome written = new WriteFileTool().execute(context,
                    args("{\"path\":\"README.md\",\"content\":\"# 项目说明\"}"));

            assertThat(written.success()).as("%s", written.output()).isTrue();
            assertThat(Files.readString(worktree.resolve("README.md"))).isEqualTo("# 项目说明");
        }

        @Test
        @DisplayName("新建仍然不需要先读 —— 那个文件本来就不存在")
        void creatingANewFileNeedsNoRead() {
            assertThat(new WriteFileTool().execute(context,
                    args("{\"path\":\"新文件.txt\",\"content\":\"x\"}")).success()).isTrue();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("run_command")
    class RunCommand {

        @Test
        @DisplayName("整行命令能正常执行")
        void runsAStringCommand() {
            ToolOutcome outcome = new RunCommandTool().execute(context, args("""
                    {"command":"mvn -q test"}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.output()).contains("假装跑过了");
        }

        @Test
        @DisplayName("数组形式被拒 —— 这里收的是整行，不是分词好的参数")
        void rejectsArrayCommand() {
            // 模型很容易顺手写成数组（旧的形状就是数组）。说清正确形状，
            // 别让它自己去猜（它会猜成"这个工具的 run_command 是坏的"）
            ToolOutcome outcome = new RunCommandTool().execute(context, args("""
                    {"command":["mvn","-q","test"]}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("字符串");
        }

        @Test
        @DisplayName("空命令被拒")
        void rejectsBlankCommand() {
            ToolOutcome outcome = new RunCommandTool().execute(context, args("""
                    {"command":"   "}"""));

            assertThat(outcome.success()).isFalse();
        }

        @Test
        @DisplayName("执行器抛出的异常（没有 shell、命令起不来）变成失败结果，不是崩溃")
        void executorFailureBecomesAResult() {
            ToolContext failing = context((ws, cmd, timeout, limit, cancel) -> {
                throw new IllegalStateException("这台机器上没有找到可用的 shell（bash）");
            }, command -> false);

            ToolOutcome outcome = new RunCommandTool().execute(failing, args("""
                    {"command":"git --version"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("shell");
        }

        @Test
        @DisplayName("超时被杀：报成【超时】而不是内部错误，而且**半截输出照样给模型**")
        void timeoutIsItsOwnFailureAndKeepsPartialOutput() {
            ToolContext timingOut = context((ws, cmd, timeout, limit, cancel) ->
                    new CommandResult(null, "已经下了 3 个依赖……", false, 900, CommandTermination.TIMED_OUT),
                    command -> false);

            ToolOutcome outcome = new RunCommandTool().execute(timingOut, args("""
                    {"command":"mvn -q test","timeout_seconds":600}"""));

            // 报成 INTERNAL 的话，模型会以为命令写错了，于是原样再跑一遍 —— 再超时一次
            assertThat(outcome.failure()).isEqualTo(ToolOutcome.Failure.TIMEOUT);
            // 说清停了多久，并给出下一步；不然它会去猜自己哪里写错了
            assertThat(outcome.output()).contains("600").contains("timeout_seconds");
            // ★ 被杀掉之前的那半截才是最有用的东西
            assertThat(outcome.output()).contains("已经下了 3 个依赖");
            // 被杀掉之前它已经跑了一会儿，完全可能改过文件 —— 所以不许把这次改动漏掉
            assertThat(outcome.mutated()).isTrue();
        }

        @Test
        @DisplayName("取消被杀：是【用户意图】，不是工具失败")
        void cancellationIsNotAFailure() {
            ToolContext cancelled = context((ws, cmd, timeout, limit, cancel) ->
                    new CommandResult(null, "", false, 300, CommandTermination.CANCELLED),
                    command -> false);

            ToolOutcome outcome = new RunCommandTool().execute(cancelled, args("""
                    {"command":"mvn -q test"}"""));

            assertThat(outcome.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("判据说要问人时，这条调用**不执行**、挂起等人答复 —— 而且不是失败")
        void approvalRequiredSuspendsWithoutRunning() {
            List<String> ran = new ArrayList<>();
            ToolContext asking = context((ws, cmd, timeout, limit, cancel) -> {
                ran.add(cmd);
                return new CommandResult(0, "不该跑到这里", false, 1);
            }, command -> true);

            ToolOutcome outcome = new RunCommandTool().execute(asking, args("""
                    {"command":"curl https://example.com"}"""));

            assertThat(outcome.needsApproval()).isTrue();
            assertThat(outcome.success()).isFalse();
            assertThat(ran).as("还没执行").isEmpty();
        }

        @Test
        @DisplayName("【问人的理由】判据说的那句话要原样带出去 —— 人要看着它点同意")
        void theReasonToAskIsCarriedOut() {
            // 理由装在 output 里往上传：等着人批这个结局没有别的内容可装
            // （这一轮不落 ToolResult，模型也看不到它）—— 见 ToolOutcome.approvalRequired
            ToolContext askingContext = asking(
                    (ws, cmd, timeout, limit, cancel) -> new CommandResult(0, "", false, 1),
                    commandLine -> Optional.of("`curl` 不在免审批的程序名单里，先请你过一眼。"));

            ToolOutcome outcome = new RunCommandTool().execute(askingContext, args("""
                    {"command":"curl https://example.com"}"""));

            assertThat(outcome.needsApproval()).isTrue();
            assertThat(outcome.output())
                    .contains("curl")
                    .contains("不在免审批");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("todo_write")
    class TodoWrite {

        @Test
        @DisplayName("写出清单，并把**一条事实**带回去 —— 调用方据此落事件")
        void writesTheListAndHandsBackTheFact() {
            ToolOutcome outcome = new TodoWriteTool().execute(context, args("""
                    {"todos":[{"content":"改三个文件","state":"in_progress"},
                              {"content":"跑测试","state":"pending"}]}"""));

            assertThat(outcome.success()).isTrue();
            // 它不碰工作区，所以不算"改动过" —— 左边那棵树不该因为写了一次计划就重拉
            assertThat(outcome.mutated()).isFalse();
            assertThat(outcome.todoUpdate().items()).extracting(TodoListUpdated.Item::content)
                    .containsExactly("改三个文件", "跑测试");
            assertThat(outcome.todoUpdate().items().getFirst().state())
                    .isEqualTo(TodoListUpdated.State.IN_PROGRESS);
            // 回给模型的是一句确认 + 数，不是整份清单：清单马上就会摆在它眼前
            // （见 ContextAssembler 的注入），在这儿再抄一遍是白烧 token
            assertThat(outcome.output()).contains("1 进行中").contains("1 待处理");
        }

        @Test
        @DisplayName("空数组 = 清空，而且这**不是**失败")
        void anEmptyListClears() {
            ToolOutcome outcome = new TodoWriteTool().execute(context, args("""
                    {"todos":[]}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.todoUpdate().items()).isEmpty();
            assertThat(outcome.output()).contains("已清空");
        }

        @Test
        @DisplayName("内容空白的任务不收 —— 那种条目在界面上就是一行空白")
        void blankContentIsRejected() {
            ToolOutcome outcome = new TodoWriteTool().execute(context, args("""
                    {"todos":[{"content":"   ","state":"pending"}]}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.todoUpdate()).isNull();
        }

        @Test
        @DisplayName("三个状态字符串和领域里那三个枚举一一对上")
        void statesMapOneToOne() {
            ToolOutcome outcome = new TodoWriteTool().execute(context, args("""
                    {"todos":[{"content":"a","state":"pending"},
                              {"content":"b","state":"in_progress"},
                              {"content":"c","state":"completed"}]}"""));

            assertThat(outcome.todoUpdate().items())
                    .extracting(TodoListUpdated.Item::state)
                    .containsExactly(TodoListUpdated.State.PENDING,
                            TodoListUpdated.State.IN_PROGRESS,
                            TodoListUpdated.State.COMPLETED);
        }

        @Test
        @DisplayName("schema 里声明的每一个 state 都真的收 —— 声明的和收下的不能是两份")
        void everyDeclaredStateIsActuallyAccepted() throws Exception {
            // "参数长什么样"由 schema 说了算，而"这个字符串翻成哪个领域状态"由
            // stateOf 说了算 —— 两处在两个文件里，编译器管不到它们对不对得上。
            // 这条测试就是那根线：schema 里加一个值而 stateOf 没跟上，它当场就红
            JsonNode declared = mapper.readTree(new TodoWriteTool().parametersJsonSchema())
                    .path("properties").path("todos").path("items")
                    .path("properties").path("state").path("enum");

            assertThat(declared).isNotEmpty();
            for (JsonNode value : declared) {
                ToolOutcome outcome = new TodoWriteTool().execute(context, args(
                        "{\"todos\":[{\"content\":\"做点什么\",\"state\":\""
                                + value.asText() + "\"}]}"));

                assertThat(outcome.success())
                        .as("schema 声明了 state = %s，工具却不收", value.asText())
                        .isTrue();
            }
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("注册表：七个标准工具，名字无重复，定义能被构造出来")
    void registryExposesStandardTools() {
        ToolRegistry registry = ToolRegistry.standard();

        // 这一条是**故意钉死**的：多一个少一个都要有人来改这里，
        // 而不是让一个新工具悄悄跟着每次请求发给模型（那也是每次请求都要付的 token）
        assertThat(registry.names()).containsExactlyInAnyOrder(
                "read_file", "edit_file", "write_file", "grep", "glob", "run_command", "todo_write");
        assertThat(registry.definitions()).hasSize(7);
        assertThat(registry.find("read_file")).isPresent();
        assertThat(registry.find("nope")).isEmpty();
    }

    private String readBack(String path) {
        try {
            return Files.readString(worktree.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
