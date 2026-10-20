package com.codeloom.workspace.persistence;

import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.StaleLeaseException;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.workspace.WorkspaceId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link WorkspaceFence} 的实现：{@code workspace.fencing_token} 是权威。
 *
 * <p>只有 {@link #issue} 推进这一列，{@link #assertValid} 只加锁读不写；
 * {@link WorkspaceMapper#save} 碰不到它。理由见
 * {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
 *
 * <p>{@link #issue} 用 {@code REQUIRED} 把两条语句绑到同一条连接；{@link #assertValid}
 * 用 {@code MANDATORY} 强制在调用方事务内执行，否则锁读完即释放。
 */
@Repository
public class MyBatisWorkspaceFence implements WorkspaceFence {

    private final WorkspaceMapper mapper;

    public MyBatisWorkspaceFence(WorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>发号由 {@code UPDATE ... LAST_INSERT_ID(expr)} 和 {@code SELECT
     * LAST_INSERT_ID()} 两条语句组成，而该值是连接级的；事务在这里的作用是把两条语句
     * 绑到同一条连接，而不是保证多行原子性。
     *
     * <p>用 {@code REQUIRED} 而非 {@code REQUIRES_NEW}：发号通常在事务之外，自己开的
     * 短事务正好够用；{@code REQUIRES_NEW} 会另起连接，看不到外层事务中尚未提交的
     * 工作区行。
     *
     * @throws IllegalStateException 工作区不存在
     */
    @Override
    @Transactional
    public long issue(WorkspaceId workspaceId) {
        int bumped = mapper.bumpFencingToken(
                workspaceId.ownerId().value(), workspaceId.projectId().value());
        if (bumped != 1) {
            // 一行都没命中 = 工作区不存在；此时 lastIssuedToken() 读到的是连接上的残留值，
            // 发出去会让一棵不相干的工作区失去写入权。与 WorkspaceRepository 一样按
            // 服务端状态错误处理（500），不是调用方参数错误
            throw new IllegalStateException(
                    "工作区不存在，无法发号：" + workspaceId + "（先建出它的工作区，再抢租约）");
        }
        return mapper.lastIssuedToken();
    }

    /**
     * {@inheritDoc}
     *
     * <p>加锁读把锁持有到调用方事务提交，因此本方法必须与它保护的那次写入在同一事务里。
     * {@code MANDATORY} 在无事务时直接抛出，而不是开一个读锁旋即释放的事务。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertValid(LeaseToken token) {
        WorkspaceId workspaceId = token.workspaceId();
        Long current = mapper.lockFencingToken(
                workspaceId.ownerId().value(), workspaceId.projectId().value());

        // 必须是当前号本身，不是“不小于它”：比当前号更大的 token 也从未被发出去过，
        // 放行等于让伪造的未来号取得写入权。current 为 null 说明这棵树不存在，同样不算有效
        if (current == null || current != token.fencingToken()) {
            throw new StaleLeaseException(workspaceId, token.fencingToken());
        }
    }
}
