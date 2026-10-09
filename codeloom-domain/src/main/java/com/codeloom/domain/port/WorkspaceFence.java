package com.codeloom.domain.port;

import com.codeloom.domain.workspace.WorkspaceId;

/**
 * fencing token 的**权威**：发号与校验。
 *
 * <h2>为什么它挂在「树」上，而不是会话上（这条是核心）</h2>
 * 号必须和**锁**守在同一个东西上。锁守的是一棵树（见 {@link ExecutionLease}），
 * 号要是还发在会话行上，就有这样一个洞：
 *
 * <pre>
 *   会话 A 拿到树锁，拿到号 5
 *   A 长时间停顿，锁到期
 *   会话 B（同一个人的另一条会话，同一棵树）拿到树锁，拿到号 1（B 那一行自己的计数）
 *   A 醒来，拿号 5 去写 —— 它那一行的号没变过，校验通过，照写不误
 * </pre>
 *
 * <p>A 和 B 同时改一棵树，而按会话发号的那套校验一次都没生效。所以发号与校验都必须落在
 * {@code workspace.fencing_token} 那一列上：**锁的键和号的键是同一个**，
 * 这条机制才有意义。
 *
 * <h2>为什么它是独立的一个端口，而不是塞进 ExecutionLease</h2>
 * 因为这两件事的存储不一样，而且必须不一样：
 *
 * <ul>
 *   <li>{@link ExecutionLease} 的锁在 Redis —— 它要的是快、有 TTL、过期即"实例已死"。
 *   <li>本端口在 MySQL —— 它要的是**持久且不可能回退**。锁可以是概率性的，
 *       而校验必须是确定性的：Redis 被清空导致计数器回退时，僵尸写入者仍要被挡住。
 * </ul>
 *
 * <p>如果发号也放 Redis，那个概率性的系统就会去决定确定性那一半的输入，
 * 正好把 {@link LeaseToken} 想划清的那条线搅浑。而如果一起放进 Redis，
 * 计数器一旦回退到低于 MySQL 里已受理的高水位，**这棵树会永久写不进去** ——
 * 一个不报错、不自愈、得先想到去 Redis 里捞计数器才能发现的故障。
 *
 * <h2>为什么它住在 domain 而不是 realtime</h2>
 * 它操作的是 {@code workspace} 表，而那张表归 {@code codeloom-workspace}。
 * 端口在 domain、实现在持有该表的模块，于是 **workspace 行只有一个写入者**。
 * 这不是洁癖：fencing token 的正确性正是"这一列只有一处会被推进"，
 * 让别处也能顺手写它，恰好破坏了它成立的前提。
 */
public interface WorkspaceFence {

    /**
     * 抢到租约之后取一个新号。返回的值**严格大于**这棵树上发过的任何号。
     *
     * <p>实现必须是原子的（并发调用不能拿到同一个值），而且**必须持久** ——
     * 它不回退这件事是整个机制成立的前提。
     *
     * <p>调用时机有要求：**必须在成功抢到锁之后**再发号。反过来做的话，
     * 一个抢锁失败的竞争者会把号推过当前持有者的 token，于是持有者下一次写入
     * 会被自己的守门拒掉（"活着的人被踢出局"）。
     *
     * @throws IllegalArgumentException 这棵树不存在（会话还没建出它的工作区）
     */
    long issue(WorkspaceId workspaceId);

    /**
     * 确认这个 token 仍是当前有效值。
     *
     * <h2>三个不能改的实现约束</h2>
     * <ol>
     *   <li><b>必须是加锁读（{@code SELECT ... FOR UPDATE}），不能是普通查询。</b>
     *       普通查询留下的窗口是：校验通过 → 另一个实例接管并推进了号 → 这里才插入。
     *       僵尸写入者于是刚好从缝里钻过去。加锁读会把 workspace 行锁到这个事务结束，
     *       顺带把**同一棵树上**的并发追加也串行化了 —— 这同时是
     *       {@code event} 表 seq「无洞」的前提（自增值的分配顺序要等于提交顺序）。
     *   <li><b>必须和它所保护的那次写入在同一个事务里。</b> 否则锁读完就释放了，
     *       上面那条约束一条也不成立。
     *   <li><b>校验的是 token 里的 {@code workspaceId}，不是 {@code sessionId}。</b>
     *       那一列在 {@code workspace} 表上。见上面那段"为什么挂在树上"。
     * </ol>
     *
     * @throws StaleLeaseException token 已失效（说明这棵树已被别的执行者接管）。
     *                             遇到它不要重试、不要吞掉，立刻中止本轮执行。
     */
    void assertValid(LeaseToken token);
}
