package com.codeloom.workspace.git;

import com.codeloom.domain.port.CommitIdentity;
import com.codeloom.domain.workspace.FileDiff;
import com.codeloom.domain.port.MergeResult;
import com.codeloom.workspace.support.TestGit;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用真 git 和真实临时目录验证仓库操作，不需要 Spring 或数据库。
 *
 * <p>标注"实验回归"的用例各自固定一个已知的回归场景。
 */
class GitClientTest {

    @TempDir
    Path tempDir;

    private GitClient git;
    private Path repo;

    private final CommitIdentity alice = CommitIdentity.of("alice");
    private final CommitIdentity bob = CommitIdentity.of("bob");

    @BeforeEach
    void setUp() {
        git = new GitClient("git", tempDir.resolve("sandbox"));
        repo = tempDir.resolve("repo");
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("取某个文件的改动")
    class DiffOfOneFile {

        @BeforeEach
        void initWithBaseCommit() {
            git.initRepo(repo, alice);
        }

        private String commit(String message) {
            return git.commitAll(repo, message, alice);
        }

        @Test
        @DisplayName("【真跑】给的是那个文件在这两步之间的改动")
        void showsWhatThatFileDidInThatStep() throws IOException {
            Files.writeString(repo.resolve("a.txt"), "第一行\n", StandardCharsets.UTF_8);
            commit("base");

            Files.writeString(repo.resolve("a.txt"), "第一行\n第二行\n", StandardCharsets.UTF_8);
            String sha = commit("加一行");

            FileDiff diff = git.diffOfFile(repo, sha, "a.txt", 100_000);

            assertThat(diff.text()).contains("+第二行");
            assertThat(diff.text()).doesNotContain("+第一行");
            assertThat(diff.truncated()).isFalse();
        }

        @Test
        @DisplayName("按提交看一个文件时带整份上下文 —— 改动之前的行也在")
        void showsTheWholeFileAroundTheChange() throws IOException {
            String original = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 20)
                    .mapToObj(i -> "line " + i)
                    .toList()) + "\n";
            Files.writeString(repo.resolve("a.txt"), original, StandardCharsets.UTF_8);
            commit("base");
            Files.writeString(repo.resolve("a.txt"), original.replace("line 20", "changed"),
                    StandardCharsets.UTF_8);
            String sha = commit("改最后一行");

            FileDiff diff = git.diffOfFile(repo, sha, "a.txt", 100_000);

            // 默认的三行上下文只会从第 17 行开始；整份文件必须从第 1 行就在
            assertThat(diff.text()).contains(" line 1").contains("+changed");
        }

        @Test
        @DisplayName("【安全约束】本地配置里的外部 diff 程序不会被执行")
        void externalDiffConfiguredInTheRepoIsIgnored() throws IOException {
            Files.writeString(repo.resolve("a.txt"), "第一行\n", StandardCharsets.UTF_8);
            commit("base");
            Files.writeString(repo.resolve("a.txt"), "第一行\n第二行\n", StandardCharsets.UTF_8);
            String sha = commit("加一行");

            // 设成一个必然失败的命令：少了 --no-ext-diff，git 会执行它并让整条命令失败
            TestGit.run(repo, "config", "diff.external", "false");

            assertThat(git.diffOfFile(repo, sha, "a.txt", 100_000).text()).contains("+第二行");
        }

        @Test
        @DisplayName("正文太大就截断并**如实标记**，不报错 —— 截一段仍然有用")
        void aHugeDiffIsTruncatedRatherThanRefused() throws IOException {
            // 基点提交已由 initRepo 打出，这里不再补空提交
            Files.writeString(repo.resolve("big.txt"), "x".repeat(2_000) + "\n",
                    StandardCharsets.UTF_8);
            String sha = commit("加一个大文件");

            FileDiff diff = git.diffOfFile(repo, sha, "big.txt", 500);

            assertThat(diff.truncated()).isTrue();
            assertThat(diff.text()).hasSize(500);
        }

        @Test
        @DisplayName("提交取不出来 → 报错，而不是给一段空的")
        void anUnresolvableCommitIsAnError() {
            assertThatThrownBy(() -> git.diffOfFile(repo,
                    "0000000000000000000000000000000000000000", "a.txt", 10_000))
                    .isInstanceOf(GitCommandException.class);
        }

        @Test
        @DisplayName("那个路径这一步没动过 → 空的正文。**git 不把这当错**")
        void aPathThatDidNotChangeGivesAnEmptyDiff() throws IOException {
            // 路径对不上时 git 退出码为 0、输出为空，因此"取不到"和"没改过"在存储层
            // 无法区分；该区分只能由调用方依据事件里记录的路径保证（见 ProjectDiff）
            Files.writeString(repo.resolve("a.txt"), "第一行\n", StandardCharsets.UTF_8);
            commit("base");
            Files.writeString(repo.resolve("a.txt"), "改了\n", StandardCharsets.UTF_8);
            String sha = commit("改一行");

            assertThat(git.diffOfFile(repo, sha, "nope.txt", 10_000).text()).isEmpty();
        }
    }

    @Nested
    @DisplayName("基点与仓库初始化")
    class BaseCommit {

        @Test
        @DisplayName("initRepo 建出 main 分支，并且带一个空提交作基点")
        void createsMainWithBaseCommit() throws Exception {
            git.initRepo(repo, alice);

            assertThat(TestGit.run(repo, "branch", "--list", "main")).contains("main");
            assertThat(git.countCommits(repo, "main")).isEqualTo(1);
        }

        @Test
        @DisplayName("initRepo 把**已经躺在目录里**的东西一并打进基点，而不是留成未跟踪")
        void baseCommitIncludesWhatIsAlreadyInTheDirectory() throws IOException {
            // 调用方会先放好新项目该有的内容（untitled/ 的占位文件）。它们必须进入基点
            // 提交：两棵树从基点 checkout，未跟踪文件不会被带过去，而它在主仓库磁盘上
            // 又确实存在，这种"看得见目录、树里没有"最难排查
            Path seed = repo.resolve("untitled");
            Files.createDirectories(seed);
            writeFile(seed, "README.md", "");
            git.initRepo(repo, alice);

            assertThat(git.countCommits(repo, "main")).isEqualTo(1);   // 在基点里，不是补的第二笔
            assertThat(newSession("A").resolve("untitled/README.md")).exists();
        }

        @Test
        @DisplayName("【实验回归】有共同基点，两条会话的分支才能合并")
        void sessionsShareBaseAndCanMerge() {
            git.initRepo(repo, alice);
            Path a = newSession("A");
            Path b = newSession("B");

            writeFile(a, "from-a.txt", "A 的产出\n");
            git.commitAll(a, "A: 加文件", alice);
            writeFile(b, "from-b.txt", "B 的产出\n");
            git.commitAll(b, "B: 加文件", bob);

            // A 合进主干（主干还停在基点 → 快进）
            assertThat(git.merge(repo, "session/A", alice).status())
                    .isEqualTo(MergeResult.Status.FAST_FORWARD);

            // B 同步主干：两边动的是不同文件，应当是干净的合并提交
            MergeResult result = git.merge(b, "main", bob);
            assertThat(result.status()).isEqualTo(MergeResult.Status.MERGED);
            assertThat(result.conflictingPaths()).isEmpty();

            // 合并后 B 既有自己的产出，也有 A 的
            assertThat(b.resolve("from-a.txt")).exists();
            assertThat(b.resolve("from-b.txt")).exists();
        }

        @Test
        @DisplayName("initRepo 之后，分支和 ref 都真的落在 git 里 —— 这句话问 git 自己")
        void refsExistAfterInit() throws Exception {
            git.initRepo(repo, alice);

            // 直接问 git：用 GitClient 自己查询等于用被测代码断言被测代码
            assertThat(TestGit.run(repo, "rev-parse", "-q", "--verify", "main^{commit}")).isNotEmpty();
            assertThat(TestGit.output(repo, "rev-parse", "-q", "--verify", "session/does-not-exist^{commit}")).isEmpty();
            assertThat(TestGit.output(repo, "branch", "--list", "session/does-not-exist")).isEmpty();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("worktree 隔离")
    class Isolation {

        @Test
        @DisplayName("两条会话在各自 worktree 里改同一个文件，彼此看不见 —— 这就是零冲突的来源")
        void sessionsAreIsolated() {
            git.initRepo(repo, alice);
            Path a = newSession("A");
            Path b = newSession("B");

            writeFile(a, "OrderService.java", "A 的版本\n");
            git.commitAll(a, "A: 实现 create", alice);
            writeFile(b, "OrderService.java", "B 的版本\n");
            git.commitAll(b, "B: 实现 create", bob);

            assertThat(read(a, "OrderService.java")).isEqualTo("A 的版本\n");
            assertThat(read(b, "OrderService.java")).isEqualTo("B 的版本\n");
            // 主干不受影响
            assertThat(repo.resolve("OrderService.java")).doesNotExist();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("合并与冲突")
    class Conflicts {

        @Test
        @DisplayName("【实验回归】冲突返回 CONFLICT 而不是抛异常 —— 冲突是正常业务路径")
        void conflictIsAResultNotAnException() {
            MergeResult conflict = setUpConflictingSessions();

            assertThat(conflict.status()).isEqualTo(MergeResult.Status.CONFLICT);
            assertThat(conflict.conflictingPaths()).containsExactly("OrderService.java");
            assertThat(git.mergeInProgress(sessionPath("B"))).isTrue();
        }

        @Test
        @DisplayName("冲突双方的内容能从索引里独立取出（前端并排 diff 用这个，不解析文本）")
        void bothSidesAreReadableFromIndex() {
            setUpConflictingSessions();
            Path b = sessionPath("B");

            assertThat(git.conflictSide(b, 2, "OrderService.java")).isEqualTo("B 的版本\n");
            assertThat(git.conflictSide(b, 3, "OrderService.java")).isEqualTo("A 的版本\n");
        }

        @Test
        @DisplayName("按文件粒度二选一，然后收尾提交 —— MVP 的冲突裁决方式")
        void resolveByChoosingOneSide() throws Exception {
            setUpConflictingSessions();
            Path b = sessionPath("B");

            git.resolveConflictByChoosingSide(b, "OrderService.java", true);
            git.commitStaged(b, "解决冲突：保留我方", bob);

            assertThat(git.mergeInProgress(b)).isFalse();
            assertThat(read(b, "OrderService.java")).isEqualTo("B 的版本\n");
            assertThat(TestGit.run(b, "status", "--porcelain")).isEmpty();
        }

        @Test
        @DisplayName("mergeAbort 把工作区恢复到合并前，不留痕迹")
        void abortRestoresCleanState() throws Exception {
            setUpConflictingSessions();
            Path b = sessionPath("B");

            git.mergeAbort(b);

            assertThat(git.mergeInProgress(b)).isFalse();
            assertThat(TestGit.run(b, "status", "--porcelain")).isEmpty();
            assertThat(read(b, "OrderService.java")).isEqualTo("B 的版本\n");
        }

        @Test
        @DisplayName("【大文件】冲突的一方大到超过输出上限时**报错**，而不是交出一份被截断的内容")
        void anOversizedConflictSideFailsInsteadOfShowingHalfAFile() {
            // 4M 字符对改十万个文件够宽，但对一个被冲突的大文件（锁文件、打包产物、
            // 生成的 JSON）不够；交出一份截断内容让人裁决而无人报错，是最坏的行为
            git.initRepo(repo, alice);
            Path a = newSession("A");
            Path b = newSession("B");
            String huge = "x".repeat(4 * 1024 * 1024 + 1);

            writeFile(a, "big.lock", huge + "\nA 的版本\n");
            git.commitAll(a, "A: 写大文件", alice);
            writeFile(b, "big.lock", huge + "\nB 的版本\n");
            git.commitAll(b, "B: 写大文件", bob);
            git.merge(repo, "session/A", alice);
            assertThat(git.merge(b, "main", bob).status()).isEqualTo(MergeResult.Status.CONFLICT);

            assertThatThrownBy(() -> git.conflictSide(b, 2, "big.lock"))
                    .isInstanceOf(GitCommandException.class)
                    .hasMessageContaining("截断");
        }

        @Test
        @DisplayName("主干预会话已一致时返回 UP_TO_DATE，而不是造一个空的合并提交")
        void alreadyUpToDateIsDetected() {
            git.initRepo(repo, alice);
            Path a = newSession("A");

            MergeResult result = git.merge(a, "main", alice);

            assertThat(result.status()).isEqualTo(MergeResult.Status.UP_TO_DATE);
        }

        /** 造出两条会话、改同一个文件、A 先进主干、B 同步时冲突的局面。 */
        private MergeResult setUpConflictingSessions() {
            git.initRepo(repo, alice);
            Path a = newSession("A");
            Path b = newSession("B");

            writeFile(a, "OrderService.java", "A 的版本\n");
            git.commitAll(a, "A: 实现 create", alice);
            writeFile(b, "OrderService.java", "B 的版本\n");
            git.commitAll(b, "B: 实现 create", bob);

            git.merge(repo, "session/A", alice);
            return git.merge(b, "main", bob);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("可审计性")
    class Auditability {

        @Test
        @DisplayName("每笔提交都带着会话所有者的署名 —— 一眼看出代码是谁的 agent 写的")
        void commitsCarryTheirAuthor() {
            git.initRepo(repo, alice);
            Path a = newSession("A");
            Path b = newSession("B");

            writeFile(a, "a.txt", "a\n");
            git.commitAll(a, "alice 的改动", alice);
            writeFile(b, "b.txt", "b\n");
            git.commitAll(b, "bob 的改动", bob);

            String log = git.run(repo, List.of("log", "--all", "--format=%an %s")).stdout();

            assertThat(log).contains("alice alice 的改动");
            assertThat(log).contains("bob bob 的改动");
        }

        @Test
        @DisplayName("没有改动时提交不报错，返回当前 HEAD")
        void commitWithoutChangesIsNotAnError() {
            git.initRepo(repo, alice);
            Path a = newSession("A");
            String before = git.revParse(a, "HEAD");

            String after = git.commitAll(a, "什么都没改", alice);

            assertThat(after).isEqualTo(before);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("换行符")
    class LineEndings {

        @Test
        @DisplayName("【实验回归】LF 不会被换成 CRLF —— 否则 diff 会显示成全文改动")
        void lfIsNotConvertedToCrlf() throws Exception {
            git.initRepo(repo, alice);
            Path a = newSession("A");
            writeFile(a, "line.txt", "line1\nline2\n");
            git.commitAll(a, "test", alice);

            // 强制重新 checkout：如果 autocrlf 开着，这一步就会写出 CRLF
            git.resetHard(a, git.revParse(a, "HEAD"));

            assertThat(read(a, "line.txt")).isEqualTo("line1\nline2\n");
            assertThat(TestGit.run(a, "status", "--porcelain")).isEmpty();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("数落后多少个提交")
    class CommitCounting {

        @Test
        @DisplayName("能数出一段区间里有几个提交 —— 「先同步再合」那道顺序靠它守门")
        void countsCommitsInARange() {
            // 它支撑合并前"是否落后主干"的检查：少算会放行本可避免的冲突，
            // 多算会让人无法合并
            git.initRepo(repo, alice);
            Path a = newSession("A");
            writeFile(a, "a.txt", "a\n");
            String first = git.commitAll(a, "第一次", alice);
            writeFile(a, "a.txt", "a2\n");
            String second = git.commitAll(a, "第二次", alice);

            assertThat(git.countCommits(a, first + ".." + second)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Path newSession(String name) {
        Path worktree = sessionPath(name);
        git.worktreeAdd(repo, worktree, "session/" + name, "main");
        return worktree;
    }

    /** worktree 是主仓库的兄弟目录，不是子目录；放在里面会被仓库自己的 git 看见。 */
    private Path sessionPath(String name) {
        return repo.getParent().resolve("ws-" + name);
    }

    private static void writeFile(Path dir, String name, String content) {
        try {
            Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 " + name + " 失败", e);
        }
    }

    private static String read(Path dir, String name) {
        try {
            return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + name + " 失败", e);
        }
    }

}
