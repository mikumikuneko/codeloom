package com.codeloom.app.merge;

import com.codeloom.app.session.CommitIdentities;
import com.codeloom.app.turn.SessionWriter;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.MergeResult;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.session.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把**主干**的最新状态拉进这条会话的工作区 —— 合并的反方向。
 *
 * <h2>为什么要有这个动作</h2>
 * 会话的工作区是**从主干分出来的**。之后两条线各走各的：对方合进主干的东西，
 * 我这边不会自动有。于是两个人会**盲撞** —— 各造一个类，谁也不知道对方也造了，
 * 而 git 只会在合并那一刻告诉你"文件冲突"，检测不到"这两个类职责重叠"。
 *
 * <p>同步就是把对方已经进主干的改动**拉进我的工作区**。它的价值不只是"少点冲突"：
 * 那些代码进了我的工作区之后 **我的 agent 读得到** —— 它就有机会自己发现
 * "已经有个同职责的类了"。这是任何"事后审查"都替代不了的：审查发生在**合**的那一刻，
 * 而同步发生在**动手之前**。
 *
 * <h2>为什么冲突发生在工作区里（这是刻意的）</h2>
 * 它和 {@link MergeService} 用的是同一个 git 原语，只是主客颠倒。冲突留在**这棵树的工作区**里 ——
 * 而那是要的：**冲突在树上出、主干保持干净**。反过来（先合主干再同步回来）会让主干
 * 进入"未完成的合并"状态被占住。
 *
 * <h2>为什么不需要项目锁</h2>
 * 同步只动**这棵树自己的分支**，不碰主干，也不需要读主干的文件 —— 所以不共享任何
 * 需要互斥的东西。互斥由**执行租约**提供，而它锁的正好是这棵树
 * （同一时刻只有一条轮次或一次同步在动这份工作区）。
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
     * @throws ConflictPendingException 有冲突。**这时会话的工作区里留下了一个未完成的合并**，
     *                                  要接着走裁决流程（那几个端点会自己找到它）
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
     * 租约**已经拿在手里**时用这个。
     *
     * <p>存在的理由很具体：合并会先同步一次（先同步再合，冲突才不会撞在主干上），
     * 而它自己已经持着这条会话的租约了。再调一次上面那个入口会去抢同一把锁 ——
     * 而锁不是可重入的，于是它只会拿到「正在执行中」，一次本可以避免的失败。
     *
     * @param token 调用方持有的租约。它必须属于 {@code session}
     */
    public SyncOutcome sync(Session session, LeaseToken token) {
        Workspace workspace = workspaceOf(session);

        // 从**磁盘**读，不是从 session 那一列 —— 我们要记的正是"我把它从哪儿挪到了哪儿"
        String fromHead = workspace.headCommit();

        MergeResult result = workspaces.merge(workspace.path(),
                WorkspaceManager.MAIN_BRANCH, identities.of(session.ownerId()));

        // 穷尽 switch：新增一种合并状态时这里编译不过，逼着人回来决定怎么处理
        return switch (result.status()) {
            case CONFLICT -> throw new ConflictPendingException(result.conflictingPaths());
            case UP_TO_DATE -> SyncOutcome.upToDate();
            case FAST_FORWARD, MERGED -> {
                // 事件和 head_commit 那一列在同一个事务里 —— 见 SessionWriter.sync
                writer.sync(session, fromHead, result.headCommit(), token);
                yield new SyncOutcome(result.status().name(), fromHead, result.headCommit());
            }
        };
    }

    /**
     * 这条会话的工作区。
     *
     * <p>建会话时就建好了（{@code SessionService.create} 里的 {@code workspaces.create}），
     * 所以找不到意味着它被人从磁盘上删掉了 —— 服务端状态不对，不是客户端传错了。
     */
    private Workspace workspaceOf(Session session) {
        return workspaces.find(session.workspaceId()).orElseThrow(() -> new IllegalStateException(
                "会话 " + session.id() + " 的工作区目录不在磁盘上。"
                        + "它本该在建会话时就建好 —— 现在找不到，只可能是被手工删掉了。"));
    }
}
