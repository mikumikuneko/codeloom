package com.codeloom.domain.port;

import com.codeloom.domain.session.Session;
import java.time.Duration;
import java.util.Optional;

/**
 * 执行租约：保证一棵工作区在任意时刻全局只有一个执行者。实现是 Redis 上的
 * {@code SET NX PX} 加 Lua 续约；租约过期本身就是「实例已死」的检测机制，崩溃恢复
 * 直接复用它。入参是会话、锁的是树，推导收在一处：让调用方自己拼 {@code WorkspaceId}，
 * 一处拼错就会让两条会话各拿一把锁，而它们护的是同一棵树；同一个人的两条会话因此
 * 天然互斥。调用方拿到就一定在 {@code finally} 里释放；拿不到时如何处置由调用方决定
 * （执行器回「忙」、合并/回滚 409、恢复跳过），所以没有 {@code acquireOrThrow}。见
 * {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
 */
public interface ExecutionLease {

    /**
     * 尝试拿这条会话所在那棵树的租约，拿不到立刻返回空（不阻塞、不排队）。
     *
     * <p>拿到锁只说明此刻没有别人在动这棵树，不代表后续写入都安全；每次写入仍要带
     * {@link LeaseToken} 校验。
     *
     * @param session 会话；锁的是它所在的那棵树
     * @return 租约凭据；拿不到时为空
     */
    Optional<LeaseToken> tryAcquire(Session session);

    /**
     * 续约。
     *
     * <p>返回 false 意味着已经失去执行权，调用方必须立刻中止，而不是继续跑完这一轮 ——
     * 继续跑的写入会被 {@link EventStore} 用 fencing token 拒绝，但工作区里的文件改动
     * 不会被拒绝，所以必须主动停。返回 false 之后不要再写、也不要再释放锁。
     *
     * @param token 当前租约凭据
     * @return 是否仍持有执行权
     */
    boolean renew(LeaseToken token);

    /**
     * 主动释放。实现必须校验 token 才删除，否则会误删别人的锁（锁过期后原持有者
     * 释放了新人刚拿到的锁）。
     *
     * <p>它不抛异常：调用方几乎总在 {@code finally} 里，那里抛出去的东西会盖掉本轮
     * 真正的结果（成功、失败、还是被取消）。释放失败也不要紧，锁有 TTL，最坏是别人
     * 多等一个 TTL；实现应当把失败记进日志，安静地失败和抛出去一样坏。见
     * {@code docs/decisions/architecture/2026-10-10-lease-failures-are-not-turns-that-never-ran.md}。
     *
     * @param token 要释放的租约凭据
     */
    void release(LeaseToken token);

    /**
     * 建议的续约节奏。执行器按这个间隔调 {@link #renew}；续约的调度不属于租约本身，
     * 它不知道谁在跑、什么时候跑完。
     *
     * <p>放在端口上而不是让调用方自己定：这个节奏是 TTL 的函数（通常是它的三分之一，
     * 让一次续约失败发生在锁过期之前；调用方看到失败就按失去执行权处理），而 TTL 只有
     * 实现知道。调用方随便定一个数，就可能出现「节奏比 TTL 还长」这种锁必然过期的配置。
     *
     * @return 建议的续约间隔
     */
    Duration renewInterval();
}
