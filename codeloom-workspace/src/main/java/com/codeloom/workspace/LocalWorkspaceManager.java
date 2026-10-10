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
 * <h2>目录布局</h2>
 * <pre>
 *   &lt;workspacesRoot&gt;/&lt;ownerId&gt;/&lt;projectId&gt;/      一棵树一个 git worktree
 * </pre>
 *
 * <p>按人分一层目录、再按项目分一层：一个人在一个项目里就一棵树，这两维正好是
 * {@link WorkspaceId} 的两半，所以目录名不需要额外的编码，人一眼就能对上。
 *
 * <p><strong>不能把它们拼成一个目录名。</strong>文本形式（{@code value()}）用的是冒号分隔，
 * 而冒号在 Windows 上是**保留字符**（它分隔 NTFS 的备用数据流），拿它当文件名的一部分
 * 会得到一个建不出来的路径。分两层就没有这个问题，而且顺带让文件管理器里好读。
 *
 * <p>注意 worktree 是**主仓库的兄弟目录**，不是它的子目录。放进仓库里面的话，
 * 项目自己的 git 会把它们当成未跟踪文件，而且里面嵌套的 git 仓库会让状态判断变得混乱。
 */
public final class LocalWorkspaceManager implements WorkspaceManager {

    private final GitClient git;
    private final Path workspacesRoot;

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

    /** 预置目录里那个占位文件的名字。为什么是它、为什么是空的 —— 见下面 {@link #seed}。 */
    private static final String FIRST_FILE = "README.md";

    /**
     * 新项目自带的那个目录，靠里面一个文件在 git 里活下来。
     *
     * <h2>为什么目录里必须有一个文件</h2>
     * <strong>git 存不了空目录</strong>：它记的是文件，目录只是路径的一段。一个没有文件的
     * 目录在 {@code git status} 里根本不存在，于是进不了基点提交 —— 而两棵树都是从基点
     * checkout 出来的，结果是<strong>两个人在自己的树里都看不到它</strong>，只有主仓库那个
     * 目录里躺着。那比"没有这个目录"更难查：它在磁盘上确实存在。
     *
     * <h2>为什么这个文件叫 {@code README.md}，而且是空的</h2>
     * 名字用 {@code README.md} 而不是 {@code .gitkeep}：它是别人打开一个目录时
     * **第一个会点开的东西**，而 {@code .gitkeep} 只是 git 圈里的惯用法。
     *
     * <p>内容是**空的**：这个目录是我们替他放的，但"他的项目里该写什么"不是我们能回答的
     * —— 写一句就等于替他开了个头。
     *
     * <p>会失败就抛：这一步失败意味着新项目少了它该有的落脚处，
     * 而"静默地少一个目录"要等到用户打开左栏才发现。
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
        // 仓库不在了（例如项目已经删到最后一步）就没有名册可清，只剩地上那个目录
        if (Files.isDirectory(repoPath.resolve(".git"))) {
            if (Files.isDirectory(worktree)) {
                git.worktreeRemove(repoPath, worktree);
            } else {
                // 目录没了、名册里可能还留着一条：对它用 remove 会以一个"路径不存在"结束，
                // 而这里要的只是把那条记录清掉
                git.pruneWorktrees(repoPath);
            }
            // 分支必须一起删。留着的话，这个人将来重新进同一个项目时
            // worktree add -b 会因为"分支已存在"而失败 —— 而那看起来像"进不去项目"
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
            // 幂等：目录已经在就说明建过了，直接读出现状返回，不重复 worktree add
            return describe(workspaceId, worktree);
        }
        excludeToolOutput(repoPath);
        // baseCommit 为空 = 项目还是空目录起步的状态，那就从基点（主干）分出
        String base = (baseCommit == null || baseCommit.isBlank()) ? MAIN_BRANCH : baseCommit;
        git.worktreeAdd(repoPath, worktree, branchName(workspaceId), base);
        return describe(workspaceId, worktree);
    }

    /**
     * 让 git 忽略 agent 的落盘目录（见 {@link Workspace#TOOL_OUTPUT_DIR}）。
     *
     * <h2>写的是 {@code .git/info/exclude}</h2>
     * 它是 git 官方的**本地**忽略机制：不进版本库，而且主仓库的那一份**对所有 worktree
     * 生效**，写一次就够。为什么不写 {@code .gitignore}、代价是什么，见
     * {@code docs/decisions/bug-fix/2026-10-08-workspace-ignore-lives-in-info-exclude.md}。
     *
     * <p>**这是唯一的忽略机制** —— 别在别处再补一个 {@code .gitignore}：它会跟着基点提交
     * 进用户项目的历史，正是上面那条要避免的事。
     *
     * <p>失败**不抛异常**：这只是一条"别把落盘目录提交进去"的保险，它没做成的后果是
     * 工作区里多出一些噪声文件；而建不出 worktree 会让整条会话根本用不了。
     */
    private static void excludeToolOutput(Path repoPath) {
        Path exclude = repoPath.resolve(".git/info/exclude");
        if (!Files.isDirectory(exclude.getParent())) {
            return;   // 不是 git 仓库，或者结构不是我们预期的样子 —— 那就别动它
        }
        try {
            String current = Files.exists(exclude)
                    ? Files.readString(exclude, StandardCharsets.UTF_8)
                    : "";
            if (current.lines().anyMatch(Workspace.TOOL_OUTPUT_DIR::equals)) {
                return;   // 幂等：已经有这一行了
            }
            Files.writeString(exclude,
                    current + (current.isEmpty() || current.endsWith("\n") ? "" : "\n")
                            + Workspace.TOOL_OUTPUT_DIR + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 理由见方法注释：宁可工作区里多几个噪声文件，也不能因此建不出 worktree
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
        // 不先跑一次 git status 探有没有改动：commitAll 内部已经能识别
        // "nothing to commit" 并返回当前 HEAD（见 GitClient.commitStaged），
        // 行为完全一致。而 status 在大仓库上要哈希整个工作树，一个 turn 里白跑一次不划算。
        return git.commitAll(requireExisting(workspace), message, identity);
    }

    @Override
    public void resetTo(Workspace workspace, String commitSha) {
        Path worktree = requireExisting(workspace);
        git.resetHard(worktree, commitSha);
        // 紧跟着清掉未跟踪的文件 —— 少了这一步回滚就不生效，理由见 GitClient.cleanUntracked
        git.cleanUntracked(worktree);
    }

    @Override
    public MergeResult merge(Path targetWorktree, String sourceRef, CommitIdentity identity) {
        if (!Files.isDirectory(targetWorktree)) {
            throw new IllegalStateException("目标工作区不存在: " + targetWorktree);
        }
        return git.merge(targetWorktree, sourceRef, identity);
    }

    @Override
    public int commitsBehind(Path worktree, String ref) {
        if (!Files.isDirectory(worktree)) {
            throw new IllegalStateException("工作区不存在: " + worktree);
        }
        // {@code HEAD..ref} 就是"ref 上有、而我没有"的那些提交
        return git.countCommits(worktree, "HEAD.." + ref);
    }

    @Override
    public List<FileChange> changesIntroducedBy(Path worktree, String commitSha) {
        if (!Files.isDirectory(worktree)) {
            throw new IllegalStateException("工作区不存在: " + worktree);
        }
        return git.numstatOf(worktree, commitSha);
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
        // 索引里的 stage 2 是"我方"、3 是"对方"。这个映射只在这里出现一次 ——
        // 把"2 和 3 哪个是哪边"泄漏到上层，是那种看代码时看不出错、但要错就全错的细节
        return git.conflictSide(requireDirectory(worktree), ours ? 2 : 3, path);
    }

    @Override
    public void resolveConflict(Path worktree, String path, boolean ours) {
        git.resolveConflictByChoosingSide(requireDirectory(worktree), path, ours);
    }

    @Override
    public void resolveConflictByContent(Path worktree, String path, String content) {
        Path root = requireDirectory(worktree);

        // 语义上的那道边界在应用层（这个路径必须先是 git 报出来的冲突文件之一），
        // 这里挡的是另一个问题：**这个路径本身会不会跑出仓库**。
        // 两个问题不一样，而三行的成本换的是"写入目标一定在仓库里"这个能直接读出来的保证 ——
        // 写入是这里唯一一处会让内容落到磁盘上的操作，不值得靠上游的检查来推断安全
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
        if (!Files.isDirectory(worktree)) {
            throw new IllegalStateException("工作目录不存在: " + worktree);
        }
        return worktree;
    }

    // ------------------------------------------------------------------

    /** {@code <workspacesRoot>/<ownerId>/<projectId>} */
    public Path worktreePath(WorkspaceId workspaceId) {
        return workspacesRoot
                .resolve(workspaceId.ownerId().value())
                .resolve(workspaceId.projectId().value());
    }

    /**
     * 分支名：{@code workspace/<ownerId>}。
     *
     * <p><strong>刻意不带 projectId</strong>：分支活在**那个项目的仓库**里，所以"哪个项目"
     * 这一维在名字里是冗余的 —— 而冗余的部分迟早会和主体漂移。
     *
     * <p>前缀不用树 id 的文本形式（{@code owner:project}）：git 的 ref 名里
     * **冒号是非法字符**，这行会直接被 {@code check-ref-format} 拒掉。
     *
     * <p>公开是为了测试能指向**同一份**命名规则而不是各抄一遍 —— 抄出来的那几份在改名时
     * 会各自安静地过期，而"测试里的分支名"过期之后测的就不是真的那棵树了。
     */
    public static String branchName(WorkspaceId workspaceId) {
        return "workspace/" + workspaceId.ownerId().value();
    }

    private Workspace describe(WorkspaceId workspaceId, Path worktree) {
        return new Workspace(workspaceId, worktree, branchName(workspaceId),
                git.revParse(worktree, "HEAD"));
    }

    private static Path requireExisting(Workspace workspace) {
        Path path = workspace.path();
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("工作区不存在: " + path);
        }
        return path;
    }
}
