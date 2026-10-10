package com.codeloom.domain.port;

import com.codeloom.domain.session.Session;
import java.time.Duration;
import java.util.Optional;

/**
 * 执行租约：保证**一棵工作区**在任意时刻全局只有一个执行者。
 *
 * <p>实现是 Redis 上的 {@code SET NX PX} + Lua 续约（{@code codeloom-realtime}）。
 * 选择自研而不是用 Redisson 的 {@code RLock}，是因为续期、竞态、误删这三件事
 * 恰恰是最值得讲清楚的部分，而它们正是 Redisson 帮你封装掉的东西。
 *
 * <p>租约过期**本身就是"实例已死"的检测机制** —— 不需要另建一套心跳存活检测，
 * 崩溃恢复（扫描非终态会话）直接复用它。
 *
 * <h2>为什么键是会话而锁是树</h2>
 * 入参是 {@link Session}，锁的却是它所在的那棵树（{@code session.workspaceId()}）。
 * 看上去多了一层，实际是**把"哪棵树"这个推导收在一处**：调用方手上永远只有会话，
 * 让它在数据库的每个名字上都自己拼一次 {@code ownerId + projectId}，迟早有一处拼错，
 * 而拼错的后果是**两个会话拿到同一棵树上互斥的两把锁** —— 那正是这个接口要防的事。
 *
 * <p>锁是树级的，所以同一个人的两条会话天然互斥（它们共用一棵树）；
 * 两个不同的人各有各的树，照旧能同时跑。
 *
 * <h2>调用方的义务</h2>
 * <ol>
 *   <li><b>拿到了就一定在 {@code finally} 里还。</b> 忘了还不会造成错误，
 *       但那条会话要等到 TTL 到期才能被别人接手 —— 而 TTL 是"实例已死"的判据，
 *       拿它当"正常路径的兜底"等于把一条 30 秒的停顿塞进用户的每次操作里。
 *   <li><b>拿不到就自己决定怎么办，没有统一答案。</b> 这个端口刻意不提供
 *       {@code acquireOrThrow} 之类的便利方法：拿不到租约在五个调用点的含义完全不同 ——
 *       执行器要回一个"忙"（让界面显示重试按钮）、合并/回滚要回 409、
 *       崩溃恢复要默默跳过。**把这几种语义收成一个方法只会得到一个处处要传 flag 的签名。**
 *   <li><b>续约返回 false 之后不要再写、也不要再还锁</b>（见 {@link #renew}）。
 * </ol>
 */
public interface ExecutionLease {

    /**
     * 尝试拿这条会话所在那棵树的租约，拿不到立刻返回空（不阻塞、不排队）。
     *
     * <p>拿到锁只说明"此刻没有别人在动这棵树"，**不代表后续写入都安全** ——
     * 后续每次写入仍要带 {@link LeaseToken} 做校验。
     */
    Optional<LeaseToken> tryAcquire(Session session);

    /**
     * 续约。
     *
     * <p><strong>返回 false 意味着已经失去执行权，调用方必须立刻中止</strong>，
     * 而不是继续跑完这一轮 —— 继续跑的写入会被 {@link EventStore} 用 fencing token 拒绝，
     * 但工作区里的文件改动不会被拒绝，所以必须主动停下来。
     */
    boolean renew(LeaseToken token);

    /**
     * 主动释放。实现必须校验 token 才删除，否则会误删别人的锁
     * （经典的"锁过期后原持有者释放了新人刚拿到的锁"）。
     *
     * <p><strong>它不抛异常。</strong>调用方几乎总是站在 {@code finally} 上，那里抛出去的东西会
     * <strong>盖掉本轮真正的结果</strong>（成功、失败、还是被取消）—— 而那个结果才是调用方要的。
     * 还不上也不要紧：锁有 TTL，最坏是别人多等一个 TTL。实现应当把失败**记进日志**，
     * 别不声不响 —— 安静地失败和抛出去一样坏。
     */
    void release(LeaseToken token);

    /**
     * 建议的续约节奏。执行器按这个间隔调 {@link #renew} —— **续约的调度不属于租约本身**，
     * 它不知道谁在跑、什么时候跑完。
     *
     * <p>之所以放在端口上而不是让调用方自己定：这个节奏是 TTL 的函数（通常是它的三分之一，
     * 允许连续两次续约失败才判死），而 TTL 只有实现知道。调用方拍一个数出来，
     * 就会出现"节奏比 TTL 还长"这种锁必然过期的配置。
     */
    Duration renewInterval();
}
