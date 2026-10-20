package com.codeloom.workspace;

import com.codeloom.domain.port.CommitIdentity;
import com.codeloom.domain.port.MergeResult;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.workspace.FileChange;
import com.codeloom.domain.workspace.FileDiff;
import com.codeloom.domain.workspace.WorkspaceId;
import com.codeloom.workspace.git.GitClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link WorkspaceManager} 的本地实现：真 git、真文件系统。
 *
 * <p>工作区目录是 {@code workspacesRoot/ownerId/projectId}。不能拼成一个目录名：
 * {@link WorkspaceId#value()} 用冒号分隔，而冒号在 Windows 上是保留字符。
 * worktree 是主仓库的兄弟目录，不是子目录 —— 放在仓库里会被项目自己的 git 当成
 * 未跟踪内容，嵌套的 git 仓库也会干扰状态判断。
 */
public final class LocalWorkspaceManager implements WorkspaceManager {

    private final GitClient git;
    private final Path workspacesRoot;

    /**
     * @param git 执行 git 的子进程客户端
     * @param workspacesRoot 所有工作区目录的根，会规范化为绝对路径
     */
    public LocalWorkspaceManager(GitClient git, Path workspacesRoot) {
        this.git = Objects.requireNonNull(git, "git");
        this.workspacesRoot = Objects.requireNonNull(workspacesRoot, "workspacesRoot")
                .toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------

    @Override
    public void initializeRepository(Path repoPath, CommitIdentity creator, String firstDirectory) {
        seed(repoPath, firstDirectory);
        git.initRepo(repoPath, creator);
    }

    /** 预置目录里占位文件的名字，理由见 {@link #seed}。 */
    private static final String FIRST_FILE = "README.md";

    /**
     * 在新项目里预置顶层目录，并放一个空的 {@code README.md}。
     *
     * <p>git 不记录空目录，目录里必须有文件才能进入基点提交；两棵树都从基点 checkout，
     * 否则两个人在自己的树里都看不到它。文件用 {@code README.md} 而不是
     * {@code .gitkeep}，内容为空，因为替用户决定目录里写什么超出了平台职责。
     *
     * @param repoPath 项目仓库目录
     * @param firstDirectory 顶层目录名；null 或空白表示不预置
     * @throws UncheckedIOException 目录或占位文件创建失败
     */
    private static void seed(Path repoPath, String firstDirectory) {
        if (firstDirectory == null || firstDirectory.isBlank()) {
            return;
        }
        Path directory = repoPath.resolve(firstDirectory);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(FIRST_FILE), "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("无法在新项目里预置目录 " + firstDirectory + "：" + repoPath, e);
        }
    }

    @Override
    public void removeWorkspace(WorkspaceId workspaceId, Path repoPath) {
        Path worktree = worktreePath(workspaceId);
        // 仓库不在了（项目可能已经删到最后一步）就没有名册可清，只剩磁盘上的目录
        if (Files.isDirectory(repoPath.resolve(".git"))) {
            if (Files.isDirectory(worktree)) {
                git.worktreeRemove(repoPath, worktree);
            } else {
                // 目录没了、名册里可能还有记录；对它用 remove 会以"路径不存在"结束
                git.pruneWorktrees(repoPath);
            }
            // 分支必须一起删：留着它，重新进入同一项目时 worktree add -b 会因分支已存在而失败
            git.branchDelete(repoPath, branchName(workspaceId));
        }
        Directories.deleteRecursively(worktree);
    }

    @Override
    public void removeRepository(Path repoPath) {
        Directories.deleteRecursively(repoPath);
    }

    @Override
    public void archive(Path repoPath, String treeish, Path outputZip) {
        git.archive(repoPath, treeish, outputZip);
    }

    @Override
    public FileDiff diffOfFile(Path worktree, String commitSha, String gitPath, int maxChars) {
        return git.diffOfFile(worktree, commitSha, gitPath, maxChars);
    }

    @Override
    public Workspace create(WorkspaceId workspaceId, Path repoPath, String baseCommit) {
        Path worktree = worktreePath(workspaceId);
        if (Files.isDirectory(worktree)) {
            // 幂等：目录已存在就读出当前状态，不重复 worktree add
            return describe(workspaceId, worktree);
        }
        excludeToolOutput(repoPath);
        // baseCommit 为空表示项目从空目录起步，从主干（基点）分出
        String base = (baseCommit == null || baseCommit.isBlank()) ? MAIN_BRANCH : baseCommit;
        git.worktreeAdd(repoPath, worktree, branchName(workspaceId), base);
        return describe(workspaceId, worktree);
    }

    /**
     * 让 git 忽略 agent 的落盘目录（见 {@link Workspace#TOOL_OUTPUT_DIR}）。
     *
     * <p>写进 {@code .git/info/exclude}：这是本地忽略机制，不进版本库，主仓库的那一份
     * 对所有 worktree 生效。不要再补 {@code .gitignore}，它会跟着基点提交进入用户项目的
     * 历史。失败不抛：没写成的后果只是工作区多出噪声文件，不能因此让创建 worktree 失败。
     * 理由见
     * {@code docs/decisions/bug-fix/2026-10-08-workspace-ignore-lives-in-info-exclude.md}。
     *
     * <p>模式带 {@code **} 前缀：落盘目录在项目根下，而项目根是 worktree 根下的
     * {@code untitled}，不是 worktree 根本身。只写相对模式会忽略错层级，agent 的产物
     * 仍会出现在 {@code git status} 里。
     *
     * @param repoPath 项目主仓库路径
     */
    private static void excludeToolOutput(Path repoPath) {
        Path exclude = repoPath.resolve(".git/info/exclude");
        if (!Files.isDirectory(exclude.getParent())) {
            return;   // 不是 git 仓库，或结构不是预期形态，不去动它
        }
        String pattern = "**/" + Workspace.TOOL_OUTPUT_DIR;
        try {
            String current = Files.exists(exclude)
                    ? Files.readString(exclude, StandardCharsets.UTF_8)
                    : "";
            if (current.lines().anyMatch(pattern::equals)) {
                return;   // 幂等：这一行已经存在
            }
            Files.writeString(exclude,
                    current + (current.isEmpty() || current.endsWith("\n") ? "" : "\n")
                            + pattern + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 见方法注释：宁可多几个噪声文件，也不能因此建不出 worktree
        }
    }

    @Override
    public Optional<Workspace> find(WorkspaceId workspaceId) {
        Path worktree = worktreePath(workspaceId);
        return Files.isDirectory(worktree)
                ? Optional.of(describe(workspaceId, worktree))
                : Optional.empty();
    }

    // ------------------------------------------------------------------

    @Override
    public String commit(Workspace workspace, String message, CommitIdentity identity) {
        // 不先跑 git status：commitAll 内部识别 "nothing to commit" 并返回当前 HEAD，
        // 而在大仓库上 status 要哈希整个工作树
        return git.commitAll(requireExisting(workspace), message, identity);
    }

    @Override
    public void resetTo(Workspace workspace, String commitSha) {
        Path worktree = requireExisting(workspace);
        git.resetHard(worktree, commitSha);
        // 必须同时清掉未跟踪文件，否则回滚对 agent 刚写的文件不生效，见 GitClient.cleanUntracked
        git.cleanUntracked(worktree);
    }

    @Override
    public MergeResult merge(Path targetWorktree, String sourceRef, CommitIdentity identity) {
        return git.merge(requireDirectory(targetWorktree, "目标工作区"), sourceRef, identity);
    }

    @Override
    public int commitsBehind(Path worktree, String ref) {
        // HEAD..ref 就是 "ref 上有、而我没有" 的提交
        return git.countCommits(requireDirectory(worktree, "工作区"), "HEAD.." + ref);
    }

    @Override
    public List<FileChange> changesIntroducedBy(Path worktree, String commitSha) {
        return git.numstatOf(requireDirectory(worktree, "工作区"), commitSha);
    }

    // ------------------------------------------------------------------
    // 冲突裁决（都作用在一个普通的 git 工作目录上，可能是某棵树的工作区，也可能是主干）
    // ------------------------------------------------------------------

    @Override
    public boolean mergeInProgress(Path worktree) {
        return git.mergeInProgress(requireDirectory(worktree));
    }

    @Override
    public List<String> conflicts(Path worktree) {
        return git.conflictedFiles(requireDirectory(worktree));
    }

    @Override
    public String conflictSide(Path worktree, String path, boolean ours) {
        // 索引 stage 2 是我方、3 是对方；这个映射只在这里出现一次
        return git.conflictSide(requireDirectory(worktree), ours ? 2 : 3, path);
    }

    @Override
    public void resolveConflict(Path worktree, String path, boolean ours) {
        git.resolveConflictByChoosingSide(requireDirectory(worktree), path, ours);
    }

    @Override
    public void resolveConflictByContent(Path worktree, String path, String content) {
        Path root = requireDirectory(worktree);

        // 应用层已保证 path 是 git 报出的冲突文件；这里防的是路径逃出仓库 ——
        // 这是本类唯一一处把内容写到磁盘上的操作，不靠上游检查推断安全
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("路径越出了仓库：" + path);
        }
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入裁决后的文件失败：" + target, e);
        }
        git.stageFile(root, path);
    }

    @Override
    public String finishMerge(Path worktree, String message, CommitIdentity identity) {
        return git.commitStaged(requireDirectory(worktree), message, identity);
    }

    @Override
    public void abortMerge(Path worktree) {
        git.mergeAbort(requireDirectory(worktree));
    }

    private static Path requireDirectory(Path worktree) {
        return requireDirectory(worktree, "工作目录");
    }

    /**
     * 校验目录存在；错误信息里的称呼跟随调用点。
     *
     * @param worktree 要检查的目录
     * @param what 错误信息里对这个目录的称呼
     * @return 原样返回 {@code worktree}
     * @throws IllegalStateException 目录不存在
     */
    private static Path requireDirectory(Path worktree, String what) {
        if (!Files.isDirectory(worktree)) {
            throw new IllegalStateException(what + "不存在: " + worktree);
        }
        return worktree;
    }

    // ------------------------------------------------------------------

    /**
     * 这棵树的工作区目录：{@code workspacesRoot/ownerId/projectId}。
     *
     * @param workspaceId 树的主人 × 项目
     * @return 工作区路径
     */
    public Path worktreePath(WorkspaceId workspaceId) {
        return workspacesRoot
                .resolve(workspaceId.ownerId().value())
                .resolve(workspaceId.projectId().value());
    }

    /**
     * 这棵树的分支名：{@code workspace/} 加 ownerId。
     *
     * <p>刻意不带 projectId：分支只存在于该项目的仓库里，那一维是冗余的。也不使用
     * {@link WorkspaceId#value()}，因为冒号是 git ref 名的非法字符。公开是为了让测试
     * 引用同一份命名规则。
     *
     * @param workspaceId 树的主人 × 项目
     * @return 分支名
     */
    public static String branchName(WorkspaceId workspaceId) {
        return "workspace/" + workspaceId.ownerId().value();
    }

    private Workspace describe(WorkspaceId workspaceId, Path worktree) {
        return new Workspace(workspaceId, worktree, branchName(workspaceId),
                git.revParse(worktree, "HEAD"));
    }

    private static Path requireExisting(Workspace workspace) {
        return requireDirectory(workspace.path(), "工作区");
    }
}
