package com.codeloom.workspace;

import com.codeloom.domain.port.CommitIdentity;
import com.codeloom.domain.port.MergeResult;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import com.codeloom.workspace.git.GitClient;
import com.codeloom.workspace.support.TestGit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端跑真实的两人协作流程。
 *
 * <p>不 mock git、不 mock 文件系统：这个模块的价值就在于它真的能操作 git，
 * 用假的测不出任何东西。
 *
 * <h2>为什么 fixture 用两个不同的人</h2>
 * 同一个人在一个项目里只有一棵树（换会话不换代码），两个人各有各的树、能同时干活互不干扰。
 * 所以下面凡是需要"两份互不相干的工作区"的地方，用的都是 alice 和 bob 两个不同的键，
 * 而不是同一个人的两个 id。
 */
class LocalWorkspaceManagerTest {

    private static final UserId ALICE = UserId.of("11111111-1111-1111-1111-111111111111");
    private static final UserId BOB = UserId.of("22222222-2222-2222-2222-222222222222");
    private static final ProjectId PROJECT = ProjectId.of("33333333-3333-3333-3333-333333333333");

    private static final WorkspaceId ALICE_TREE = WorkspaceId.of(ALICE, PROJECT);
    private static final WorkspaceId BOB_TREE = WorkspaceId.of(BOB, PROJECT);
    private static final WorkspaceId NOBODY_TREE =
            WorkspaceId.of(UserId.of("99999999-9999-9999-9999-999999999999"), PROJECT);

    @TempDir
    Path tempDir;

    private GitClient git;
    private LocalWorkspaceManager manager;
    private Path repo;
    private Path workspacesRoot;

    private final CommitIdentity alice = CommitIdentity.of("alice");
    private final CommitIdentity bob = CommitIdentity.of("bob");

    @BeforeEach
    void setUp() {
        git = new GitClient("git", tempDir.resolve("sandbox"));
        repo = tempDir.resolve("repo");
        workspacesRoot = tempDir.resolve("workspaces");
        manager = new LocalWorkspaceManager(git, workspacesRoot);
        git.initRepo(repo, alice);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("create 建出工作区与独立分支，find 能原样取回")
    void createsAndFindsWorkspace() {
        Workspace created = manager.create(ALICE_TREE, repo, null);

        assertThat(created.workspaceId()).isEqualTo(ALICE_TREE);
        // 目录按「人 / 项目」分两层：拼成一个目录名的话，文本形式里的冒号在 Windows 上是保留字符
        assertThat(created.path())
                .isEqualTo(workspacesRoot.resolve(ALICE.value()).resolve(PROJECT.value()));
        assertThat(created.branch()).isEqualTo("workspace/" + ALICE.value());
        assertThat(created.headCommit()).as("有基点就有 HEAD").isNotNull();
        assertThat(manager.find(ALICE_TREE)).contains(created);
    }

    @Test
    @DisplayName("create 是幂等的：重复调用不会炸，也不会重建工作区")
    void createIsIdempotent() {
        Workspace first = manager.create(ALICE_TREE, repo, null);
        Workspace second = manager.create(ALICE_TREE, repo, null);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("新项目自带 untitled/：两棵树里都看得到它，因为它在基点提交里")
    void newProjectComesWithItsFirstDirectory() {
        Path fresh = tempDir.resolve("fresh-repo");
        manager.initializeRepository(fresh, alice, "untitled");

        // 判据是两棵**人的树**里有没有，而不是主仓库磁盘上有没有 —— 后者成立不代表有用。
        // 两棵树都从基点 checkout 出来，所以这一条同时验了"目录在"和"它在基点里"
        //（git 存不了空目录，里面那个占位文件才是真正被记录的东西）
        assertThat(manager.create(ALICE_TREE, fresh, null).path().resolve("untitled/README.md")).exists();
        assertThat(manager.create(BOB_TREE, fresh, null).path().resolve("untitled/README.md")).exists();
        assertThat(git.countCommits(fresh, "main")).isEqualTo(1);   // 只有基点那一笔
    }

    @Test
    @DisplayName("【换会话不换代码】同一个人的两次 create 落在同一棵树、同一条分支上")
    void oneTreePerPersonPerProject() {
        Workspace first = manager.create(ALICE_TREE, repo, null);
        write(first.path(), "OrderService.java", "class OrderService {}\n");
        manager.commit(first, "alice 的产出", alice);

        // "这个人又开了一段新对话"：键没变，所以拿到的还是那棵树，上一轮的改动原样都在 ——
        // 这正是把键从会话提到人的全部意义
        Workspace again = manager.create(ALICE_TREE, repo, null);

        assertThat(again.path()).isEqualTo(first.path());
        assertThat(again.branch()).isEqualTo(first.branch());
        assertThat(again.path().resolve("OrderService.java")).exists();
    }

    @Test
    @DisplayName("落盘目录被 git 忽略 —— agent 的产物不能混进用户的项目")
    void toolOutputDirectoryIsIgnoredByGit() throws Exception {
        Workspace workspace = manager.create(ALICE_TREE, repo, null);

        // 模拟一次落盘：往那个目录里写一份"完整工具输出"
        Files.createDirectories(workspace.path().resolve(Workspace.TOOL_OUTPUT_DIR));
        Files.writeString(workspace.path().resolve(Workspace.TOOL_OUTPUT_DIR).resolve("call_1.txt"), "完整输出",
                StandardCharsets.UTF_8);

        // git 视角里它根本不存在 —— 工作区依然是干净的。用 .gitignore 的话它会进版本库，
        // 而 agent 不该往用户的项目里塞任何东西
        assertThat(TestGit.run(workspace.path(), "status", "--porcelain")).isEmpty();
    }

    @Test
    @DisplayName("建多棵树不会把忽略规则写重复")
    void excludeIsWrittenOnlyOnce() throws IOException {
        manager.create(ALICE_TREE, repo, null);
        manager.create(BOB_TREE, repo, null);

        String exclude = Files.readString(repo.resolve(".git/info/exclude"), StandardCharsets.UTF_8);
        long occurrences = exclude.lines()
                .filter(Workspace.TOOL_OUTPUT_DIR::equals)
                .count();

        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    @DisplayName("find 对不存在的工作区返回空，而不是抛异常")
    void findReturnsEmptyForUnknownWorkspace() {
        assertThat(manager.find(NOBODY_TREE)).isEmpty();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("commit 打 checkpoint；没有改动时不造空提交")
    void commitCreatesCheckpointOnlyWhenThereAreChanges() {
        Workspace ws = manager.create(ALICE_TREE, repo, null);
        String base = git.revParse(ws.path(), "HEAD");

        assertThat(manager.commit(ws, "什么都没改", alice)).isEqualTo(base);

        write(ws.path(), "Hello.java", "class Hello {}\n");
        String checkpoint = manager.commit(ws, "加 Hello", alice);

        assertThat(checkpoint).isNotEqualTo(base);
        assertThat(git.countCommits(ws.path(), "main..HEAD")).isEqualTo(1);
    }

    @Test
    @DisplayName("【主流程】工作区的产出合并进主干，主干工作区里能看到文件")
    void workspaceMergesIntoMain() {
        Workspace ws = manager.create(ALICE_TREE, repo, null);
        write(ws.path(), "OrderService.java", "class OrderService {}\n");
        manager.commit(ws, "实现 create", alice);

        MergeResult result = manager.merge(repo, ws.branch(), alice);

        assertThat(result.status()).isEqualTo(MergeResult.Status.FAST_FORWARD);
        assertThat(repo.resolve("OrderService.java")).exists();
    }

    @Test
    @DisplayName("两边改同一处时升级为冲突，不自动裁决 —— 冲突的两侧都能取出来交给用户")
    void conflictingMergeIsReportedWithBothSides() {
        Workspace a = manager.create(ALICE_TREE, repo, null);
        Workspace b = manager.create(BOB_TREE, repo, null);

        write(a.path(), "OrderService.java", "// alice 的版本\n");
        manager.commit(a, "alice 实现", alice);
        write(b.path(), "OrderService.java", "// bob 的版本\n");
        manager.commit(b, "bob 实现", bob);

        manager.merge(repo, a.branch(), alice);
        MergeResult conflict = manager.merge(b.path(), WorkspaceManager.MAIN_BRANCH, bob);

        assertThat(conflict.status()).isEqualTo(MergeResult.Status.CONFLICT);
        assertThat(conflict.conflictingPaths()).containsExactly("OrderService.java");
        // 冲突的两边内容都能取出来交给用户裁决
        assertThat(git.conflictSide(b.path(), 2, "OrderService.java")).isEqualTo("// bob 的版本\n");
        assertThat(git.conflictSide(b.path(), 3, "OrderService.java")).isEqualTo("// alice 的版本\n");
    }

    @Test
    @DisplayName("两棵树之间互不可见 —— 各自的工作区里只有自己的文件")
    void treesDoNotSeeEachOther() {
        Workspace a = manager.create(ALICE_TREE, repo, null);
        Workspace b = manager.create(BOB_TREE, repo, null);

        write(a.path(), "only-in-a.txt", "a\n");
        manager.commit(a, "a 的产出", alice);

        assertThat(a.path().resolve("only-in-a.txt")).exists();
        assertThat(b.path().resolve("only-in-a.txt")).doesNotExist();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("回滚：resetTo 把工作区退回指定 checkpoint，之后的改动被丢弃")
    void resetToRollsBackToCheckpoint() throws Exception {
        Workspace ws = manager.create(ALICE_TREE, repo, null);
        write(ws.path(), "file.txt", "第一版\n");
        String first = manager.commit(ws, "第一版", alice);
        write(ws.path(), "file.txt", "第二版\n");
        manager.commit(ws, "第二版", alice);

        manager.resetTo(ws, first);

        assertThat(read(ws.path(), "file.txt")).isEqualTo("第一版\n");
        assertThat(TestGit.run(ws.path(), "status", "--porcelain")).isEmpty();
    }

    @Test
    @DisplayName("【回滚】resetTo 之后工作区回到那个 commit 的完全一致状态，未跟踪文件也不留")
    void resetToAlsoDropsUntrackedFiles() {
        Workspace ws = manager.create(ALICE_TREE, repo, null);
        write(ws.path(), "committed.txt", "已提交\n");
        String checkpoint = manager.commit(ws, "打一个 checkpoint", alice);
        // agent 刚写出来、还没被任何 checkpoint 收进去的文件就是未跟踪的
        write(ws.path(), "just-written.txt", "还没提交\n");

        manager.resetTo(ws, checkpoint);

        // reset --hard 不碰未跟踪文件，所以这一步是回滚能不能真的生效的关键
        assertThat(ws.path().resolve("just-written.txt")).doesNotExist();
    }

    // ------------------------------------------------------------------

    private static void write(Path dir, String name, String content) {
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
