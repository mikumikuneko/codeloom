package com.codeloom.workspace.git;

import com.codeloom.domain.port.CommitIdentity;
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
 * 跑**真的 git**，用真的临时目录。
 *
 * <p>不需要 Spring，也不需要数据库 —— 这正是把 workspace 做成只依赖 domain 的回报。
 *
 * <p>这里每个 {@code 【实验回归】} 标注的用例，都对应设计阶段那次实验里真实踩到的一个坑。
 * 它们存在的意义是：**别让同样的坑再踩第二次**。
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
            // 调用方会先放好新项目该有的内容（现在是那个 untitled/ 的占位文件）。
            // 它们必须落在**基点**里：两棵树都是从基点 checkout 出来的，
            // 未跟踪的文件不会被 checkout，于是两个人在自己的树里都看不到它 ——
            // 而它在主仓库的磁盘上是存在的，那种"看得见目录、树里没有"最难查
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

            // 这里问的是 git 自己 —— 从前问的是 GitClient 自带的查询方法，
            // 那是拿被测代码去断言被测代码：包装错了会一起错、一起绿
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
            // 4M 字符的上限对"一条提交改十万个文件"够宽，但对**一个被冲突的大文件**
            // （锁文件、打包产物、生成出来的 JSON）正好不够。从前这里会把截断过的内容
            // 交出去让人挑"留哪一边"，而没有任何地方会报错 —— 最坏的那种 bug
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
            // 它守的是合并前那道"我有没有落后于主干"的检查：落后就拒绝合并、让人先同步。
            // 少算了会放行一次本可以避免的冲突（冲突于是撞在主干上），
            // 多算了会让人不明不白地合不动
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

    /** worktree 是主仓库的**兄弟目录**，不是子目录 —— 放里面会让仓库自己的 git 看见它。 */
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

    private static void deleteFile(Path dir, String name) {
        try {
            Files.delete(dir.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException("删除 " + name + " 失败", e);
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
