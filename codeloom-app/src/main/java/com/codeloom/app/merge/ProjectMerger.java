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
 * 在项目行锁保护下动主干的那几步。
 *
 * <p>拆成独立 bean 是为了事务边界：{@code @Transactional} 只在跨 bean 调用时生效。
 * 事务必须短，因为这里持着项目行的排它锁，而合并后的构建可能要几分钟；同一个事务会把锁
 * 按住整场构建。事务在这里只用于持锁，不用于回滚 git —— git 没有回滚，只能显式 abort。
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
     * <p>四种收场：已是最新（{@code UP_TO_DATE}）、快进、干净合完、有冲突。有冲突时
     * git 在主干工作区留下未完成的合并，从此刻进入等裁决状态。
     *
     * @param sourceRef        合到哪儿：会话的分支名（全部产出），或某个 checkpoint 的 sha
     *                         （小步合并，粒度与每轮一个提交对齐）。sha 的合法性由调用方保证
     *                         （见 {@code SessionCheckpoints}），这一层只负责合
     * @param requireUpToDate  是否先确认分支已包含主干；合到分支最新时为 true，
     *                         合到旧 checkpoint 时为 false
     * @return 合并结果；冲突时状态为 {@code CONFLICT} 并带冲突路径
     * @throws ResponseStatusException 409，树的工作区或主干已有未收尾的合并
     * @throws StaleBranchException    分支落后于主干（仅 {@code requireUpToDate} 时）
     */
    @Transactional
    public MergeResult mergeIntoMain(Session session, Project project, String sourceRef,
                                     boolean requireUpToDate) {
        projects.lock(project.id());

        // 未收尾的合并会让下一次 git merge 以非零退出；不翻译就是 500。这里说成 409，
        // 并给出下一步。冲突可能留在树的工作区（同步）或主干（合回），两处都查。
        // 理由见 docs/decisions/bug-fix/2026-10-18-a-pending-merge-turns-sync-into-409.md
        Path sessionWorktree = workspaceOf(session).path();
        if (workspaces.mergeInProgress(sessionWorktree)
                || workspaces.mergeInProgress(mainOf(project))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "有一笔合并还没收尾：先在中间裁决冲突，或者放弃合并");
        }

        // 过期检查必须在锁内：锁到手后主干不会再动，此时查才有意义，否则查完到合之间还有窗口。
        // 落后就不合：拒绝的代价是再点一次（第二次会先自动同步），照合则会把冲突撞在主干上。
        // 合到旧 checkpoint 时不查：那个点故意是旧的，要求它包含主干等于禁止小步合并
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
     * 当前等着裁决的冲突文件，连同两侧的内容；界面上的并排 diff 用它。
     *
     * <p>没有待裁决的合并时返回空信封而不是报错，客户端进入页面时会先探一下。
     *
     * @param session 发起合并的会话
     * @param project 会话所属项目
     * @return 冲突信封；没有待裁决的合并时为空
     */
    @Transactional
    public ConflictsView conflicts(Session session, Project project) {
        projects.lock(project.id());
        return pendingMerge(session, project)
                .map(pending -> new ConflictsView(pending.direction(),
                        workspaces.conflicts(pending.worktree()).stream()
                                // 项目外面的文件不露给用户：那是工作区根上平台自己的文件，
                                // 用户既没建过也无从裁决
                                .filter(path -> ProjectLayout.toProjectPath(path) != null)
                                // 路径在 sidesOf 里翻，那里同时要把 git 路径留给 git 用
                                .map(path -> sidesOf(pending, path))
                                .toList()))
                .orElseGet(ConflictsView::none);
    }

    /** 一串 git 路径转为项目路径，丢掉项目外面的那些（见 ProjectLayout.toProjectPath）。 */
    private static List<String> projectPaths(List<String> gitPaths) {
        return gitPaths.stream().map(ProjectLayout::toProjectPath).filter(Objects::nonNull).toList();
    }

    /**
     * 一个冲突的两侧，按"会话侧 / 主干侧"归类，而不是 git 的 {@code ours}/{@code theirs}。
     *
     * <p>{@code ours} 指当前分支，而当前分支随合并方向变化：合回主干时是主干，同步时是
     * 这条会话。这里显式翻一次，使 {@code ConflictView} 的两个字段永远指同一份东西，
     * 前端因此不需要知道方向。见 {@link MergeDirection}。
     *
     * @param path git 路径（例如 {@code untitled/a.txt}）；装进 {@link ConflictView} 前
     *             翻成项目路径
     * @return 两侧内容，{@code sessionSide} 恒为会话侧、{@code mainSide} 恒为主干侧
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
     * 裁决一个文件的冲突；全部裁决完后自动收尾合并。
     *
     * <p>自动收尾而不是另设"完成"接口：最后一个决定之后没有剩余选择，多一次请求只会
     * 多一个能忘记的步骤，忘了就表现为合并一直挂着。
     *
     * @param path       项目路径（例如 {@code a.txt}）；必须当前在冲突清单里
     * @param resolution 取一侧，或人给出的全文
     * @return 尚未裁完时 {@code merged} 为 false 并带剩余文件；完成时带合并提交，
     *         并标明收尾是否发生在树的工作区
     * @throws ResponseStatusException 409，当前没有待裁决的合并，或该文件不在冲突清单里
     */
    @Transactional
    public Resolution resolve(Session session, Project project, String path,
                              ConflictResolution resolution) {
        projects.lock(project.id());
        PendingMerge pending = pendingMerge(session, project).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "现在没有待裁决的合并"));
        Path worktree = pending.worktree();
        // 客户端给的是项目路径，git 只认仓库相对路径；只在这里翻
        String gitPath = ProjectLayout.toGitPath(path);

        if (!workspaces.conflicts(worktree).contains(gitPath)) {
            // 明确拒绝而不是静默忽略：客户端可能拿着过期的列表操作。
            // 这道检查同时是写入路径的边界：按内容写文件时，路径只能是 git 报出的冲突文件之一
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "这个文件当前不在冲突清单里：" + path);
        }

        // 穷尽 switch：新增裁决方式时编译不过
        switch (resolution) {
            case ConflictResolution.KeepSide keep -> {
                // 语义的"哪一侧"转为 git 的 ours；整个代码库只在这里翻一次。
                // 会话是 ours 当且仅当合并方向是 INTO_SESSION（ours 即当前分支）
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
        // 最后一个冲突裁完：索引里已是裁决结果，直接提交
        String sha = workspaces.finishMerge(worktree,
                "merge: 合并 " + workspaceOf(session).branch() + "（冲突已裁决）",
                identities.of(session.ownerId()));
        // 收尾的合并推进 HEAD；如果推进的是这棵树的 HEAD，调用方必须同步 head_commit 列。
        // 主干那侧不用：那一列记的是树的分支位置
        return new Resolution(true, sha, List.of(), worktree.equals(workspaceOf(session).path()));
    }

    /** 放弃当前未完成的合并，工作区恢复到合并前。 */
    @Transactional
    public void abort(Session session, Project project) {
        projects.lock(project.id());
        Path worktree = pendingMerge(session, project).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "现在没有待裁决的合并"))
                .worktree();
        workspaces.abortMerge(worktree);
    }

    /**
     * 当前挂着的合并：在哪块工作区、哪个方向。
     *
     * <p>两个方向在不同地方留下冲突：同步（主干到会话）留在会话的工作区，合回（会话到主干）
     * 留在主干。裁决端点不关心它落在哪，调用方要的是"把当前挂着的那个裁决掉"。
     * 方向必须一起带出，因为它决定 git 的 {@code ours} 指哪一边。先查树的工作区：
     * 同步先做，绝大多数冲突发生在那里。
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
     * <p>建会话时已经建好（见 {@link com.codeloom.app.workspace.WorkspaceProvisioner#ensure}），
     * 因此找不到意味着目录被人从磁盘上删掉了，属于服务端状态错误。
     */
    private Workspace workspaceOf(Session session) {
        // 问磁盘（WorkspaceManager），不是库里的行；两者都叫 find
        return workspaces.find(session.workspaceId())
                .orElseThrow(() -> new IllegalStateException(
                        "会话 " + session.id() + " 的工作区目录不在磁盘上"));
    }

    /**
     * 一次裁决的结果。
     *
     * @param merged            所有冲突都裁完、合并已提交
     * @param mergeCommitSha    完成时的合并提交 sha；未完成时为 null
     * @param remaining         尚未裁决的项目路径
     * @param inSessionWorktree 收尾的合并发生在树的工作区（一次同步的收尾），而不是主干。
     *                          调用方据此更新工作区的 head_commit；推进了这棵树的 HEAD
     *                          却不更新，回滚、checkpoint 和下一次合并都会按错位置计算且不报错
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
