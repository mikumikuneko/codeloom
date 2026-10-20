package com.codeloom.domain.port;

import com.codeloom.domain.workspace.FileChange;
import com.codeloom.domain.workspace.FileDiff;
import com.codeloom.domain.workspace.WorkspaceId;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 工作区管理：git worktree 的创建、checkpoint、回滚、合并与冲突裁决。git 参数全部由
 * 服务端构造，因此可信；{@link CommandExecutor} 的参数由 agent 构造，必须走白名单和
 * 资源限额，两者不能互相替代，agent 也不能直接访问本接口。调用 git 必须用 {@code -c}
 * 关闭 {@code core.hooksPath} 和 {@code core.fsmonitor}，diff 子命令还必须带
 * {@code --no-ext-diff} 与 {@code --no-textconv}：worktree 内容对 agent 可写，否则它能
 * 通过 hook、外部 diff 或 textconv 程序执行任意代码。{@code filter.*.clean} 没有等价的
 * 单条开关，不能把“已隔离”写进本接口的保证。树的键是 {@link WorkspaceId}（用户 × 项目），
 * 同一人在同一项目里的所有会话共用一棵树，所以不存在“某条会话的分支”：树只有一条分支。
 */
public interface WorkspaceManager {

    /**
     * 初始化项目仓库：建目录、{@code git init -b main}，并立刻打一个基点提交。
     *
     * <p>基点提交是必需的：没有它，两条工作区分支就是两段互不相关的历史，第一次合并
     * 会被 git 拒绝。基点必须一次就是最终形态，因为两棵树都从它 checkout；晚一个提交
     * 才放进去的内容，两边都看不到。这条操作也放在本接口，是为了让“调用 git 只有一个
     * 入口”成立；多开一个入口，“git 参数由服务端构造”就会退化成约定。内容从哪来见
     * {@code docs/decisions/architecture/2026-09-29-untitled-seed-and-empty-readme.md}。
     *
     * @param repoPath       项目仓库目录
     * @param creator        基点提交的署名，用项目创建者
     * @param firstDirectory 新项目自带的顶层目录名，由应用层决定；null 或空白表示不预置
     */
    void initializeRepository(Path repoPath, CommitIdentity creator, String firstDirectory);

    /**
     * 为一棵树创建工作区（{@code git worktree add}）。
     *
     * <p>幂等：目录已经存在时直接读出现状返回，不重复执行 {@code worktree add}。调用方
     * 用它确保“有地方跑”，不必先查库；目录是否还在是磁盘的事实。
     *
     * @param workspaceId 这棵树属于谁、属于哪个项目
     * @param repoPath    项目仓库路径
     * @param baseCommit  出发点；空项目起步时传 null
     * @return 现有或新建的工作区
     */
    Workspace create(WorkspaceId workspaceId, Path repoPath, String baseCommit);

    /**
     * 磁盘上那棵树：在就返回它此刻的样子，不在就是空。
     *
     * <p>它回答磁盘事实，不是库里记录的位置：HEAD 是现读 {@code git rev-parse} 的，所以
     * 同一棵树上别的会话刚提交的内容也看得见。要库里的位置，问 {@link WorkspaceRepository}。
     * 空只有一个含义：目录不在磁盘上了（还没建，或被人删掉）。它不查库，所以“库里有记录、
     * 目录没了”同样返回空；这也是“要不要重建”的判据。要一个能跑的工作区请走
     * {@link #create}，不要拿这个自己判断。
     *
     * @param workspaceId 这棵树属于谁、属于哪个项目
     * @return 那一刻的位置；它是值，不是活指针，下一笔提交就会让它过时
     */
    Optional<Workspace> find(WorkspaceId workspaceId);

    /**
     * 把一棵树的工作区从磁盘上删除：注销它、删掉目录、删掉它的分支。
     *
     * <p>它和“丢弃会话”不是一回事：丢弃会话删的是对话，代码保留；这里删的是代码。
     * 两个调用方（退出项目、删除项目）的含义都是“这个人不再拥有这棵树”。幂等：目录
     * 已经被删过时照样把登记信息清干净，不报错。
     *
     * @param workspaceId 要删除的树
     * @param repoPath    主仓库路径；注销必须在主仓库里做
     */
    void removeWorkspace(WorkspaceId workspaceId, Path repoPath);

    /**
     * 删掉整个项目仓库目录（含 {@code .git}）。
     *
     * <p>必须先 {@link #removeWorkspace} 掉所有树：worktree 是主仓库的兄弟目录，不在
     * 仓库里面；直接删仓库不会带走它们，只会留下无法识别的目录。
     *
     * @param repoPath 项目仓库路径
     */
    void removeRepository(Path repoPath);

    /**
     * 把某个提交（或它的某棵子树）导出成 zip，写进 {@code outputZip}。
     *
     * <p>只读：不碰任何人的工作区，也不写仓库。导出的是该提交里的内容，不是当前磁盘
     * 内容。下载语义见
     * {@code docs/decisions/feature/2026-10-08-download-the-project-as-zip.md}。
     *
     * @param repoPath  项目仓库路径
     * @param treeish   {@code main}，或 {@code main:untitled}（只导一棵子树）
     * @param outputZip 输出文件
     */
    void archive(Path repoPath, String treeish, Path outputZip);

    /**
     * 把工作区的全部改动提交成 checkpoint。
     *
     * <p>每轮结束后自动调用一次（{@code TurnExecutor} 收尾之前）。回滚会把代码重置到
     * 这个提交，并把对话截断到对应 checkpoint 的事件序号；截断点不是这个 sha，因为
     * 一个 sha 可能对应多条 checkpoint（见
     * {@link com.codeloom.domain.event.CheckpointCreated}）。理由见
     * {@code docs/decisions/architecture/2026-09-26-every-turn-commits-and-the-zero-checkpoint.md}。
     *
     * @param workspace 要提交的工作区
     * @param message   提交信息
     * @param identity  提交署名，必须是会话所有者，以便 git log 能看出提交归属；
     *                  刻意不设仓库级身份，见 {@link CommitIdentity}
     * @return 新提交的 sha；工作区没有改动时返回当前 HEAD
     */
    String commit(Workspace workspace, String message, CommitIdentity identity);

    /**
     * 把整棵树重置到指定提交：代码回到该提交的完全一致状态，并清掉未跟踪的文件。
     *
     * <p>树属于一个人（{@link WorkspaceId}），回滚在本人那条分支上进行，不碰主干和
     * 对方的工作区。因此“撤销”就是整块撤销：本人另一条会话之后提交的内容、以及同步
     * 带进来的内容，都一起退掉；别人在主干里的提交和对方那棵树不动，下次同步会重新
     * 拿回来。不要因为“目标之后有别人的提交”就拒绝回滚：那挡住的是正当操作。
     * {@code reset --hard} 不碰未跟踪文件，而 agent 刚写、还没进 checkpoint 的文件
     * 正是未跟踪的；不清掉它们，回滚在最需要它的场景里会什么都不做。被 gitignore 的
     * 文件（{@code node_modules} 等）不动，回滚不应要求重装依赖。理由见
     * {@code docs/decisions/architecture/2026-09-29-rewind-the-whole-tree.md}。
     *
     * @param workspace 要重置的工作区
     * @param commitSha 目标提交
     */
    void resetTo(Workspace workspace, String commitSha);

    /**
     * 把 {@code sourceRef} 分支合并进 {@code targetWorktree} 当前所在的分支。
     *
     * <p>签名以目标为中心，而不是“两个 Workspace 互合”：合回主干时，目标是检出在
     * 项目主仓库里的 {@code main}，不是工作区。日常只有这一个方向，所以调用方直接传
     * 主仓库路径和源分支，不需要额外的 {@code mergeIntoMain} 转发。有冲突就停下，
     * 不自动裁决：代码的语义冲突机器判不了。理由见
     * {@code docs/decisions/architecture/2026-09-25-merge-model-without-pull-requests.md}。
     *
     * @param targetWorktree 目标工作区目录，或项目主仓库的主工作区
     * @param sourceRef      源分支名
     * @param identity       合并提交的署名；即使无冲突也需要，git 在冲突检测之前就会用到
     * @return 合并结果
     */
    MergeResult merge(Path targetWorktree, String sourceRef, CommitIdentity identity);

    /**
     * 这棵树相对 {@code ref} 落后多少个提交；0 表示已经包含 {@code ref}。
     *
     * <p>它是“先同步再合并”的前提：合之前确认没有落后，这一步就是快进，不可能冲突；
     * 于是冲突全部发生在同步那一步、也就是这棵树自己的工作区里，主干始终干净。返回
     * 个数而不是布尔，是为了能直接说“落后 2 个提交”；“冲突了”会让人误以为代码打架，
     * 多数时候只是出发得早。见
     * {@code docs/decisions/architecture/2026-09-29-sync-before-merge.md}。
     *
     * @param worktree 要检查的工作区
     * @param ref      对照的 ref，例如 {@code main}
     * @return 落后的提交数
     */
    int commitsBehind(Path worktree, String ref);

    /**
     * 这一个提交引入的改动：哪些文件、各增删多少行。
     *
     * <p>按提交问，而不是按“两个提交之间”问：历史不直时会夹着从主干同步进来的合并，
     * “两个提交之间”会把别人的改动也算进来，界面会显示这一轮改了 40 个文件，而 agent
     * 实际只动了 2 个。一个提交的第一父提交永远是这棵树的上一个状态，不管它是不是
     * 合并提交，所以按提交问得到的是这一步真正引入的东西。
     *
     * @param worktree  工作区
     * @param commitSha 要看的提交
     * @return 改动的文件，按路径排序；没有改动时是空表
     */
    List<FileChange> changesIntroducedBy(Path worktree, String commitSha);

    /**
     * 这个提交里某个文件改了什么，返回一段 diff 正文。
     *
     * <p>和 {@link #changesIntroducedBy} 是同一件事的两个粒度，问的是同一个区间
     * （{@code SHA^ SHA}），两者不会对不上。正文按需取，不存进事件流：正文只有人点开
     * 某个文件时才需要，塞进事件会让所有读取路径都为它买单。理由见
     * {@code docs/decisions/feature/2026-10-10-file-diff-is-fetched-on-demand.md}。
     *
     * @param worktree 工作区
     * @param commitSha 提交
     * @param gitPath  git 能识别的仓库根相对路径；项目路径到 git 路径的转换在 {@code ProjectLayout#toGitPath}
     * @param maxChars 正文字符上限；超出时截断并如实标记，不报错
     * @return 文件 diff
     */
    FileDiff diffOfFile(Path worktree, String commitSha, String gitPath, int maxChars);

    // ------------------------------------------------------------------
    // 冲突裁决
    //
    // 这一组操作的是一个普通的 git 工作目录：同步的冲突落在树的工作区，合回的冲突
    // 落在主干；调用方按当前挂着的合并决定传哪个。放在本接口的理由同
    // initializeRepository：调用 git 只能有一个入口。
    // ------------------------------------------------------------------

    /**
     * 这个工作目录当前是否处于合并中途。
     *
     * @param worktree 要检查的工作目录
     * @return 有未完成合并时为 true
     */
    boolean mergeInProgress(Path worktree);

    /**
     * 冲突文件清单，也就是界面上交给用户裁决的那份列表。
     *
     * @param worktree 要检查的工作目录
     * @return 冲突文件路径
     */
    List<String> conflicts(Path worktree);

    /**
     * 冲突中某一方的内容。
     *
     * <p>取索引里的那一份（stage 2/3），不是工作区文件里的 {@code <<<<<<<} 标记文本：
     * 后者要自己解析，而且不同的 merge driver 可能不写它。这两份正是前端并排 diff
     * 需要的两个版本。
     *
     * @param worktree 冲突所在的工作目录
     * @param path     冲突文件
     * @param ours     true = 我方（当前分支），false = 对方（被合进来的分支）
     * @return 该侧内容
     */
    String conflictSide(Path worktree, String path, boolean ours);

    /**
     * 用某一侧的内容了结一个文件的冲突。
     *
     * @param worktree 冲突所在的工作目录
     * @param path     冲突文件
     * @param ours     true = 取我方，false = 取对方
     */
    void resolveConflict(Path worktree, String path, boolean ours);

    /**
     * 用人给出的完整内容了结一个文件的冲突。
     *
     * <p>整份取一侧不够：两个人各往同一文件加一个方法时，保留两边才对；同一行被改成
     * 不同内容时，正确答案也常常不是任何一侧原样。服务端写入内容后执行 {@code git add}；
     * 在 git 的语义里，add 一个冲突文件就是“已解决”。
     *
     * @param worktree 冲突所在的工作目录
     * @param path     冲突文件
     * @param content  人给出的完整内容
     */
    void resolveConflictByContent(Path worktree, String path, String content);

    /**
     * 冲突全部裁决完后收尾：提交索引里已经正确的合并结果。
     *
     * <p>不能用 {@link #commit}：它会先 {@code add -A}，而合并后的索引已经是裁决结果；
     * 再 add 一遍会把“这次合并改了什么”交给工作区判断，两者不一致时（例如裁决后又
     * 手改了文件）结果就会漂移。
     *
     * @param worktree 合并所在的工作目录
     * @param message  合并提交信息
     * @param identity 提交署名
     * @return 合并提交的 sha
     */
    String finishMerge(Path worktree, String message, CommitIdentity identity);

    /**
     * 放弃本次合并，工作区恢复到合并前。
     *
     * @param worktree 合并所在的工作目录
     */
    void abortMerge(Path worktree);

    /** 主干的固定分支名；所有工作区由它分出，也都合并回它。 */
    String MAIN_BRANCH = "main";
}
