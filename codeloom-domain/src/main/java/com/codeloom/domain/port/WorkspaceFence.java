package com.codeloom.domain.port;

import com.codeloom.domain.workspace.WorkspaceId;

/**
 * fencing token 的权威：发号与校验。号必须和锁保护同一个对象：锁保护一棵树（见
 * {@link ExecutionLease}），号若发在会话行上，同一棵树的另一条会话会推进自己那行的号，
 * 旧持有者醒来拿旧号仍能通过校验。所以发号与校验都落在 {@code workspace.fencing_token}
 * 上。存储必须与锁不同：租约在 Redis，本端口在 MySQL，要持久且不回退 —— 锁可以是概率性
 * 的，校验必须确定性。这一列只有一个写入者，定义在 domain 而不是 realtime，因为表归
 * {@code codeloom-workspace}。见
 * {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
 */
public interface WorkspaceFence {

    /**
     * 抢到租约之后取一个新号。返回值严格大于这棵树上发过的任何号。
     *
     * <p>实现必须原子（并发调用不能拿到同一个值）而且持久 —— 不回退是整个机制成立的
     * 前提。必须在成功抢到锁之后再发号：反过来做的话，一个抢锁失败的竞争者会把号推过
     * 当前持有者的 token，持有者下一次写入会被写入校验拒掉。
     *
     * @param workspaceId 哪棵树
     * @return 新号
     * @throws IllegalStateException 这棵树不存在（会话还没建出它的工作区）—— 判据和异常
     *                               与 {@link WorkspaceRepository#require} 相同：是库里的行
     *                               少了，不是请求写错了
     */
    long issue(WorkspaceId workspaceId);

    /**
     * 确认这个 token 仍是当前有效值。
     *
     * <p>实现有三条不能改的约束。第一，必须加锁读（{@code SELECT ... FOR UPDATE}），
     * 不能是普通查询：普通查询留下的窗口是「校验通过 → 另一个实例接管并推进了号 →
     * 这里才插入」，僵尸写入者仍能通过校验；加锁读把 workspace 行锁到事务结束，顺带
     * 把同一棵树上的并发追加串行化，使 {@code event} 表 seq 的分配顺序等于提交顺序。
     * 第二，必须和它所保护的那次写入在同一个事务里，否则锁读完就释放了。第三，校验的是 token 里的
     * {@code workspaceId}，不是 {@code sessionId} —— 那一列在 {@code workspace} 表上。
     *
     * @param token 待校验的租约凭据
     * @throws StaleLeaseException token 已失效，说明这棵树已被别的执行者接管；遇到它不要重试、
     *                             不要吞掉，立刻中止本轮执行
     */
    void assertValid(LeaseToken token);
}
