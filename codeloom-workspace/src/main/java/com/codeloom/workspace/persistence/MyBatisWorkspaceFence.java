package com.codeloom.workspace.persistence;

import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.StaleLeaseException;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.workspace.WorkspaceId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link WorkspaceFence} 的实现：把 {@code workspace.fencing_token} 那一列当作权威。
 *
 * <p>本类是这一列在**校验路径**上的唯一写入者（另一个是 {@link WorkspaceMapper#save}，
 * 而它刻意不碰这一列）。这正是机制能成立的原因，见 {@link WorkspaceFence} 的类注释。
 *
 * <h2>两个方法的传播行为都是有讲究的</h2>
 * <ul>
 *   <li>{@link #issue} 用 {@code REQUIRED}：它需要一条事务把两条语句绑到同一条连接上
 *       （见方法注释），而在正常情况下它本来就跑在任何事务之外，于是自己开一条短事务。
 *       不用 {@code REQUIRES_NEW}，理由写在方法注释里。
 *   <li>{@link #assertValid} 用 {@code MANDATORY}：它必须**已经在**调用方的事务里，
 *       否则加锁读当场就释放了，什么都没保护到。用 {@code MANDATORY} 而不是靠自觉，
 *       是把这条要求变成运行时强制检查 —— 少了事务时它会抛，而不是安静地什么都不保护。
 * </ul>
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
     * <p>为什么要一条事务：发号是「UPDATE ... LAST_INSERT_ID(expr)」加「SELECT
     * LAST_INSERT_ID()」两条语句，而 {@code LAST_INSERT_ID()} 是**连接级**的值。
     * 不在一条事务里就落不到同一条连接上，第二条语句读回来的是别的连接的残留值 ——
     * 静默发错号。事务在这里的作用不是原子性，是**把两条语句绑到同一条连接**。
     *
     * <p>用默认的 {@code REQUIRED}，而不是 {@code REQUIRES_NEW}：
     * <ul>
     *   <li>发号本来就发生在任何事务之外（抢锁是执行一轮的第一步），所以 {@code REQUIRED}
     *       自己开的那条短事务就是想要的效果，锁在方法返回时就释放了。
     *   <li>反过来，{@code REQUIRES_NEW} 会挂起外层事务、另起一条连接：那条连接
     *       **看不见外层事务里尚未提交的工作区行**。谁要是先在一个事务里建工作区、
     *       紧接着在里面抢租约，就会拿到"这棵树不存在"—— 而那看起来完全不像隔离级别的问题。
     *   <li>万一真有调用方刻意把发号包进一个更长的事务里，意思是"发号和后面的写入要整体成立"，
     *       那就应该跟着那个事务走。
     * </ul>
     */
    @Override
    @Transactional
    public long issue(WorkspaceId workspaceId) {
        int bumped = mapper.bumpFencingToken(
                workspaceId.ownerId().value(), workspaceId.projectId().value());
        if (bumped != 1) {
            // 一行都没命中 = 这棵树不存在。这时 lastIssuedToken() 读回来的是这条连接更早的
            // 残留值，把它当号发出去会让一棵不相干的工作区莫名失去写入权 —— 必须在这里拦住
            throw new IllegalArgumentException(
                    "工作区不存在，无法发号：" + workspaceId + "（先建出它的工作区，再抢租约）");
        }
        return mapper.lastIssuedToken();
    }

    /**
     * {@inheritDoc}
     *
     * <p>用加锁读而不是普通查询：普通查询留下的窗口是「校验通过 → 别人接管 → 这里才写」，
     * 僵尸写入者刚好从缝里钻过去。锁持有到调用方的事务提交，所以调用方**必须**把本方法与
     * 它保护的那次写入放在同一个事务里。
     *
     * <p>{@code MANDATORY} 就是在强制这一条：调用方没有事务时它直接抛
     * {@code IllegalTransactionStateException}，而不是自己开一个事务 —— 后者会让
     * 锁读完就释放，然后调用方在毫无保护的情况下写入，而且看起来一切正常。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertValid(LeaseToken token) {
        WorkspaceId workspaceId = token.workspaceId();
        Long current = mapper.lockFencingToken(
                workspaceId.ownerId().value(), workspaceId.projectId().value());

        // current 为 null 说明这棵树不存在 —— 号都没有，这个 token 自然不算有效
        if (current == null || current > token.fencingToken()) {
            throw new StaleLeaseException(workspaceId, token.fencingToken());
        }
    }
}
