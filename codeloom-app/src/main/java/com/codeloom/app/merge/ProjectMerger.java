package com.codeloom.app.merge;

import com.codeloom.app.project.ProjectLayout;
import com.codeloom.app.session.CommitIdentities;
import com.codeloom.domain.port.MergeResult;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.session.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 「在项目锁的保护下动主干」的那几步。**拆成独立 bean 是为了事务边界。**
 *
 * <h2>为什么不放在 {@code MergeService} 里</h2>
 * 因为它必须是一个**独立、短**的事务：这里持着项目行的排它锁，而紧接着的
 * "合并之后跑一次构建"可能要几分钟。两者放进同一个事务的话，那把锁会被按住整场构建 ——
 * 期间任何人想合并、想改成员都得等。
 *
 * <p>而 {@code @Transactional} 只在**跨 bean 调用**时生效 —— 在同一个类里自己调自己
 * 是拿不到代理的。这和 {@code SessionWriter} 被拆出来是同一个原因：
 * 给事务边界一个明确的落点。
 *
 * <h2>为什么用 {@code @Transactional} 而不是别的锁</h2>
 * 事务在这里**不是为了回滚 git**（git 没有回滚，出事只能 abort，而 abort 是显式的一步）。
 * 它只是为了持住那把行锁。合并本身是一次几百毫秒的 git 调用。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProjectMerger {

    private final ProjectRepository projects;
    private final WorkspaceManager workspaces;
    private final CommitIdentities identities;

    public ProjectMerger(ProjectRepository projects,
                         WorkspaceManager workspaces,
                         CommitIdentities identities) {
        this.projects = projects;
        this.workspaces = workspaces;
        this.identities = identities;
    }

    /**
     * 把这条会话的产出合进主干。
     *
     * <p>可能有三种收场：干净地合完、快进、或者**有冲突**。有冲突时 git 会在主干工作区里
     * 留下一个未完成的合并 —— 从这里开始就进入"等裁决"的状态了。
     *
     * @param sourceRef        合**到哪儿**。两种用法：
     *                         <ul>
     *                           <li>会话的分支名 —— 合掉这条会话的全部产出</li>
     *                           <li>某个 checkpoint 的 sha —— **小步合并**：不攒完整个会话再合，
     *                               做一段合一段。粒度和"每轮一个提交"天然对齐</li>
     *                         </ul>
     *                         sha 的合法性由调用方保证（见 {@code SessionCheckpoints}）——
     *                         这一层只管合，不管"你有没有资格合它"
     * @param requireUpToDate 要不要先确认"这条分支已经包含主干"。见下面的注释 ——
     *                        **合到分支最新时为 true，合到某个旧 checkpoint 时为 false**
     */
    @Transactional
    public MergeResult mergeIntoMain(Session session, Project project, String sourceRef,
                                     boolean requireUpToDate) {
        projects.lock(project.id());

        // ★ 过期检查放在**锁里面**：锁到手了主干就不会动，此刻查才有意义。
        //   放在锁外面查的话，查完到合之间还有一段窗口 —— 而那正是它要消灭的东西。
        //
        //   落后就不合：拒绝的代价是"再点一次"（第二次会先自动同步），
        //   而照合的代价是"冲突撞在主干上、把主干占住"。见 StaleBranchException
        //
        //   合到**旧 checkpoint** 时不做这个检查：那个点是**故意旧**的，
        //   要求它"已经包含主干"等于禁止小步合并
        if (requireUpToDate) {
            int behind = workspaces.commitsBehind(
                    workspaceOf(session).path(), WorkspaceManager.MAIN_BRANCH);
            if (behind > 0) {
                throw new StaleBranchException(behind);
            }
        }

        return workspaces.merge(mainOf(project), sourceRef, identities.of(session.ownerId()));
    }

    /**
     * 当前等着裁决的冲突文件，连同两侧的内容 —— 界面上的并排 diff 用它。
     *
     * <p>没有待裁决的合并时返回**空信封**而不是报错：客户端会在进页面时探一下。
     */
    @Transactional
    public ConflictsView conflicts(Session session, Project project) {
        projects.lock(project.id());
        return pendingMerge(session, project)
                .map(pending -> new ConflictsView(pending.direction(),
                        workspaces.conflicts(pending.worktree()).stream()
                                // **工作在项目外面**的那些不露给用户（工作区根上
                                // 平台自己的文件，见 ProjectLayout.toProjectPath）——
                                // 他既没建过它，也无从裁决
                                .filter(path -> ProjectLayout.toProjectPath(path) != null)
                                // 路径在 sidesOf 里翻（那里同时要把 git 路径留给 git 用）
                                .map(path -> sidesOf(pending, path))
                                .toList()))
                .orElseGet(ConflictsView::none);
    }

    /** 一串 git 路径 → 项目路径，**丢掉项目外面的那些**（见 ProjectLayout.toProjectPath）。 */
    private static List<String> projectPaths(List<String> gitPaths) {
        return gitPaths.stream().map(ProjectLayout::toProjectPath).filter(Objects::nonNull).toList();
    }

    /**
     * 一个冲突的两侧 —— **按"是哪一边"归类，不按 git 的 ours/theirs**。
     *
     * <p>git 的 {@code ours} 是"当前分支"，而当前分支是哪条取决于合并方向：
     * 合回主干时它是主干，同步时它是这条会话。所以这里显式翻一次，把
     * {@code ConflictView.sessionSide / mainSide} 填成**永远指同一份东西** ——
     * 前端因此不需要知道方向，也不需要做任何翻转。
     *
     * <p>见 {@link MergeDirection}：git 的词汇只该活在适配层。
     *
     * @param path **git 的路径**（{@code untitled/a.txt}）。装进 {@link ConflictView}
     *             之前翻成项目路径 —— 那个字段是给界面看的
     */
    private ConflictView sidesOf(PendingMerge pending, String path) {
        boolean oursIsTheSession = pending.direction() == MergeDirection.INTO_SESSION;
        String ours = workspaces.conflictSide(pending.worktree(), path, true);
        String theirs = workspaces.conflictSide(pending.worktree(), path, false);
        String shown = ProjectLayout.toProjectPath(path);
        return oursIsTheSession
                ? new ConflictView(shown, ours, theirs)     // ours 就是会话那侧
                : new ConflictView(shown, theirs, ours);    // 反方向：ours 是主干那侧
    }

    /**
     * 裁决一个文件的冲突；**所有的都裁决完之后把合并收尾**。
     *
     * <p>为什么自动收尾而不是再来一个"完成"接口：人做完最后一个决定之后，
     * 剩下的事情没有任何选择余地 —— 让客户端再发一次请求只是多一个能忘掉的步骤，
     * 而忘了它的表现是"合并一直挂在那儿"，还得去查文档才知道要点哪个按钮。
     */
    @Transactional
    public Resolution resolve(Session session, Project project, String path,
                              ConflictResolution resolution) {
        projects.lock(project.id());
        PendingMerge pending = pendingMerge(session, project).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "现在没有待裁决的合并"));
        Path worktree = pending.worktree();
        // 客户端给的是**项目路径**（{@code a.txt}），而下面每一步问的都是 git ——
        // 它只认 {@code untitled/a.txt}。翻一次，就在这里
        String gitPath = ProjectLayout.toGitPath(path);

        if (!workspaces.conflicts(worktree).contains(gitPath)) {
            // 明确拒绝而不是静默忽略：客户端可能拿着一份过期的列表在操作，
            // 而"我以为裁决了、其实没有"会让下一步的合并结果莫名其妙。
            //
            // 顺带一提，这道检查也**是写入路径的边界**：下面按内容写文件时，
            // 那个路径只能是 git 报出来的冲突文件之一，而 git 报的都是仓库内的相对路径
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "这个文件当前不在冲突清单里：" + path);
        }

        // 穷尽 switch：新增一种裁决方式时这里编译不过，逼着人回来处理
        switch (resolution) {
            case ConflictResolution.KeepSide keep -> {
                // ★ 语义的"哪一侧" → git 的 ours，**整个项目只在这里翻一次**。
                //
                //   "会话是 ours" 当且仅当在往会话的工作区里合 —— 因为 ours 是"当前分支"，
                //   而当前分支就是那块工作区所在的分支。合回主干时当前分支是主干，
                //   于是 ours 是主干侧，两边正好调过来。
                //
                //   穷尽 switch：新增一个方向时这里编译不过
                boolean ours = switch (keep.side()) {
                    case SESSION -> pending.direction() == MergeDirection.INTO_SESSION;
                    case MAIN -> pending.direction() == MergeDirection.INTO_MAIN;
                };
                workspaces.resolveConflict(worktree, gitPath, ours);
            }
            case ConflictResolution.WithContent with ->
                    workspaces.resolveConflictByContent(worktree, gitPath, with.content());
        }

        List<String> remaining = workspaces.conflicts(worktree);
        if (!remaining.isEmpty()) {
            return new Resolution(false, null, projectPaths(remaining), false);
        }
        // 最后一个冲突裁完了：索引里已经是裁决后的结果，直接提交
        String sha = workspaces.finishMerge(worktree,
                "merge: 合并 " + workspaceOf(session).branch() + "（冲突已裁决）",
                identities.of(session.ownerId()));
        // 把"这块工作区是不是树那侧"带出去：**裁决完成的合并会推进 HEAD**，
        // 而推进的如果是**这棵树的** HEAD，工作区的 head_commit 那一列就必须跟着更新
        //（由调用方落事件）。主干那侧不用 —— 那一列记的是树的分支位置，和主干无关
        return new Resolution(true, sha, List.of(), worktree.equals(workspaceOf(session).path()));
    }

    @Transactional
    public void abort(Session session, Project project) {
        projects.lock(project.id());
        Path worktree = pendingMerge(session, project).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "现在没有待裁决的合并"))
                .worktree();
        workspaces.abortMerge(worktree);
    }

    /**
     * 当前挂着的那个合并：**在哪块工作区**，以及**是哪个方向**。
     *
     * <h2>为什么要两处都查</h2>
     * 两个方向的合并各自在自己的工作区里留下冲突：
     * <ul>
     *   <li><b>同步</b>（主干 → 会话）：冲突留在**会话的工作区**</li>
     *   <li><b>合回</b>（会话 → 主干）：冲突留在**主干**</li>
     * </ul>
     *
     * <p>而裁决那几个端点**不该关心它落在哪儿** —— 调用方要的是"把当前挂着的那个裁决掉"。
     * 让它记着"我正在裁决的是哪一个"，正是这个接口最不该塞给客户端的包袱。
     *
     * <p>而且**方向必须跟着一起带出来**：它决定了 git 的 {@code ours} 指哪一边，
     * 于是决定了裁决时怎么把"取会话侧"翻过去。少了它，那个翻转就得在别处再猜一次。
     *
     * <p>先查树的工作区：同步做上之后，绝大多数冲突都发生在那里。
     */
    private Optional<PendingMerge> pendingMerge(Session session, Project project) {
        Path sessionWorktree = workspaceOf(session).path();
        if (workspaces.mergeInProgress(sessionWorktree)) {
            return Optional.of(new PendingMerge(MergeDirection.INTO_SESSION, sessionWorktree));
        }
        Path main = mainOf(project);
        return workspaces.mergeInProgress(main)
                ? Optional.of(new PendingMerge(MergeDirection.INTO_MAIN, main))
                : Optional.empty();
    }

    /** 一个挂着等人裁决的合并：落在哪块工作区，以及是哪个方向。 */
    private record PendingMerge(MergeDirection direction, Path worktree) {
    }

    /**
     * 这条会话所在的那棵树的工作区。
     *
     * <p>建会话时就已经建好了（见 {@link com.codeloom.app.workspace.WorkspaceProvisioner#ensure}），
     * 所以找不到意味着它被人从磁盘上删掉了 —— 那是服务端状态不对，不是客户端传错了。
     */
    private Workspace workspaceOf(Session session) {
        return workspaces.find(session.workspaceId())
                .orElseThrow(() -> new IllegalStateException(
                        "会话 " + session.id() + " 的工作区目录不在磁盘上"));
    }

    /**
     * 一次裁决的结果。
     *
     * @param merged            所有冲突都裁完了、合并已经提交
     * @param mergeCommitSha    完成时的合并提交 sha；未完成时为 null
     * @param inSessionWorktree 这次收尾的合并发生在**树的工作区**里（也就是一次同步的收尾），
     *                          而不是主干上。调用方据此决定要不要更新工作区的 head_commit ——
     *                          **推进了这棵树的 HEAD 就必须更新那一列**，否则回滚、checkpoint、
     *                          下一次合并会全按一个错的位置去算，而且不会报错
     */
    public record Resolution(boolean merged, String mergeCommitSha, List<String> remaining,
                             boolean inSessionWorktree) {

        public Resolution {
            remaining = List.copyOf(remaining);
        }
    }

    private static Path mainOf(Project project) {
        return Path.of(project.repoPath());
    }
}
