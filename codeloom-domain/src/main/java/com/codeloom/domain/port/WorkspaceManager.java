package com.codeloom.domain.port;

import com.codeloom.domain.workspace.FileChange;
import com.codeloom.domain.workspace.FileDiff;
import com.codeloom.domain.workspace.WorkspaceId;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 工作区管理：git worktree 的创建、checkpoint、回滚、三方合并、冲突裁决。
 *
 * <h2>它和 {@link CommandExecutor} 是两条物理隔离的路径</h2>
 * 本接口的所有 git 命令**参数由我们构造**，是可信的，直接走进程调用。
 * 而 {@link CommandExecutor} 的参数**由 agent 构造**，必须走白名单 + 资源限额。
 * 绝不能把 git 操作塞进通用命令执行器（白名单立刻形同虚设），也绝不能让 agent 碰本接口。
 *
 * <h2>调用 git 时的一个安全要求（实现方必须遵守）</h2>
 * worktree 里的内容对 agent 可写，而 git 有一堆能执行任意命令的配置项：
 * {@code core.hooksPath}、{@code diff.external}、{@code core.fsmonitor}、
 * {@code filter.*.clean}。所以调用 git 时必须显式用 {@code -c} 把这些堵死。
 * 否则 agent 只要能影响到 {@code .git/config} 或放一个 hook 进去，就是任意代码执行。
 *
 * <h2>一棵树，不是一条会话</h2>
 * 键是 {@link WorkspaceId}（用户 × 项目）。同一个人在同一项目里的所有会话共用一棵树，
 * 所以「这条会话的分支」这种说法在本接口里不存在 —— 树只有一条分支，它属于一棵树。
 */
public interface WorkspaceManager {

    /**
     * 初始化一个项目的仓库：建目录、{@code git init -b main}、并**立刻打一个基点提交**。
     *
     * <p>那个基点提交不是形式：没有它，两条工作区分支就是两段互不相关的历史，
     * 第一次合并时 git 会直接拒绝。所以基点必须由我们补上 ——
     * 而且它必须**一次就是最终的样子**：两棵树都是从基点 checkout 出来的，
     * 晚一个提交才放进去的东西，两边都看不到。
     *
     * <p>为什么这条也在本接口里、而不是让应用层直接调 git 客户端：
     * 这个接口的类注释里那条约束 —— **git 参数由我们构造，所以是可信的** ——
     * 成立的前提是"调用 git 这件事只有一个出口"。多开一个出口，
     * 那条约束就退化成了一句约定。
     *
     * @param creator        基点提交的署名，用项目创建者
     * @param firstDirectory 新项目自带的那个顶层目录名。**名字由应用层定**（它才知道
     *                       新项目该带什么），而"怎么让一个空目录在 git 里活下来"
     *                       是实现方的事 —— 见 {@code LocalWorkspaceManager} 里那段注释。
     *                       null 或空白 = 不预置目录
     */
    void initializeRepository(Path repoPath, CommitIdentity creator, String firstDirectory);

    /**
     * 为一棵树创建它的工作区（{@code git worktree add}）。
     *
     * <p>幂等：目录已经在就说明建过了，直接读出现状返回，不重复 {@code worktree add}。
     * 调用方拿它当"确保有地方跑"的入口 —— 而"目录还在不在"是**磁盘**的事，
     * 不该让调用方先查一次库再决定要不要建。
     *
     * @param repoPath   项目仓库路径
     * @param baseCommit 出发点；空项目起步时传 null
     */
    Workspace create(WorkspaceId workspaceId, Path repoPath, String baseCommit);

    /**
     * 磁盘上那棵树：**在就返回它此刻的样子，不在就是空**。
     *
     * <p>它回答的是**磁盘上的真相**，不是库里记着的那一份 —— HEAD 是现读
     * {@code git rev-parse} 出来的，所以同一棵树上别的会话刚提交的东西也看得见
     *（一个 {@link Workspace} 有两副面孔，见它的类注释）。要库里记着的那个位置，
     * 去问 {@code WorkspaceRepository}。
     *
     * <p>**空只有一个意思：那个目录不在磁盘上了**（还没建，或者被人删掉）。它不查库，
     * 所以"库里有记录、目录没了"同样是空 —— 而这正是"要不要重建"的判据。
     * 要一个能跑的工作区请走 {@link #create}（它幂等、目录不在就建），别拿这个自己判。
     *
     * @return 那一刻的位置；**它是个值，不是活指针** —— 下一笔提交就会让它过时
     */
    Optional<Workspace> find(WorkspaceId workspaceId);

    /**
     * 把一棵树的工作区**从磁盘上拿掉**：注销它、删掉那个目录、顺带删掉它的分支。
     *
     * <h2>它和「丢弃会话」不是一回事</h2>
     * 丢弃会话删的是对话，**代码留着**；这一个删的就是代码。所以它只有两个调用方，
     * 而那两个都意味着同一个意思：**这个人不再拥有这棵树了** —— 退出项目、删除项目。
     *
     * <p>幂等：目录已经被人删过时照样把名册清干净，不报错。
     *
     * @param repoPath 主仓库路径。注销要在主仓库里做 —— 工作区自己够不着那份名册
     */
    void removeWorkspace(WorkspaceId workspaceId, Path repoPath);

    /**
     * 删掉整个项目仓库目录（含 {@code .git}）。
     *
     * <p><strong>必须先 {@link #removeWorkspace} 掉所有的树。</strong>
     * worktree 是主仓库的<strong>兄弟目录</strong>，不在它里面（见实现类的目录布局）——
     * 删掉仓库不会带走它们，那些目录会变成一堆谁也认不出来的垃圾躺在那儿。
     */
    void removeRepository(Path repoPath);

    /**
     * 把某个提交（或它的某棵子树）导出成 zip，写进 {@code outputZip}。
     *
     * <p>它对调用方的两条含义：**只读**（不碰任何人的工作区、不写仓库），以及它导的是
     * **那个提交里的内容**，而不是现在磁盘上有什么。
     *
     * @param treeish {@code main}，或 {@code main:untitled}（只导一棵子树）
     */
    void archive(Path repoPath, String treeish, Path outputZip);

    /**
     * 把当前工作区的全部改动提交，作为 checkpoint。
     *
     * <p>**每一轮跑完之后**自动调用一次（在 {@code TurnExecutor} 收尾之前）。
     * 回滚 = 代码 reset 到这个 commit，**加上**对话历史截断到这条 checkpoint 的位置，
     * 两者绑死。截断点是**那条 checkpoint 的事件序号**，不是这个 sha ——
     * 一轮什么都没改时返回的还是上一个 HEAD，于是同一个 sha 在一条会话里出现好几次，
     * 靠它定位会退到最早那个位置。见 {@link com.codeloom.domain.event.SessionRewound}。
     *
     * @param identity 提交署名。必须是**会话所有者**，这样 git log 里能看出
     *                 每笔提交是哪位用户的哪条会话产出的 —— 这是可审计性的落点。
     *                 刻意不设仓库级身份，见 {@link CommitIdentity}
     * @return 新 commit 的 sha；工作区没有改动时返回当前 HEAD
     */
    String commit(Workspace workspace, String message, CommitIdentity identity);

    /**
     * 把**整棵树**重置到那个提交：代码回到那个 commit 的**完全一致**状态，并清掉未跟踪的文件。
     *
     * <h2>为什么是"整棵树"而不是"这条会话动过的文件"</h2>
     * 一棵树属于**一个人**（{@link WorkspaceId}），回滚是在**他自己那条分支**上做的：
     * 主干、对方的工作区都不在这条路径上。所以对同一个人来说，**"撤销"就该整块撤销** ——
     * 包括他自己另一条会话在那之后提交的、以及同步带进来的。
     *
     * <p>它**不碰别人**，所以调用方不需要先做任何检查：把主干同步进来会让这棵树上带一批
     * 别人的提交，但把它们从我的分支上退掉，丝毫不动他们在主干里的代码、也不动他们自己
     * 那棵树 —— 下次同步就拿回来了。反过来，"回不到同步前"才是实打实的损失，
     * 所以**别加**"目标之后有别人的提交就拒绝"这道检查：它守的是一个不会发生的伤害，
     * 代价却是挡住一个正当操作。
     *
     * <h2>未跟踪的那些为什么要一起清</h2>
     * {@code reset --hard} **不碰未跟踪的文件**，而 agent 刚写出来、还没被任何 checkpoint
     * 收进去的东西恰恰就是未跟踪的。不清的话，"回滚"在最需要它的那个场景里恰好什么都没做 ——
     * 用户看着文件还在，会以为功能坏了。
     * 被 gitignore 的文件（{@code node_modules} 之类）**不动**：回滚一次不该要重装依赖。
     */
    void resetTo(Workspace workspace, String commitSha);

    /**
     * 把 {@code sourceRef} 这个分支合并进 {@code targetWorktree} 当前所在的分支。
     *
     * <p>签名以**目标**为中心，而不是"两个 Workspace 互合"。因为两个方向的目标形态
     * 并不对称：合回主干时，目标是被检出在项目主仓库里的 {@code main}，
     * 它不是一个工作区。日常用法就只有这一个方向，所以调用方直接写
     * {@code merge(项目主仓库的路径, workspace.branch(), identity)} ——
     * 不为它单独包一个 {@code mergeIntoMain}，那个方法只会多一层要读的转发。
     *
     * <p>有冲突就停下、不自动裁决 —— 代码的语义冲突机器判不了。
     *
     * @param targetWorktree 目标工作区目录（某棵树的工作区，或项目主仓库的主工作区）
     * @param sourceRef      源分支名
     * @param identity       合并提交的署名。**即使无冲突也需要** —— git 在冲突检测之前
     *                       就要用提交者身份，缺了会以退出码 128 直接失败
     */
    MergeResult merge(Path targetWorktree, String sourceRef, CommitIdentity identity);

    /**
     * 这棵树相对 {@code ref} **落后多少个提交**。0 表示它已经包含 {@code ref}。
     *
     * <h2>它是"先同步再合"那道顺序的前提</h2>
     * 合之前先确认"我没落后"，那一合就是快进，**不可能冲突**。于是冲突全部发生在
     * 同步那一步、也就是**在这棵树自己的工作区里**，而主干从头到尾是干净的 ——
     * 这正是那套顺序的全部意义，没有这道检查它就不成立。
     *
     * <p>（GitHub 上叫 <em>Require branches to be up to date before merging</em>，同一个东西。）
     *
     * <p>返回**个数**而不是布尔，是为了能直说"你落后 2 个提交" —— 而"冲突了"会让人
     * 以为代码打架了，多数时候其实只是"你出发得早，别人先到了"。
     *
     * @param worktree 要检查的工作区
     * @param ref      对照的 ref，如 {@code "main"}
     */
    int commitsBehind(Path worktree, String ref);

    /**
     * **这一个提交引入的改动**：哪些文件、各增删多少行。
     *
     * <h2>为什么按"提交"问，而不是按"两个提交之间"问</h2>
     * 因为要的是"**这一步做了什么**"。而"两个提交之间"在历史不是一条直线时会答错：
     * 中间夹过一次从主干同步进来的合并，那一段的差就把它人的改动也算进来了 ——
     * 于是界面上会出现"这一轮改了 40 个文件"，而 agent 其实只动了 2 个。
     *
     * <p>按提交问就没有这个问题：一个提交的第一父提交**永远**是"这棵树的上一个状态"，
     * 不管它是不是合并提交。`diff <sha>^ <sha>` 拿到的就是这一步引入的东西。
     *
     * @param worktree  工作区（那棵树）
     * @param commitSha 要看的提交
     * @return 改动的文件，按路径排序。没有改动时是空表
     */
    List<FileChange> changesIntroducedBy(Path worktree, String commitSha);

    /**
     * 这个提交里**某一个文件**改了什么 —— 正文。
     *
     * <p>和 {@link #changesIntroducedBy} 是同一件事的两个粒度，问的是同一个区间
     * （{@code <sha>^ <sha>}），所以两者不会对不上。
     *
     * <p><strong>按需取，不存进事件流。</strong>"这一轮改了什么"是每一处读取都要过一遍的东西
     * （断线补齐、回放、崩溃恢复），而正文只有人点开某一个文件时才需要 ——
     * 把它塞进事件，等于让每一处读事件的地方都为它买单。
     *
     * @param gitPath  git 看得懂的路径（**仓库根相对**）。界面上给的是项目路径，
     *                 翻那一道在 {@code ProjectLayout#toGitPath}
     * @param maxChars 正文字符上限。超了**截断并如实标记**，不报错 ——
     *                 一份被截断的 diff 仍然有用，而"打不开"没有任何用
     */
    FileDiff diffOfFile(Path worktree, String commitSha, String gitPath, int maxChars);

    // ------------------------------------------------------------------
    // 冲突裁决
    //
    // 下面这一组操作的是**一个普通的 git 工作目录** —— 某棵树的工作区，或者主干，
    // 取决于合并的方向：**同步**的冲突落在树的工作区，**合回**的冲突落在主干。
    // 调用方通过"当前挂着的那个合并在哪"来决定传哪一个。
    //
    // 为什么它们也在这个接口上：理由同上面 initializeRepository 那段 ——
    // "调用 git 这件事只有一个出口"，多开一个出口，那条约束就退化成约定。
    // ------------------------------------------------------------------

    /** 主干当前是否处于合并中途（有未完成的合并）。 */
    boolean mergeInProgress(Path worktree);

    /** 冲突文件清单 —— 就是界面上要交给用户裁决的那份列表。 */
    List<String> conflicts(Path worktree);

    /**
     * 冲突中某一方的内容。
     *
     * <p>取的是**索引里的那一份**（stage 2 / 3），不是工作区文件里的
     * {@code <<<<<<<} 标记文本 —— 后者要自己解析，而且不同的 merge driver 可能不写它。
     * 这两份正是前端并排 diff 需要的两个版本。
     *
     * @param ours true = 我方（当前分支），false = 对方（被合进来的那个分支）
     */
    String conflictSide(Path worktree, String path, boolean ours);

    /** 用一个侧面把某个文件的冲突裁决掉（整份取一侧）。 */
    void resolveConflict(Path worktree, String path, boolean ours);

    /**
     * 用**人给出的完整内容**了结一个文件的冲突。
     *
     * <h2>为什么"整份取一侧"不够</h2>
     * 两个人各往同一个文件里加了一个方法，都保留才是对的 —— 而取一侧必然丢掉另一个。
     * 同样地，两个人把同一行改成不同的东西时，正确的答案常常也不是其中任何一份原样，
     * 而是"两边各取一半"。这两种情况在协作里是**常态**，不是边角。
     *
     * <p>所以第三块拼图是：让人直接给出他想要的那份内容。服务端写进去、然后
     * {@code git add} —— 而在 git 的语义里，"add 一个冲突文件"正是"这个文件我解决了"。
     */
    void resolveConflictByContent(Path worktree, String path, String content);

    /**
     * 冲突全部裁决完之后收尾：把索引里已经正确的合并结果提交掉。
     *
     * <p>不能用 {@link #commit}：那个会先 {@code add -A}，而合并后的索引已经是
     * 裁决的正确答案，再 add 一遍不会有害、但会把"这次合并到底改了什么"这个判断
     * 交给工作区而不是索引 —— 两者不一致时（比如裁决之后又手改了文件）结果就飘了。
     *
     * @return 合并提交的 sha
     */
    String finishMerge(Path worktree, String message, CommitIdentity identity);

    /** 放弃本次合并，工作区恢复到合并前。 */
    void abortMerge(Path worktree);

    /** 主干的固定分支名。所有工作区都由它分出，也都合并回它。 */
    String MAIN_BRANCH = "main";
}
