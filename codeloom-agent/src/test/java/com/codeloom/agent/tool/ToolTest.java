package com.codeloom.agent.tool;

import com.codeloom.agent.web.WebAccess;
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

    /** 构造工具上下文；所有字段都要给出，与生产唯一的构造器一致。 */
    private ToolContext context(CommandExecutor executor, Predicate<String> asksApproval) {
        // 审批判据返回理由；这里统一收成固定文本，验证理由传递的用例自行提供判据。
        return asking(executor, commandLine -> asksApproval.test(commandLine)
                ? Optional.of("测试：这条命令要人批一下")
                : Optional.empty());
    }

    /**
     * 直接接收审批理由的上下文构造。不能重载为 {@code context}：两个重载都接收 String 函数，
     * 隐式类型 lambda 会产生歧义。
     *
     * @param executor         命令执行器
     * @param requiresApproval 审批判据
     * @return 工具上下文
     */
    private ToolContext asking(CommandExecutor executor, CommandApproval requiresApproval) {
        return new ToolContext(worktree, executor, CancellationToken.none(), requiresApproval,
                new ReadLedger(), WebAccess.none());
    }

    /**
     * 记录文件已读，满足 edit_file 的观测要求。必须在文件写入之后调用，
     * 否则写入本身会让记录的版本失效。
     *
     * @param path 工作区相对路径
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
            // 100 行 × 5000 字符：行数远未到 2000，字符数已超过 10 万，验证总量上限。
            write("huge.json", ("x".repeat(5_000) + "\n").repeat(100));

            ToolOutcome outcome = new ReadFileTool().execute(context, args("""
                    {"path":"huge.json"}"""));

            assertThat(outcome.truncated()).isTrue();
            assertThat(outcome.output()).hasSizeLessThan(110_000);
            // 必须给出下一次读取的 offset。
            assertThat(outcome.output()).contains("接着读就用 offset=");
        }

        @Test
        @DisplayName("这个工具声明了自己有界，run_command 没有（见 Tool#selfBounded）")
        void declaresItselfBounded() {
            assertThat(new ReadFileTool().selfBounded()).isTrue();
            // 其他工具不应声明：run_command 的输出无法“接着跑一半”。
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
            // 只说明状态，不重复工具描述里的指导。
            assertThat(outcome.output()).contains("还没读过");
            // 文件未被修改。
            assertThat(readBack("Foo.java")).contains("void a()");
        }

        @Test
        @DisplayName("【被人改过】读完之后文件变了 → 拒绝，要求重读 —— 不然改的不是你以为的那一份")
        void refusesToEditAFileChangedSinceItWasRead() throws Exception {
            write("Foo.java", "class Foo {\n    void a() {}\n}\n");
            readFirst("Foo.java");

            // 模拟其他 agent 或用户在此期间修改文件；时间戳拨快一秒以确保 mtime 不同。
            Files.writeString(worktree.resolve("Foo.java"), "class Foo {\n    void a() {}\n}\n// 别人加的\n",
                    StandardCharsets.UTF_8);
            Files.setLastModifiedTime(worktree.resolve("Foo.java"),
                    FileTime.fromMillis(System.currentTimeMillis() + 1000));

            ToolOutcome outcome = new EditFileTool().execute(context, args("""
                    {"path":"Foo.java","old_string":"void a() {}","new_string":"void b() {}"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("改过").contains("重新读");
            // 他人的改动仍在，未被覆盖。
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
            // JSON 字符串内不能有裸换行；text block 中需写 \\n 才能得到转义序列。
            ToolOutcome outcome = new WriteFileTool().execute(context, args("""
                    {"path":"src/main/java/New.java","content":"class New {}\\n"}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(readBack("src/main/java/New.java")).isEqualTo("class New {}\n");
        }

        @Test
        @DisplayName("【关键约束】没读过的已有文件拒绝覆盖 —— 逼它先读或走 edit_file")
        void refusesToOverwriteExistingFile() {
            write("Existing.java", "class Existing {}\n");

            ToolOutcome outcome = new WriteFileTool().execute(context, args("""
                    {"path":"Existing.java","content":"class Replaced {}\\n"}"""));

            assertThat(outcome.success()).isFalse();
            // 只说明状态，不附带操作指导。
            assertThat(outcome.output()).contains("还没读过").contains("覆盖");
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
        @DisplayName("新建还是覆盖、多少行 —— 这两个事实随结果给界面（presentation）")
        void tellsTheUiWhetherItCreatedOrOverwrote() {
            ToolOutcome created = new WriteFileTool().execute(context, args("""
                    {"path":"New.java","content":"a\\nb\\n"}"""));

            assertThat(created.success()).isTrue();
            assertThat(created.presentation())
                    .contains("\"kind\":\"write\"")
                    .contains("\"created\":true")
                    .contains("\"lines\":2");

            // 再覆盖一次，行数和 created=false 都要如实反映。
            readFirst("New.java");
            ToolOutcome overwritten = new WriteFileTool().execute(context, args("""
                    {"path":"New.java","content":"c"}"""));

            assertThat(overwritten.presentation())
                    .contains("\"created\":false")
                    .contains("\"lines\":1");
        }

        @Test
        @DisplayName("【只读了一部分】整份覆盖要拦住 —— 没看到的那部分会被**静默抹掉**")
        void refusesToOverwriteAfterAPartialRead() {
            write("Big.java", "a\nb\nc\n");
            // 只读取第 2 行（offset=2、limit=1），属于部分观测。
            new ReadFileTool().execute(context, args("""
                    {"path":"Big.java","offset":2,"limit":1}"""));

            ToolOutcome outcome = new WriteFileTool().execute(context, args("""
                    {"path":"Big.java","content":"只剩这一行\\n"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("只读了它的一部分");
            // 文件内容完全未变。
            assertThat(readBack("Big.java")).isEqualTo("a\nb\nc\n");
        }

        @Test
        @DisplayName("【被人改过】读完之后文件变了 → 拒绝，要求重读 —— 不然覆盖的是别人的改动")
        void refusesToOverwriteAFileChangedSinceItWasRead() throws Exception {
            write("Shared.java", "class Shared {}\n");
            readFirst("Shared.java");

            // 模拟他人修改文件。
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

            // 紧接着修改刚写入的文件，不应被“未读过”拦住。
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

    /**
     * glob 模式语义。本类不使用 JDK 的 glob 引擎：它把 {@code **}{@code /} 当作至少一层目录，
     * 会漏掉根目录文件，也会让工具说明里的 {@code src/**}{@code /}*.java 匹配不到 {@code src/A.java}。
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
            // 点必须按字面量处理，否则 a.java 会匹配 axjava。
            assertThat(matches("a.java", "a.java")).isTrue();
            assertThat(matches("a.java", "axjava")).isFalse();
        }
    }

    // ------------------------------------------------------------------

    /**
     * “读过才许覆盖”约束及其对空文件的放行。若空文件不可读，write_file 与 edit_file
     * 都无法填充它。
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
            // 与 edit_file 一致：只说明状态，不重复工具描述里的指导。
            assertThat(outcome.output()).contains("还没读过").contains("覆盖").doesNotContain("read_file");
            // 文件未被修改。
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
            // 数组是常见笔误，必须明确说明正确形状。
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

            // 报成 INTERNAL 会让模型误以为命令写错并原样重试。
            assertThat(outcome.failure()).isEqualTo(ToolOutcome.Failure.TIMEOUT);
            // 必须说明超时时长和下一步。
            assertThat(outcome.output()).contains("600").contains("timeout_seconds");
            // 终止前的半截输出最有诊断价值。
            assertThat(outcome.output()).contains("已经下了 3 个依赖");
            // 终止前可能已改文件，不能漏掉 mutated。
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
            // 理由装在 output 中上传；审批结局没有其他内容可装（见 ToolOutcome.approvalRequired）。
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
            // 不碰工作区，因此不算改动过。
            assertThat(outcome.mutated()).isFalse();
            assertThat(outcome.todoUpdate().items()).extracting(TodoListUpdated.Item::content)
                    .containsExactly("改三个文件", "跑测试");
            assertThat(outcome.todoUpdate().items().getFirst().state())
                    .isEqualTo(TodoListUpdated.State.IN_PROGRESS);
            // 只回确认和计数；清单会由投影立即注入，无需重复。
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
            // schema 与 stateOf 位于两处，编译器无法检查一致性；该用例负责发现漂移。
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

        // 工具集合被刻意固定：新增或删除都必须显式修改此处，避免新工具静默进入每次请求。
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
