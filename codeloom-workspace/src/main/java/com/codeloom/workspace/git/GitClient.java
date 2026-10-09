package com.codeloom.workspace.git;

import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommitIdentity;
import com.codeloom.domain.port.CommandTermination;
import com.codeloom.domain.port.MergeResult;
import com.codeloom.domain.workspace.FileChange;
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
 * 直接调用 {@code git} 可执行文件，把实验里踩到的坑全部固化成默认行为。
 *
 * <h2>为什么 shell 调 git 而不是用 JGit</h2>
 * 本项目的并发一致性模型压在 {@code git worktree} 上，而 git 的 **linked worktree**
 * （独立 HEAD、独立索引、{@code .git} 是个指向主仓库的**文件**）在 JGit 里支持不完整 ——
 * JGit 的 {@code WorkTree} 类指的是"仓库的工作区"，不是同一个东西。用 JGit 就得自己实现
 * worktree 语义，而那正是这套并发模型赖以成立的核心。
 *
 * <h2>为什么每一处调用都带一长串 {@code -c} 硬化参数</h2>
 * worktree 里的内容对 agent 可写，而 git 有一堆能执行任意命令的配置项
 * （{@code core.hooksPath}、{@code core.fsmonitor}、{@code diff.external}、
 * {@code filter.*.clean}）。虽然 worktree 的 {@code .git} 是个文件、agent 碰不到主仓库配置，
 * 但这里仍然显式中和一遍 —— 纵深防御的成本是一行参数。
 *
 * <h2>实验固化的五条</h2>
 * <ol>
 *   <li>新建仓库后立刻打一个空提交作基点，否则两条会话分支"历史不相关"、无法合并</li>
 *   <li>{@code git init} 必须显式 {@code -b main}，默认分支名依赖版本和配置</li>
 *   <li>每次提交**和每次 merge** 都要传身份；不设仓库级身份，忘了会响亮失败
 *       （代码里就是 {@code runAs*} 那一对入口：身份只出现在会产生提交的命令上）</li>
 *   <li>退出码 1（冲突）和 128（致命）必须分开处理</li>
 *   <li>{@code core.autocrlf=false}，否则每次改动在 diff 里显示成全文变化</li>
 * </ol>
 */
public final class GitClient {

    /** git 卡住时的兜底时限。正常操作都在毫秒级，只有大仓库的首次 checkout 会慢。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private static final int MAX_OUTPUT_CHARS = 4 * 1024 * 1024;

    /** 会被持久化进仓库配置的项 —— 让用户自己用 IDE 或命令行碰这个仓库时行为也一致。 */
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

    public GitClient(String gitExecutable, Path sandboxDir) {
        // 和 LocalCommandExecutor 同一条规则：先按 UTF-8 解，解不出来按本机编码兜底。
        // 对 git 来说重要的是**文件内容**（合并冲突时那些中文行），它们是什么编码
        // 取决于用户的仓库，不是我们说了算 —— 所以更不该在这里固定成 UTF-8
        this(gitExecutable, sandboxDir, ProcessRunner.nativeCharset(), DEFAULT_TIMEOUT);
    }

    /**
     * @param fallbackCharset 输出**不是合法 UTF-8** 时的兜底字符集。规则见 {@link ProcessRunner}：
     *                        先按 UTF-8 解，解不出来再用它重解一遍
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
     * 执行一条 git 子命令，**永远返回结果，不对非零退出码抛异常**。
     *
     * <p>退出码的含义由调用方解释 —— 见 {@link GitCommandException} 的说明。
     *
     * <p>这条入口不带身份：读命令，以及 {@code add}、{@code checkout}、{@code reset}、
     * {@code clean} 这类不写作者信息的写命令，都走它。
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
     * 带身份执行：{@code -c user.name=… -c user.email=…} 会加在命令前面。
     *
     * <h2>只有会产生提交的命令该用它</h2>
     * 仓库级身份是**故意不设**的（见类注释第 3 条），所以谁产生提交谁把身份带上 ——
     * 眼下就是 {@code commit} 和 {@code merge} 这两条。**其余命令一律用 {@link #run} /
     * {@link #runOrThrow}**：给一条读命令多带一份身份不会报错，只会让人从代码上看不出
     * "那次提交是谁写的"，而这件事正是身份这套东西存在的全部理由。
     *
     * <p>它和 {@link #run} 一样**不抛**：提交失败（没有改动、被 hook 拦下）由调用方
     * 按退出码解释 —— 见 {@link #commitStaged}。
     */
    public ProcessOutcome runAs(Path workingDirectory, CommitIdentity identity, List<String> args) {
        return execute(buildCommand(workingDirectory, identity, args), workingDirectory);
    }

    /** 同 {@link #runAs}，但非零退出码直接抛 {@link GitCommandException}。 */
    public ProcessOutcome runAsOrThrow(Path workingDirectory, CommitIdentity identity, List<String> args) {
        List<String> command = buildCommand(workingDirectory, identity, args);
        ProcessOutcome outcome = execute(command, workingDirectory);
        if (!outcome.succeeded()) {
            throw new GitCommandException(command, outcome, "git 命令失败");
        }
        return outcome;
    }

    /**
     * 同 {@link #runOrThrow}，但**要求输出是完整的** —— 被截断过就直接失败。
     *
     * <h2>用在哪</h2>
     * 用在"输出会被解析成数据"的地方：{@link #numstatOf} 数行数和文件名、
     * {@link #conflictedFiles} 列冲突文件、{@link #countCommits} 读一个数、
     * {@link #conflictSide} 取整份文件内容。展示性的命令（比如 {@code git log}）
     * 不需要它 —— 那种地方少显示一截是能接受的。
     *
     * <h2>为什么宁可失败</h2>
     * 这些地方被截断的后果不是"少显示一点"，而是**一个看上去正常、其实是错的答案**：
     * 少列一个冲突文件、少算几行、把一份被拦腰截断的代码摆出来让人挑"留哪一边"。
     * 而没有任何地方会因此报错 —— 最坏的那种 bug。
     *
     * <p>4M 字符的上限对"一条提交改十万个文件"来说够宽，但对**一个被冲突的大文件**
     * （锁文件、打包产物、生成出来的 JSON）正好不够 —— 所以这不是假想。
     */
    private ProcessOutcome runWhole(Path directory, List<String> args) {
        List<String> command = buildCommand(directory, null, args);
        ProcessOutcome outcome = execute(command, directory);
        // 先看截断，再看退出码：被截断的输出**即使退出码是 0** 也不可信
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
        // git 操作是我们自己发起的、参数可信、耗时可预期，所以不接取消信号，
        // 只靠类级超时兜底。用户按 Esc 影响的应该是 agent 的工具调用，不是仓库维护操作。
        ProcessOutcome outcome = ProcessRunner.run(command, workingDirectory, environment(), timeout,
                MAX_OUTPUT_CHARS, outputCharset, CancellationToken.none());

        // **我们把它杀掉的，不是它自己结束的 —— 在这里就断掉。**
        //
        // 这一层必须显式判断：那两种终止是**返回值**（因为部分输出得还给调用方），
        // 不会再以异常形式冒出来。少了它的后果很具体：下面 commitStaged 和 merge 都拿
        // "退出码 1"当正常业务路径（没有改动 / 有冲突），而一条**超时的** merge 会被读成
        // "有冲突"—— 那会让人去解决一堆不存在的冲突。
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
        // 不为了刷新索引而顺手拿锁：后台进程不该干扰前台操作
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
        return List.of(
                "core.autocrlf=false",
                "core.longpaths=true",
                "core.symlinks=false",
                "core.hooksPath=" + hooksDir,
                "core.fsmonitor=false",
                "color.ui=false",
                "advice.detachedHead=false",
                "credential.helper=",
                "protocol.file.allow=never"
        );
    }

    private Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        // 绝不能让 git 等待输入 —— 一个等密码的命令会把执行线程永远挂住
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "echo");
        // 不读系统级与用户级配置：行为完全由我们传的 -c 决定，不受机器上别的配置影响
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", emptyGlobalConfig.toString());
        // 输出不本地化。这不只是好看 —— 我们靠 "nothing to commit" 这类英文串做判断
        env.put("LC_ALL", "C");
        return env;
    }

    // ------------------------------------------------------------------
    // 仓库与基点
    // ------------------------------------------------------------------

    /**
     * 新建项目仓库，并**立刻打一个基点提交**。
     *
     * <p>那个提交不是可有可无的形式：没有它，两条会话分支就是两段互不相关的历史，
     * 第一次合并时 git 会直接拒绝（{@code refusing to merge unrelated histories}）。
     * 而"项目从空目录起步"正意味着创建时没有任何提交 —— 所以必须由我们补上这个基点。
     *
     * <p>开始之前已经躺在目录里的东西<strong>一并进这个基点提交</strong>：调用方有时会
     * 先放好新项目该有的文件（见 {@code LocalWorkspaceManager.initializeRepository}）。
     * 晚一个提交放进去的话，从基点 checkout 出来的两棵树里都没有它。
     *
     * @param creator 基点提交的署名，用项目创建者
     */
    public void initRepo(Path repoPath, CommitIdentity creator) {
        try {
            Files.createDirectories(repoPath);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建仓库目录: " + repoPath, e);
        }
        // -b main 必须显式给：默认分支名依赖 git 版本和 init.defaultBranch 配置
        runOrThrow(repoPath, List.of("init", "-q", "-b", "main"));

        for (String setting : PERSISTENT_CONFIG) {
            int eq = setting.indexOf('=');
            runOrThrow(repoPath, List.of("config", setting.substring(0, eq), setting.substring(eq + 1)));
        }

        // 先登记再提交。目录里什么都没有时这条不产生任何改动，下面照样是一笔空提交
        runOrThrow(repoPath, List.of("add", "-A"));
        runAsOrThrow(repoPath, creator, List.of("commit", "-q", "--allow-empty", "-m", "chore: 初始化空项目"));
    }

    public String revParse(Path directory, String ref) {
        return runOrThrow(directory, List.of("rev-parse", ref)).stdout().strip();
    }

    // ------------------------------------------------------------------
    // worktree
    // ------------------------------------------------------------------

    /** {@code git worktree add <target> -b <branch> <baseRef>} */
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
     * {@code git worktree remove --force <path>}：删掉一棵树的工作区目录，并从主仓库的
     * 工作区名册里注销它。
     *
     * <h2>为什么必须走 git 而不是直接删目录</h2>
     * 主仓库的 {@code .git/worktrees/<名字>} 里留着这个工作区的记录（HEAD、索引、
     * 各种管理文件）。只删目录的话，那条记录还在，于是 {@code git worktree list}
     * 会一直列出一个不存在的路径，而**同一个键再建一次会失败**（"已经注册过"）。
     *
     * <p>{@code --force} 是必需的：工作区里通常有未提交的东西，没有它 git 会拒。
     *
     * <p>目录已经不在了的调用方走 {@link #pruneWorktrees} ——
     * 对它用这条会以一个"路径不存在"的错误结束，而那只是个需要清理的名册。
     */
    public void worktreeRemove(Path repoPath, Path worktree) {
        runOrThrow(repoPath, List.of("worktree", "remove", "--force", worktree.toString()));
    }

    /** 清掉名册里那些路径已经不存在的记录。 */
    public void pruneWorktrees(Path repoPath) {
        runOrThrow(repoPath, List.of("worktree", "prune"));
    }

    /**
     * 删掉一条分支。**失败不抛**：调用方要的是"这条分支别再挡着"，
     * 而它本来就不存在时，目的已经达到了。
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
     * <p>回滚必须带上它：{@code reset --hard} **不碰未跟踪的文件**，而 agent 刚写出来、
     * 还没被任何 checkpoint 收进去的那些文件恰恰就是未跟踪的。只 reset 的话，
     * "回滚"在最需要它的那个场景里恰好什么都没回滚 —— 用户看着文件还在，会以为功能坏了。
     *
     * <p>刻意**不加 {@code -x}**：那会连被 gitignore 的文件一起删（{@code node_modules}、
     * {@code target/} 之类），回滚一次就要重装一次依赖。不加则只删"本该进版本库、
     * 却还没进去"的东西 —— 那正好是我们要撤销的那些。
     */
    public void cleanUntracked(Path worktree) {
        runOrThrow(worktree, List.of("clean", "-fd", "-q"));
    }

    /**
     * 把某个提交导出成 zip（{@code git archive}）—— 界面上那个"下载"。
     *
     * <p>为什么不自己遍历文件打包：**"这个提交里有什么" git 比我们清楚** —— 忽略规则、
     * {@code .gitattributes} 里的 {@code export-ignore}、子模块都算在内，而"下载这个项目"
     * 要的正是这个语义。它读的是那个提交，不是现在磁盘上有什么。
     *
     * @param treeish   导出的对象：提交（{@code main}），或者它的某棵子树（{@code main:untitled} ——
     *                  只导那个目录，zip 里就不会多出那一层前缀）
     * @param outputZip 输出文件。**必须走文件**：这条路吐的是二进制，而另外几个入口会把
     *                  stdout 按字符集解码 —— 二进制过一趟字符集就是坏数据。{@code --output}
     *                  让 git 自己写文件，stdout 保持空，那条文本通路就绕开了
     */
    public void archive(Path repoPath, String treeish, Path outputZip) {
        runOrThrow(repoPath, List.of("archive", "--format=zip",
                "--output=" + outputZip.toAbsolutePath(), treeish));
    }

    /** {@code add -A} 后提交。工作区没有改动时返回当前 HEAD，不报错。 */
    public String commitAll(Path worktree, String message, CommitIdentity identity) {
        // 这条 add 不带身份：它不写作者信息，产生提交的是下面那一步
        runOrThrow(worktree, List.of("add", "-A"));
        return commitStaged(worktree, message, identity);
    }

    /** 只提交已暂存的内容（合并完成后用这个，索引已经是对的，不能再 add）。 */
    public String commitStaged(Path worktree, String message, CommitIdentity identity) {
        ProcessOutcome outcome = runAs(worktree, identity, List.of("commit", "-q", "-m", message));
        if (!outcome.succeeded()) {
            // 没有改动时 git commit 以退出码 1 结束 —— 那是正常情况，不是冲突也不是错误。
            // 靠 LC_ALL=C 保证这句英文串稳定可匹配。
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
     * <p>用 {@code --no-commit} 是为了**在产生合并提交之前停下来看一眼**：
     * 这样能区分"快进""需要合并提交""有冲突"三种情况，而不是让 git 一口气做完。
     *
     * @return 四种状态之一；{@code CONFLICT} 时**不改动工作区之外的状态**，
     *         但工作区里会留下冲突标记 —— 调用方必须接着调 {@link #mergeAbort}
     *         或走解决流程，不能放着不管
     */
    public MergeResult merge(Path worktree, String ref, CommitIdentity identity) {
        String beforeHead = revParse(worktree, "HEAD");
        ProcessOutcome outcome = runAs(worktree, identity,
                List.of("merge", "--no-commit", "--no-edit", ref));

        if (outcome.exitCode() == 1) {
            // 冲突是正常业务路径，不是异常。
            // headCommit 报的是**合并前**那个 —— 冲突时 HEAD 本来就没动
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

    /** 是否正处于合并中途（有未提交的合并）。 */
    public boolean mergeInProgress(Path worktree) {
        return run(worktree, List.of("rev-parse", "-q", "--verify", "MERGE_HEAD")).succeeded();
    }

    /** 放弃本次合并，把工作区恢复到合并前。 */
    public void mergeAbort(Path worktree) {
        runOrThrow(worktree, List.of("merge", "--abort"));
    }

    /**
     * {@code git add -- <path>}：把一个已经解决好的冲突文件标记为已解决。
     *
     * <p>在 git 的语义里，"add 一个冲突文件"就是"这个文件我处理完了" ——
     * 冲突的标志存在**索引**里（stage 1/2/3 那几份），而不在工作区的文件内容里。
     * 所以人工裁决完之后必须这一步，光把文件改成对的内容是不够的。
     *
     * <p>{@code --} 不能省：文件名以 {@code -} 开头时，不隔开会被当成选项。
     */
    public void stageFile(Path worktree, String path) {
        runOrThrow(worktree, List.of("add", "--", path));
    }

    /** 冲突文件清单 —— 就是 UI 上要交给用户裁决的那份列表。 */
    public List<String> conflictedFiles(Path worktree) {
        String output = runWhole(worktree,
                List.of("diff", "--name-only", "--diff-filter=U", "-z")).stdout();
        return splitNul(output);
    }

    /**
     * 取出冲突中某一方的文件内容。
     *
     * <p>索引里的 stage 2 是"我方"，stage 3 是"对方"。**从索引取比解析工作区文件里的
     * {@code <<<<<<<} 标记可靠得多**，而且这正是前端并排 diff 需要的两份数据。
     *
     * @param stage 2 = ours，3 = theirs
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
     * <p>这是 MVP 的最小可行裁决方式 —— 前端是只读查看器，用户没法在界面上手工编辑
     * 冲突文件。用户选完之后必须再调 {@link #commitStaged} 把合并收尾。
     */
    public void resolveConflictByChoosingSide(Path worktree, String path, boolean ours) {
        runOrThrow(worktree, List.of("checkout", ours ? "--ours" : "--theirs", "--", path));
        runOrThrow(worktree, List.of("add", "--", path));
    }

    // ------------------------------------------------------------------
    // 回滚
    // ------------------------------------------------------------------

    /**
     * 硬回滚到某个 commit。
     *
     * <h2>它只动调用方指定的那个工作区，所以不会波及任何人</h2>
     * 每条会话都在一棵**属于某个人**的树里干活（见 {@code WorkspaceId}），
     * 而 reset 只在这棵树的分支上做。主干和别人的工作区都不在这条路径上。
     *
     * <p>把主干同步进来确实会让这棵树上带一批别人的提交，但把它们从**我的**分支上退掉，
     * 丝毫不动他们在主干里的代码、也不动他们自己那棵树 —— 我下次同步就能拿回来。
     * 代价是我再也回不到同步前的状态。
     */
    public void resetHard(Path worktree, String commitSha) {
        runOrThrow(worktree, List.of("reset", "--hard", commitSha));
    }

    /**
     * 这一个提交引入的改动。
     *
     * <h2>为什么是 {@code <sha>^!}，不是 {@code <from> <to>}</h2>
     * 前半句的理由见 {@code WorkspaceManager#changesIntroducedBy}：中间夹过一次同步进来的
     * 合并时，"两个提交之间"会把别人的改动也算进来，而"这个提交相对它第一父的改动"不会。
     *
     * <p>{@code ^!} 和写成两个参数（{@code <sha>^ <sha>}）是同一个意思，但它**对根提交也成立** ——
     * 而两个参数那种写法在根提交上直接 {@code fatal: ambiguous argument}。它**每一轮收尾都要
     * 跑一次**（协作提醒要报新建的文件），所以"项目刚建出来、第一轮还没产生新提交"这种最平常的
     * 情况也会走到这里。
     *
     * <p>{@code core.quotepath=false}：不加的话，中文文件名会被 git 转义成
     * `"\344\275\240\345\245\275.txt"` 这种八进制串 —— 而那正是这个项目里最常见的路径。
     *
     * @param commitSha 提交 sha
     * @return 改动的文件，按路径排序（git 自己就是按路径出的）
     */
    public List<FileChange> numstatOf(Path directory, String commitSha) {
        String output = runWhole(directory, List.of(
                        "-c", "core.quotepath=false",
                        "diff", "--numstat", commitSha + "^!"))
                .stdout();

        // **状态要单独问一次** —— `--numstat` 只说行数，不说"这是新建还是修改"。
        // 而"新建"是个不同强度的信号（见 FileChange.created），拿行数去猜是不准的
        Set<String> created = newNameStatusOf(directory, commitSha);

        List<FileChange> changes = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            // 每行是「增<TAB>删<TAB>路径」。二进制文件的增删是 `-`，不是数字
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
     * 这个提交里**新建**了哪些文件（git 的 {@code A}）。
     *
     * <p>同一次 diff 分两次问是有意的：`--numstat` 和 `--name-status` 是两个开关，
     * 合不到一条命令里，而 `--summary` 给的又是另一种格式（不好稳地解析）。
     * 两次 git 进程换一段直白的代码，值得。
     */
    private Set<String> newNameStatusOf(Path directory, String commitSha) {
        String output = runWhole(directory, List.of(
                        "-c", "core.quotepath=false",
                        "diff", "--name-status", commitSha + "^!"))
                .stdout();

        Set<String> created = new HashSet<>();
        for (String line : output.split("\n")) {
            // 每行是「状态<TAB>路径」。`A` 是新增；`M`/`D`/`R100` 之类都不是
            String[] parts = line.split("\t", 3);
            if (parts.length >= 2 && "A".equals(parts[0])) {
                created.add(parts[1]);
            }
        }
        return created;
    }

    /** git 的 numstat 里除了 `-` 都是十进制数。读不动就当 0 —— 少一个数字不该让整页打不开。 */
    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code main..<branch>} 之间有多少提交（用于"你的会话落后主干 N 个"）。 */
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
