package com.codeloom.realtime.lease;

import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.workspace.WorkspaceId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ExecutionLease} 的 Redis 实现：{@code SET NX PX} 抢锁 + Lua 续约/释放。
 *
 * <h2>为什么不用 Redisson 的 RLock</h2>
 * 它会把续期、竞态、误删这三件事一次性封装掉 —— 而这三件事在这里需要展开在代码里，
 * 每一处都能指着解释"为什么必须这样"。
 *
 * <h2>Redis 在这个机制里只管两件事</h2>
 * <ol>
 *   <li><b>快速互斥</b>：一棵工作区同一时刻只有一个执行者。
 *   <li><b>死实例探测</b>：租约过期本身就是"那个实例已经不在了"。
 *       不需要另建一套心跳存活检测 —— 崩溃恢复（扫描非终态会话）直接复用它。
 * </ol>
 *
 * <p>**它不分配 fencing token 的序号。** fencing token 由 {@link WorkspaceFence} 在 MySQL 里发，因为那必须是
 * 持久且不可能回退的，而 Redis 的 AOF everysec 加异步主从都做不到这一点 ——
 * 计数器一旦回退到低于已受理的高水位，这棵树会永久写不进去。
 *
 * <h2>锁的键是一棵树，不是一条会话</h2>
 * 同一个人的两条会话共用一棵树，所以它们必须在**同一把锁**上互斥 ——
 * 键要是还按会话走，两条会话就会各自拿到一把锁，然后同时写同一份目录。
 * 那个推导收在 {@code Session.workspaceId()} 里，见 {@link ExecutionLease} 的类注释。
 *
 * <h2>锁的值是 {@code 实例 id + 随机后缀}</h2>
 * 不是裸的实例 id。理由见 {@link LeaseToken#holderId()}：同一个实例内部也会出现
 * "老线程删掉新线程的锁"，光靠实例 id 分不出来。
 */
@Component
public class RedisExecutionLease implements ExecutionLease {

    /**
     * 键的前缀。后面接的是 {@code WorkspaceId} 的文本形式（两个 UUID，冒号分隔），
     * 所以不会和别人撞。
     *
     * <p>刻意不做成 hash 结构（{@code HSET locks workspaceId value}）：一棵树一把锁、
     * 带自己的 TTL，用独立的键最直观，过期也彼此独立 —— 一棵树的锁过期不该影响另一棵。
     */
    private static final String KEY_PREFIX = "codeloom:workspace-lock:";

    /**
     * 续约与释放共用同一个判断：值对得上才动手。
     *
     * <p>必须在 Lua 里做，因为它得是原子的。分成「GET 比对」和「PEXPIRE/DEL」两条命令的话，
     * 中间那一瞬间锁可能已经过期并被别人拿到 —— 于是我们删掉的是别人的锁。
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('pexpire', KEYS[1], ARGV[2])
                return 1
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final WorkspaceFence fence;
    private final String instanceId;
    private final Duration leaseTtl;

    public RedisExecutionLease(StringRedisTemplate redis,
                               WorkspaceFence fence,
                               @Value("${codeloom.instance-id:${random.uuid}}") String instanceId,
                               @Value("${codeloom.lease-ttl:30s}") Duration leaseTtl) {
        this.redis = redis;
        this.fence = fence;
        this.instanceId = instanceId;
        this.leaseTtl = leaseTtl;
        if (leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("租约 TTL 必须是正数，收到 " + leaseTtl);
        }
    }

    /** 锁的键。公开的理由同 {@code RedisEventBus.CHANNEL}：排障时能在 redis-cli 里找到它。 */
    public static String keyFor(WorkspaceId workspaceId) {
        return KEY_PREFIX + workspaceId.value();
    }

    /**
     * {@inheritDoc}
     *
     * <p>取 TTL 的三分之一：允许连续两次续约失败才判死，能扛过一次网络抖动。
     * 反过来 TTL 也不能太短，否则一个跑构建的长工具调用会在中途失去锁。
     */
    @Override
    public Duration renewInterval() {
        return leaseTtl.dividedBy(3);
    }

    @Override
    public Optional<LeaseToken> tryAcquire(Session session) {
        Objects.requireNonNull(session, "session");
        // 入参是会话、锁的键是它所在的那棵树 —— 这个换算只在这里做一次，
        // 见 ExecutionLease 类注释「为什么键是会话而锁是树」
        WorkspaceId workspaceId = session.workspaceId();
        String key = keyFor(workspaceId);
        String holderId = instanceId + "/" + UUID.randomUUID();

        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, holderId, leaseTtl))) {
            // 不阻塞、不排队：拿不到就是拿不到，让调用方自己决定（多半是拒绝这次请求）
            return Optional.empty();
        }

        try {
            // 发号必须在【抢到锁之后】。反过来做的话，一个抢锁失败的竞争者会把号推过
            // 当前持有者的 token，于是持有者下一次写入被 fence 校验拒掉 —— 当前有效的持有者
            // 反而写不进去。
            long fencingToken = fence.issue(workspaceId);
            return Optional.of(new LeaseToken(session.id(), workspaceId, fencingToken, holderId));
        } catch (RuntimeException e) {
            // 锁拿到了却没还回去，它会一直被占到 TTL 结束 —— 那段时间里谁都拿不到这棵树的锁。
            // 发号失败（比如工作区还不存在）是调用方的问题，不该让整棵树因此被锁住。
            releaseQuietly(key, holderId);
            throw e;
        }
    }

    @Override
    public boolean renew(LeaseToken token) {
        Long renewed = redis.execute(RENEW_SCRIPT, List.of(keyFor(token.workspaceId())),
                token.holderId(), String.valueOf(leaseTtl.toMillis()));
        // 返回 false 意味着**已经失去执行权**，调用方必须立刻中止本轮，
        // 而不是继续跑完 —— 后续写入会被 fencing token 拒绝，但工作区里的文件改动不会被拒绝
        return renewed == 1L;
    }

    @Override
    public void release(LeaseToken token) {
        redis.execute(RELEASE_SCRIPT, List.of(keyFor(token.workspaceId())), token.holderId());
    }

    /**
     * 还锁，但**绝不把异常抛出去**。
     *
     * <p>只用在 {@link #tryAcquire} 的失败分支：那里已经有一个正在往外抛的异常了，
     * 再抛一个会把它顶掉，而它才是调用方需要看到的那个原因。
     * 还锁失败不是什么大事 —— 锁有 TTL，最坏就是多占一会儿。
     */
    private void releaseQuietly(String key, String holderId) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key), holderId);
        } catch (RuntimeException ignored) {
            // 刻意吞掉：见上面
        }
    }
}
