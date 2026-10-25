package com.codeloom.app.merge;

import com.codeloom.app.session.CommitIdentities;
import com.codeloom.app.turn.SessionWriter;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.MergeResult;
import com.codeloom.domain.port.Workspace;
import com.codeloom.app.workspace.WorkspaceLookup;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.session.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把主干的最新状态拉进这条会话的工作区，是合并的反方向。
 *
 * <p>同步让对方的改动进入这棵树，agent 在动手前就能读到，从而自己发现重复的实现；
 * 这也正是冲突刻意留在树的工作区、主干保持干净的原因。同步只动这棵树自己的分支，
 * 不碰主干，因此不需要项目锁；互斥由执行租约提供，它锁的正好是这棵树。
 *
 * <p>理由见 {@code docs/decisions/architecture/2026-09-29-sync-before-merge.md}。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionSyncService {

    private final WorkspaceManager workspaces;
    private final ExecutionLease leases;
    private final SessionWriter writer;
    private final CommitIdentities identities;

    public SessionSyncService(WorkspaceManager workspaces,
                              ExecutionLease leases,
                              SessionWriter writer,
                              CommitIdentities identities) {
        this.workspaces = workspaces;
        this.leases = leases;
        this.writer = writer;
        this.identities = identities;
    }

    /**
     * 同步一次。
     *
     * @return 同步状态与前后 HEAD；主干没动时两个 HEAD 均为 null
     * @throws ResponseStatusException   409，这条会话正在执行中
     * @throws ConflictPendingException  有冲突；会话的工作区里留下未完成的合并，
     *                                   要接着走裁决流程（裁决端点会自己找到它）
     */
    public SyncOutcome sync(Session session) {
        LeaseToken token = leases.tryAcquire(session).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完再同步"));
        try {
            return sync(session, token);
        } finally {
            leases.release(token);
        }
    }

    /**
     * 租约已经拿在手里时用这个。
     *
     * <p>合并会先同步一次，而合并自己已经持着这条会话所在那棵树的租约；再走上面的入口会抢同一把
     * 不可重入的锁，得到一次本可避免的「正在执行中」。
     *
     * @param token      调用方持有的租约，必须属于 {@code session}
     * @return 同步状态与前后 HEAD；主干没动时两个 HEAD 均为 null
     * @throws ResponseStatusException  409，树里已有未收尾的合并
     * @throws ConflictPendingException 有冲突；会话的工作区里留下未完成的合并
     */
    public SyncOutcome sync(Session session, LeaseToken token) {
        Workspace workspace = workspaceOf(session);

        // 上一次合并没收尾时不能再起一次：git 不允许同一工作区里有两个未完成的合并，
        // 第二次会以非零退出、变成 500。这里提前说成 409 并给出下一步；
        // 与 ProjectMerger 合并前的同一检查共用理由。
        // 检查放在读 HEAD 之前：此时读到的 HEAD 属于那次未完成的合并，写进事件会把中间状态记成事实
        if (workspaces.mergeInProgress(workspace.path())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "这棵树里有一笔合并还没收尾：先在中间裁决冲突，或者放弃合并");
        }

        // 从磁盘读，不是从 session 那一列：要记的正是"从这个位置挪到了哪里"
        String fromHead = workspace.headCommit();

        MergeResult result = workspaces.merge(workspace.path(),
                WorkspaceManager.MAIN_BRANCH, identities.of(session.ownerId()));

        // 穷尽 switch：新增合并状态时编译不过
        return switch (result.status()) {
            case CONFLICT -> throw new ConflictPendingException(result.conflictingPaths());
            case UP_TO_DATE -> SyncOutcome.upToDate();
            case FAST_FORWARD, MERGED -> {
                // 事件和 head_commit 列在同一个事务里写 —— 见 SessionWriter.sync
                writer.sync(session, fromHead, result.headCommit(), token);
                yield new SyncOutcome(result.status().name(), fromHead, result.headCommit());
            }
        };
    }

    /**
     * 这条会话的工作区。
     *
     * <p>建会话时已建好（见 {@code WorkspaceProvisioner.ensure}），找不到意味着目录被人
     * 从磁盘上删掉，属于服务端状态错误。这里问的是 {@code WorkspaceManager}（磁盘），
     * 不是库里的那一行。
     */
    private Workspace workspaceOf(Session session) {
        return WorkspaceLookup.requireOnDisk(workspaces, session.workspaceId());
    }
}
