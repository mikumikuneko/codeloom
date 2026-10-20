package com.codeloom.domain.port;

import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.workspace.WorkspaceId;

import java.util.Objects;

/**
 * 执行租约的凭据，也就是 fencing token。锁只拦没拿到锁的执行者，拦不住停顿后醒来的旧
 * 持有者：每次抢锁由 {@link WorkspaceFence} 发一个单调递增的号，每次产生副作用的写入
 * 都带上它并校验仍是当前值；锁可以是概率性的，写入校验是确定性的。锁和号都挂在
 * {@link WorkspaceId}（一棵树）上；{@link #sessionId} 是 {@link EventStore} 校验「拿
 * A 会话的 token 写 B 会话」时的归属判据，不参与定位锁。{@code holderId} 每次抢锁都
 * 必须不同，释放靠比对它删除，否则同一实例里停顿的旧线程会删掉新持有者的锁。四个字段
 * 都不为 null；{@code fencingToken} 必须为正。见
 * {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
 *
 * @param sessionId    这次抢锁替哪条会话抢的
 * @param workspaceId  锁和号挂在哪棵树上
 * @param fencingToken 单调递增序号；同一棵树上后抢到的租约一定比先抢到的大，从 1 开始
 * @param holderId     本次抢锁的持有者标识（实例 id + 随机后缀），每次抢锁都不同
 */
public record LeaseToken(SessionId sessionId, WorkspaceId workspaceId,
                         long fencingToken, String holderId) {

    public LeaseToken {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(holderId, "holderId");
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken 从 1 开始递增，收到 " + fencingToken);
        }
    }
}
