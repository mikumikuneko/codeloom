package com.codeloom.workspace.git;

import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommitIdentity;
import com.codeloom.domain.port.CommandTermination;
import com.codeloom.domain.port.MergeResult;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.workspace.FileChange;
import com.codeloom.domain.workspace.FileDiff;
import com.codeloom.workspace.exec.ProcessOutcome;
import com.codeloom.workspace.exec.ProcessRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;

/**
 * 通过 {@code git} 子进程操作仓库。
 *
 * <p>不使用 JGit：项目依赖 linked worktree（独立 HEAD、独立索引、{@code .git} 是文件），
 * JGit 对它的支持不完整。每次调用都带硬化的 {@code -c} 参数，中和
 * {@code core.hooksPath}、{@code core.fsmonitor} 与凭据 helper 等能执行命令或改变行为的
 * 配置；{@code diff} 子命令另带 {@code --no-ext-diff}、{@code --no-textconv}，关掉本地配置
 * 或 {@code .gitattributes} 指定的外部 diff 与 textconv 命令。
 *
 * <p>{@code filter.*.clean} 不在保证内：git 没有能一次关掉全部 filter driver 的配置项，
 * 而 {@code .gitattributes} 可以给任意 driver 名挂上本地配置里的命令。要挡住它，得在
 * 工作区之外再隔离 git 配置，单靠这里的参数做不到。
 *
 * <p>新建仓库立刻打基点提交并固定分支名为 {@link WorkspaceManager#MAIN_BRANCH}；
 * 不设仓库级提交身份，身份只出现在会产生提交的入口上。退出码由调用方解释：merge 的 1
 * 是冲突，128 是致命错误。理由见
 * {@code docs/decisions/architecture/2026-09-24-git-as-a-subprocess-with-per-commit-identity.md}。
 */
public final class GitClient {

    /** git 卡住时的兜底时限；正常操作是毫秒级，大仓库首次 checkout 会慢。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** 单次 git 调用的输出字符上限。 */
    private static final int MAX_OUTPUT_CHARS = 4 * 1024 * 1024;

    /** 同时写入仓库配置的项，使用户用 IDE 或命令行操作该仓库时行为一致。 */
    private static final List<String> PERSISTENT_CONFIG = List.of(
            "core.autocrlf=false",
            "core.longpaths=true",
            "core.symlinks=false"
    );

    private final String gitExecutable;
    private final Path hooksDir;
    private final Path emptyGlobalConfig;
    private final Charset outputCharset;
    private final Duration timeout;

    /**
     * 使用本机编码和 {@link #DEFAULT_TIMEOUT}。
     *
     * @param gitExecutable git 可执行文件名或路径
     * @param sandboxDir 沙箱目录，存放空 hooks 目录与空全局配置
     */
    public GitClient(String gitExecutable, Path sandboxDir) {
        // 与 LocalCommandExecutor 同一规则：先按 UTF-8 解，失败后按本机编码；
        // 冲突时的文件内容编码取决于用户仓库，不能固定成 UTF-8
        this(gitExecutable, sandboxDir, ProcessRunner.nativeCharset(), DEFAULT_TIMEOUT);
    }

    /**
     * @param gitExecutable git 可执行文件名或路径
     * @param sandboxDir 沙箱目录，存放空 hooks 目录与空全局配置
     * @param fallbackCharset 输出不是合法 UTF-8 时的兜底字符集，规则见 {@link ProcessRunner}
     * @param timeout 单次 git 调用的超时
     */
    public GitClient(String gitExecutable, Path sandboxDir, Charset fallbackCharset, Duration timeout) {
        this.gitExecutable = Objects.requireNonNull(gitExecutable, "gitExecutable");
        Objects.requireNonNull(sandboxDir, "sandboxDir");
        this.outputCharset = Objects.requireNonNull(fallbackCharset, "fallbackCharset");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.hooksDir = sandboxDir.resolve("hooks");
        this.emptyGlobalConfig = sandboxDir.resolve("empty-gitconfig");
        try {
            // 一个空目录，用来中和 core.hooksPath：指向这里就等于"没有 hook"
            Files.createDirectories(hooksDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建 git 沙箱目录: " + hooksDir, e);
        }
    }

    // ------------------------------------------------------------------
    // 进程执行
    // ------------------------------------------------------------------

    /**
     * 执行一条 git 子命令，永远返回结果，不对非零退出码抛异常。
     *
     * <p>退出码含义由调用方解释，见 {@link GitCommandException}。不带提交身份：读命令
     * 以及 {@code add}、{@code checkout}、{@code reset}、{@code clean} 等不写作者信息的
     * 写命令都走它。
     *
     * @param workingDirectory 工作目录
     * @param args git 子命令及参数
     * @return 执行结果，包括非零退出码
     * @throws GitCommandException 进程被超时终止
     * @throws com.codeloom.workspace.exec.ProcessException 进程无法启动或等待时被中断
     */
    public ProcessOutcome run(Path workingDirectory, List<String> args) {
        return execute(buildCommand(workingDirectory, null, args), workingDirectory);
    }

    /** 同上，但非零退出码直接抛 {@link GitCommandException}。 */
    public ProcessOutcome runOrThrow(Path workingDirectory, List<String> args) {
        // 命令行只构造一次，异常路径复用同一份
        List<String> command = buildCommand(workingDirectory, null, args);
        ProcessOutcome outcome = execute(command, workingDirectory);
        if (!outcome.succeeded()) {
            throw new GitCommandException(command, outcome, "git 命令失败");
        }
        return outcome;
    }

    /**
     * 带提交身份执行：{@code -c user.name=… -c user.email=…} 加在命令前面。
     *
     * <p>只有会产生提交的命令使用它；不设仓库级身份，其余命令一律用 {@link #run} 或
     * {@link #runOrThrow}。与 {@link #run} 一样不因非零退出码抛异常：提交失败由调用方
     * 解释退出码，见 {@link #commitStaged}。
     *
     * @param workingDirectory 工作目录
     * @param identity 提交署名
     * @param args git 子命令及参数
     * @return 执行结果，包括非零退出码
     * @throws GitCommandException 进程被超时终止
     * @throws com.codeloom.workspace.exec.ProcessException 进程无法启动或等待时被中断
     */
    public ProcessOutcome runAs(Path workingDirectory, CommitIdentity identity, List<String> args) {
        return execute(buildCommand(workingDirectory, identity, args), workingDirectory);
    }

    /**
     * 同 {@link #runAs}，但非零退出码直接抛 {@link GitCommandException}。
     *
     * @param workingDirectory 工作目录
     * @param identity 提交署名
     * @param args git 子命令及参数
     * @return 执行结果
     * @throws GitCommandException 非零退出码或进程被超时终止
     */
    public ProcessOutcome runAsOrThrow(Path workingDirectory, CommitIdentity identity, List<String> args) {
        List<String> command = buildCommand(workingDirectory, identity, args);
        ProcessOutcome outcome = execute(command, workingDirectory);
        if (!outcome.succeeded()) {
            throw new GitCommandException(command, outcome, "git 命令失败");
        }
        return outcome;
    }

    /**
     * 同 {@link #runOrThrow}，但要求输出完整：被截断过就直接失败。
     *
     * <p>用于输出会被解析成数据的地方（文件清单、行数、冲突文件内容）。截断在这里不是
     * "少显示一点"，而是一个看上去正常、其实错误的答案；展示性命令不需要它。
     * 4M 字符对一个被冲突的大文件同样可能不够。
     */
    private ProcessOutcome runWhole(Path directory, List<String> args) {
        List<String> command = buildCommand(directory, null, args);
        ProcessOutcome outcome = execute(command, directory);
        // 先看截断，再看退出码：被截断的输出即使退出码为 0 也不可信
        if (outcome.truncated()) {
            throw new GitCommandException(command, outcome,
                    "git 的输出超过 " + MAX_OUTPUT_CHARS + " 字符被截断了，"
                            + "而这条命令的输出是要拿来解析的 —— 截断的版本会算出一个错的答案");
        }
        if (!outcome.succeeded()) {
            throw new GitCommandException(command, outcome, "git 命令失败");
        }
        return outcome;
    }

    private ProcessOutcome execute(List<String> command, Path workingDirectory) {
        // git 操作由我们自己发起，不接取消信号，只靠类级超时兜底
        ProcessOutcome outcome = ProcessRunner.run(command, workingDirectory, environment(), timeout,
                MAX_OUTPUT_CHARS, outputCharset, CancellationToken.none());

        // 被杀掉的终止是返回值，必须在这里显式拦截：commitStaged 与 merge 都把
        // "退出码 1" 当正常业务路径，超时的 merge 否则会被读成"有冲突"
        if (outcome.termination() != CommandTermination.COMPLETED) {
            throw new GitCommandException(command, outcome,
                    "git 命令超过 " + timeout + " 没有结束，已强制终止进程树");
        }
        return outcome;
    }

    private List<String> buildCommand(Path workingDirectory, CommitIdentity identity, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(gitExecutable);
        command.add("--no-pager");
        // 不为刷新索引而顺手拿锁：后台进程不应干扰前台操作
        command.add("--no-optional-locks");
        if (workingDirectory != null) {
            command.add("-C");
            command.add(workingDirectory.toString());
        }
        for (String setting : hardening()) {
            command.add("-c");
            command.add(setting);
        }
        if (identity != null) {
            command.add("-c");
            command.add("user.name=" + identity.name());
            command.add("-c");
            command.add("user.email=" + identity.email());
        }
        command.addAll(args);
        return command;
    }

    private List<String> hardening() {
        // 从 PERSISTENT_CONFIG 起头，避免抄成两份导致两套行为；每次再给一遍是防它被改回去
        List<String> settings = new ArrayList<>(PERSISTENT_CONFIG);
        settings.addAll(List.of(
                "core.hooksPath=" + hooksDir,
                "core.fsmonitor=false",
                "color.ui=false",
                "advice.detachedHead=false",
                "credential.helper=",
                "protocol.file.allow=never"
        ));
        return settings;
    }

    private Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        // git 绝不能等待输入：等密码的命令会挂住执行线程
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "echo");
        // 不读系统级与用户级配置：行为只由传入的 -c 决定
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", emptyGlobalConfig.toString());
        // 输出不本地化：代码依赖 "nothing to commit" 这类英文串做判断
        env.put("LC_ALL", "C");
        return env;
    }

    // ------------------------------------------------------------------
    // 仓库与基点
    // ------------------------------------------------------------------

    /**
     * 新建项目仓库并立刻打一个基点提交。
     *
     * <p>没有基点提交，两条会话分支就是互不相关的历史，第一次合并会被 git 拒绝。调用方
     * 事先放进目录的内容也会一并进入这个提交；晚一个提交放进去，从基点 checkout 的两棵树
     * 都看不到它。
     *
     * @param repoPath 项目仓库目录
     * @param creator 基点提交的署名，用项目创建者
     * @throws UncheckedIOException 无法创建仓库目录
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void initRepo(Path repoPath, CommitIdentity creator) {
        try {
            Files.createDirectories(repoPath);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建仓库目录: " + repoPath, e);
        }
        // -b 必须显式给出：默认分支名依赖 git 版本和 init.defaultBranch 配置。
        // 名字取 WorkspaceManager.MAIN_BRANCH，合并、同步、归档都按它找分支
        runOrThrow(repoPath, List.of("init", "-q", "-b", WorkspaceManager.MAIN_BRANCH));

        for (String setting : PERSISTENT_CONFIG) {
            int eq = setting.indexOf('=');
            runOrThrow(repoPath, List.of("config", setting.substring(0, eq), setting.substring(eq + 1)));
        }

        // 先登记再提交；目录为空时下面照样产生一笔空提交
        runOrThrow(repoPath, List.of("add", "-A"));
        runAsOrThrow(repoPath, creator, List.of("commit", "-q", "--allow-empty", "-m", "chore: 初始化空项目"));
    }

    /**
     * @param directory 工作目录
     * @param ref 要解析的 ref
     * @return 解析出的对象 id
     * @throws GitCommandException ref 不存在、命令失败或被超时终止
     */
    public String revParse(Path directory, String ref) {
        return runOrThrow(directory, List.of("rev-parse", ref)).stdout().strip();
    }

    // ------------------------------------------------------------------
    // worktree
    // ------------------------------------------------------------------

    /**
     * {@code git worktree add TARGET -b BRANCH BASE_REF}。
     *
     * @param repoPath 主仓库路径
     * @param target 新工作区目录
     * @param branch 新分支名
     * @param baseRef 分支的起点
     * @throws UncheckedIOException 无法创建工作区父目录
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void worktreeAdd(Path repoPath, Path target, String branch, String baseRef) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建工作区父目录: " + target.getParent(), e);
        }
        runOrThrow(repoPath,
                List.of("worktree", "add", "-q", target.toString(), "-b", branch, baseRef));
    }

    /**
     * {@code git worktree remove --force PATH}：删除工作区目录并从主仓库的工作区名册
     * 注销它。
     *
     * <p>必须走 git：主仓库的 {@code .git/worktrees} 里留有记录，只删目录会让
     * {@code git worktree list} 一直列出不存在的路径，同一个键再次注册也会失败。
     * {@code --force} 是必需的，工作区通常有未提交内容。目录已经不存在的调用方走
     * {@link #pruneWorktrees}。
     *
     * @param repoPath 主仓库路径
     * @param worktree 要删除的工作区目录
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void worktreeRemove(Path repoPath, Path worktree) {
        runOrThrow(repoPath, List.of("worktree", "remove", "--force", worktree.toString()));
    }

    /**
     * 清掉工作区名册里路径已经不存在的记录。
     *
     * @param repoPath 主仓库路径
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void pruneWorktrees(Path repoPath) {
        runOrThrow(repoPath, List.of("worktree", "prune"));
    }

    /**
     * 删掉一条分支；失败不抛。
     *
     * <p>调用方要的是"这条分支别再挡着"，分支本来就不存在时目的已经达到。
     *
     * @param repoPath 主仓库路径
     * @param branch 要删除的分支
     */
    public void branchDelete(Path repoPath, String branch) {
        run(repoPath, List.of("branch", "-D", branch));
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /**
     * 删掉未跟踪的文件与目录（{@code git clean -fd}）。
     *
     * <p>回滚必须带上它：{@code reset --hard} 不碰未跟踪文件，而 agent 刚写、还没进入
     * checkpoint 的文件正是未跟踪的。刻意不加 {@code -x}，否则被 gitignore 的
     * {@code node_modules}、{@code target/} 也会被删，回滚一次就要重装依赖。
     *
     * @param worktree 工作区
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void cleanUntracked(Path worktree) {
        runOrThrow(worktree, List.of("clean", "-fd", "-q"));
    }

    /**
     * 把某个提交导出成 zip（{@code git archive}）。
     *
     * <p>由 git 判断"这个提交里有什么"，忽略规则、{@code .gitattributes} 的
     * {@code export-ignore} 和子模块都算在内；导出的是该提交的内容，不是当前磁盘内容。
     *
     * @param repoPath 项目仓库路径
     * @param treeish 提交（{@code main}）或子树（{@code main:untitled}，zip 中不会多出
     *                那一层前缀）
     * @param outputZip 输出文件；必须走文件而不是 stdout，因为 zip 是二进制，文本入口会
     *                  按字符集解码
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void archive(Path repoPath, String treeish, Path outputZip) {
        runOrThrow(repoPath, List.of("archive", "--format=zip",
                "--output=" + outputZip.toAbsolutePath(), treeish));
    }

    /**
     * {@code add -A} 后提交；工作区没有改动时返回当前 HEAD，不报错。
     *
     * @param worktree 工作区
     * @param message 提交信息
     * @param identity 提交署名
     * @return 新提交的 sha，或没有改动时的当前 HEAD
     * @throws GitCommandException 提交失败或被超时终止
     */
    public String commitAll(Path worktree, String message, CommitIdentity identity) {
        // add 不写作者信息，因此不带身份；产生提交的是下一步
        runOrThrow(worktree, List.of("add", "-A"));
        return commitStaged(worktree, message, identity);
    }

    /**
     * 只提交已暂存的内容；用在合并收尾，此时索引已经是裁决结果，不能再 add。
     *
     * @param worktree 工作区
     * @param message 提交信息
     * @param identity 提交署名
     * @return 新提交的 sha，或没有改动时的当前 HEAD
     * @throws GitCommandException 提交失败或被超时终止
     */
    public String commitStaged(Path worktree, String message, CommitIdentity identity) {
        ProcessOutcome outcome = runAs(worktree, identity, List.of("commit", "-q", "-m", message));
        if (!outcome.succeeded()) {
            // 没有改动时 git commit 以退出码 1 结束，这是正常情况；LC_ALL=C 保证英文串稳定
            if (outcome.exitCode() == 1 && outcome.combinedOutput().contains("nothing to commit")) {
                return revParse(worktree, "HEAD");
            }
            throw new GitCommandException(buildCommand(worktree, identity, List.of("commit")),
                    outcome, "提交失败");
        }
        return revParse(worktree, "HEAD");
    }

    // ------------------------------------------------------------------
    // 合并与冲突
    // ------------------------------------------------------------------

    /**
     * 把 {@code ref} 合并进当前分支。
     *
     * <p>{@code --no-commit} 让调用发生在合并提交之前，从而区分快进、需要合并提交与
     * 有冲突三种情况。
     *
     * @param worktree 目标工作区
     * @param ref 源 ref
     * @param identity 提交署名；git 在冲突检测前就需要它
     * @return 合并结果；{@link MergeResult.Status#CONFLICT} 时工作区留下冲突标记，调用方必须继续走
     *         {@link #mergeAbort} 或解决流程
     * @throws GitCommandException 非冲突的失败，或进程被超时终止
     */
    public MergeResult merge(Path worktree, String ref, CommitIdentity identity) {
        String beforeHead = revParse(worktree, "HEAD");
        ProcessOutcome outcome = runAs(worktree, identity,
                List.of("merge", "--no-commit", "--no-edit", ref));

        if (outcome.exitCode() == 1) {
            // 冲突是正常业务路径；headCommit 是合并前的 HEAD，冲突时它本来就没动
            return new MergeResult(MergeResult.Status.CONFLICT, conflictedFiles(worktree), beforeHead);
        }
        if (!outcome.succeeded()) {
            throw new GitCommandException(buildCommand(worktree, identity, List.of("merge", ref)),
                    outcome, "合并失败");
        }

        if (!mergeInProgress(worktree)) {
            // 没有产生合并状态：要么快进（HEAD 动了），要么本来就一致（HEAD 没动）
            String afterHead = revParse(worktree, "HEAD");
            return beforeHead.equals(afterHead)
                    ? new MergeResult(MergeResult.Status.UP_TO_DATE, List.of(), afterHead)
                    : new MergeResult(MergeResult.Status.FAST_FORWARD, List.of(), afterHead);
        }

        String mergeCommit = commitStaged(worktree, "merge: 合并 " + ref, identity);
        return new MergeResult(MergeResult.Status.MERGED, List.of(), mergeCommit);
    }

    /**
     * @param worktree 要检查的工作区
     * @return 正处于合并中途时为 true
     */
    public boolean mergeInProgress(Path worktree) {
        return run(worktree, List.of("rev-parse", "-q", "--verify", "MERGE_HEAD")).succeeded();
    }

    /**
     * 放弃本次合并，把工作区恢复到合并前。
     *
     * @param worktree 合并所在的工作区
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void mergeAbort(Path worktree) {
        runOrThrow(worktree, List.of("merge", "--abort"));
    }

    /**
     * {@code git add -- PATH}：把一个已经解决好的冲突文件标记为已解决。
     *
     * <p>冲突状态记录在索引里；人工裁决后必须 add，只改工作区文件内容不够。
     * {@code --} 不能省：文件名以 {@code -} 开头时会被当成选项。
     *
     * @param worktree 冲突所在的工作区
     * @param path 冲突文件路径
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void stageFile(Path worktree, String path) {
        runOrThrow(worktree, List.of("add", "--", path));
    }

    /**
     * 冲突文件清单，即 UI 上交给用户裁决的列表。
     *
     * @param worktree 要检查的工作区
     * @return 冲突文件路径
     * @throws GitCommandException git 命令失败、输出被截断或被超时终止
     */
    public List<String> conflictedFiles(Path worktree) {
        String output = runWhole(worktree,
                List.of("diff", "--no-ext-diff", "--no-textconv",
                        "--name-only", "--diff-filter=U", "-z")).stdout();
        return splitNul(output);
    }

    /**
     * 取出冲突中某一方的文件内容。
     *
     * <p>从索引取，而不是解析工作区里的 {@code <<<<<<<} 标记；两份内容正是前端并排
     * diff 所需的两个版本。
     *
     * @param worktree 冲突所在的工作区
     * @param stage 2 表示我方，3 表示对方
     * @param path 冲突文件路径
     * @return 该侧的完整内容
     * @throws IllegalArgumentException stage 不是 2 或 3
     * @throws GitCommandException git 命令失败、输出被截断或被超时终止
     */
    public String conflictSide(Path worktree, int stage, String path) {
        if (stage != 2 && stage != 3) {
            throw new IllegalArgumentException("冲突只区分 stage 2(ours) 与 3(theirs)，收到 " + stage);
        }
        return runWhole(worktree, List.of("show", ":" + stage + ":" + path)).stdout();
    }

    /**
     * 按文件粒度裁决冲突：整份文件取某一方的版本。
     *
     * <p>裁决后必须再调 {@link #commitStaged} 收尾合并。
     *
     * @param worktree 冲突所在的工作区
     * @param path 冲突文件路径
     * @param ours true 取我方，false 取对方
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void resolveConflictByChoosingSide(Path worktree, String path, boolean ours) {
        runOrThrow(worktree, List.of("checkout", ours ? "--ours" : "--theirs", "--", path));
        runOrThrow(worktree, List.of("add", "--", path));
    }

    // ------------------------------------------------------------------
    // 回滚
    // ------------------------------------------------------------------

    /**
     * 硬回滚到某个提交；只作用于调用方指定的工作区及其分支。
     *
     * <p>同步带进来的别人的提交会一起从这棵树的分支上退掉，主干和对方的树不受影响，
     * 下次同步可以重新取回；代价是回不到同步前的状态。
     *
     * @param worktree 要重置的工作区
     * @param commitSha 目标提交
     * @throws GitCommandException git 命令失败或被超时终止
     */
    public void resetHard(Path worktree, String commitSha) {
        runOrThrow(worktree, List.of("reset", "--hard", commitSha));
    }

    /**
     * 这一个提交引入的改动。
     *
     * <p>用 {@code SHA^!} 而不是两个提交之间：后者在同步合并之后会把别人的改动算进来；
     * 前者等于"相对第一父的改动"，并且对根提交也成立（两个参数的写法在根提交上会报
     * {@code fatal: ambiguous argument}）。区间约定见
     * {@link WorkspaceManager#changesIntroducedBy}。
     *
     * <p>{@code core.quotepath=false} 避免中文文件名被转义成八进制串。
     *
     * @param directory 工作区
     * @param commitSha 提交 sha
     * @return 改动的文件，按路径排序
     * @throws GitCommandException git 命令失败、输出被截断或被超时终止
     */
    public List<FileChange> numstatOf(Path directory, String commitSha) {
        String output = runWhole(directory, List.of(
                        "-c", "core.quotepath=false",
                        "diff", "--no-ext-diff", "--no-textconv", "--numstat", commitSha + "^!"))
                .stdout();

        // 状态单独问一次：--numstat 只有行数，新建是不同强度的信号（见 FileChange.created）
        Set<String> created = newNameStatusOf(directory, commitSha);

        List<FileChange> changes = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            // 每行是「增、删、路径」三列，制表符分隔；二进制文件的增删列是 -
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            boolean binary = "-".equals(parts[0]) || "-".equals(parts[1]);
            changes.add(new FileChange(parts[2], binary ? 0 : parseInt(parts[0]),
                    binary ? 0 : parseInt(parts[1]), binary, created.contains(parts[2])));
        }
        return List.copyOf(changes);
    }

    /**
     * 这个提交里某个文件改了什么，返回一段 diff 正文。
     *
     * <p>与 {@link #numstatOf} 在普通提交上取同一区间（{@code SHA^ SHA}），因此两者的
     * 统计与正文不会对不上。两者只在根提交上分岔：{@code numstatOf} 在轮内问，工作树
     * 正是这一轮的现状；这里是事后问，两个参数的写法在根提交上明确失败，而不是给出
     * "从起点到现在"的错误正文。
     *
     * <p>带整份文件的上下文，不只用改动附近那三行：中栏点开"这一轮的改动"时，人要
     * 看的是这个文件完整的样子，而不是几段需要再点开的片段。允许截断，所以不走
     * {@link #runWhole}；{@link FileDiff#truncated()} 如实报告。
     *
     * @param directory 工作区
     * @param commitSha 提交
     * @param gitPath 仓库根相对路径
     * @param maxChars 正文字符上限
     * @return 文件 diff
     * @throws GitCommandException 提交取不出，或进程被超时终止
     */
    public FileDiff diffOfFile(Path directory, String commitSha, String gitPath, int maxChars) {
        // 1,000,000 行足够覆盖正常文件，同时给 git 一个有限的上界；真正的输出上限
        // 仍由调用方传进来的 maxChars 兜着
        List<String> command = buildCommand(directory, null, List.of(
                "-c", "core.quotepath=false",
                "diff", "--no-ext-diff", "--no-textconv", "--unified=1000000",
                commitSha + "^", commitSha, "--", gitPath));
        // 不能走 execute：它使用类级大上限，会静默丢掉调用方给的 maxChars，
        // 使 truncated 永远为假
        ProcessOutcome outcome = ProcessRunner.run(command, directory, environment(), timeout,
                maxChars, outputCharset, CancellationToken.none());
        if (outcome.termination() != CommandTermination.COMPLETED) {
            throw new GitCommandTimeoutException(command, outcome,
                    "取这段 diff 超过 " + timeout + " 没有结束，已强制终止进程树");
        }
        if (outcome.exitCode() != 0) {
            throw new GitCommandException(command, outcome,
                    "取不出这个提交里那个文件的改动 —— 提交或不在这棵树里，或路径不对");
        }
        return new FileDiff(outcome.stdout(), outcome.truncated());
    }

    /**
     * 这个提交里新建了哪些文件（git 的 {@code A}）。
     *
     * <p>{@code --numstat} 与 {@code --name-status} 合不到一条命令里，{@code --summary}
     * 又是另一种不易解析的格式，因此分两次询问。
     */
    private Set<String> newNameStatusOf(Path directory, String commitSha) {
        String output = runWhole(directory, List.of(
                        "-c", "core.quotepath=false",
                        "diff", "--no-ext-diff", "--no-textconv",
                        "--name-status", commitSha + "^!"))
                .stdout();

        Set<String> created = new HashSet<>();
        for (String line : output.split("\n")) {
            // 每行是「状态、路径」两列，制表符分隔；A 表示新增，M、D、R100 等都不是
            String[] parts = line.split("\t", 3);
            if (parts.length >= 2 && "A".equals(parts[0])) {
                created.add(parts[1]);
            }
        }
        return created;
    }

    /** numstat 里除 {@code -} 外都是十进制数；读不动按 0 处理，不让一个数字导致整页失败。 */
    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 数一段区间里的提交数，例如 {@code main..BRANCH}。
     *
     * @param directory 工作区
     * @param range git 的区间表达式
     * @return 提交数；输出为空时为 0
     * @throws GitCommandException git 命令失败、输出被截断或被超时终止
     */
    public int countCommits(Path directory, String range) {
        String output = runWhole(directory, List.of("rev-list", "--count", range)).stdout().strip();
        return output.isEmpty() ? 0 : Integer.parseInt(output);
    }

    private static List<String> splitNul(String output) {
        List<String> parts = new ArrayList<>();
        for (String part : output.split("\0", -1)) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }
}
