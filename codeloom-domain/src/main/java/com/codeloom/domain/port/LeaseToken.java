package com.codeloom.domain.port;

import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.workspace.WorkspaceId;

import java.util.Objects;

/**
 * 执行租约的凭据，也就是 **fencing token**。
 *
 * <h2>它解决什么问题</h2>
 * 分布式锁的经典失效场景：实例 A 拿到锁后发生长时间的 GC 停顿（或网络分区），
 * 续约失败、锁到期；实例 B 拿到锁开始干活；A 从停顿中恢复，**它不知道自己已经失去执行权**，
 * 继续跑完并写入数据 —— 于是 A 和 B 同时写，数据被污染。
 *
 * <p>这个缺口无法靠"把 TTL 调长"或"好好做续约"补上，因为**锁的过期和持有者的知情之间
 * 必然存在时延**。所以做法是：不只锁"进入"，还要校验"写入"。
 *
 * <ul>
 *   <li>每次抢到锁，Redis 原子地返回一个**单调递增**的序号 → {@code fencingToken}</li>
 *   <li>所有产生副作用的写入都带上它（见 {@link EventStore#append}）</li>
 *   <li>写入路径校验它是否仍是当前有效值，不是就**拒绝**</li>
 * </ul>
 *
 * <p>锁可以是概率性的，写入校验是确定性的 —— 这才是"水平扩展下不出问题"的兑现。
 *
 * <h2>锁、号、会话：三个东西，两个键</h2>
 * 锁和号都挂在 {@link WorkspaceId}（一棵树）上，理由见那个类的注释：同一棵树上
 * 不允许两个执行者，哪怕是同一个人的两条会话。
 *
 * <p>那为什么还要带一个 {@link #sessionId}？因为它**不是**用来定位锁的，而是
 * {@link EventStore} 那道校验的判据：「拿 A 会话的合法 token 去写 B 会话」是这套机制
 * 最容易被绕过的一种用法 —— token 本身没毛病、fence 校验也会通过（它校验的是那棵树），
 * 而写下去的是另一条会话。带上它，那次写入就能被当场认出来。
 *
 * <p>换句话说：<b>token 的"身份"是树，token 的"用途"是替某一条会话干活。</b>
 * 每一次抢锁都对应一条具体的会话（执行一轮、同步一次、回滚一次，都归属于某条会话），
 * 所以这个字段不是补上去的，是本来就成立的事实。
 *
 * <h2>{@code holderId} 为什么必须每次抢锁都不同</h2>
 * 它是锁在 Redis 里的**值**，而释放锁是"比对值再删"（否则会误删别人的锁）。
 * 如果它只等于实例 id，就会出现这样一个洞：
 *
 * <pre>
 *   同一实例的线程 A 抢到锁（值 = inst-1）
 *   A 长时间停顿，租约到期
 *   同一实例的线程 B 抢到锁 —— 值还是 inst-1，因为实例没变
 *   A 醒来，调用 release()，比对值相等 → 删掉了 B 的锁
 * </pre>
 *
 * <p>两个人身上的经典误删场景，在**同一个实例内部**同样成立。所以值里必须带上
 * 每次抢锁都不一样的部分；实例 id 只作为可读前缀留着，方便在 {@code redis-cli} 里
 * 一眼看出是谁拿着。
 *
 * @param sessionId    这次抢锁是替哪条会话抢的。见上面「锁、号、会话」那一节
 * @param workspaceId  锁和号挂在哪棵树上
 * @param fencingToken 单调递增序号。同一棵树上，后抢到的租约一定比先抢到的大
 * @param holderId     **本次抢锁**的持有者标识。不是"实例 id"一个静态值，
 *                     而是每次抢锁都要换一个的（{@code 实例 id + 随机后缀}）。
 *                     理由见下。
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
