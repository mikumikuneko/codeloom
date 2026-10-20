package com.codeloom.realtime.lease;

import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.LeaseUnavailableException;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.workspace.WorkspaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ExecutionLease} 的 Redis 实现：{@code SET NX PX} 抢锁，Lua 脚本续约与释放。
 *
 * <p>Redis 在这里只负责两件事：同一棵工作区同一时刻只有一个执行者，以及用租约过期
 * 探测死实例。后者让崩溃恢复不必另建心跳检测。
 *
 * <p>它不分配 fencing token 的序号。序号由 {@link WorkspaceFence} 在 MySQL 里发，
 * 必须持久且不会回退；Redis 的 AOF everysec 和异步主从都保证不了这一点。计数器一旦
 * 回退到低于已受理的高水位，这棵树会永久写不进去。
 *
 * <p>锁的键是一棵树而不是一条会话：同一个人的多条会话共用一棵树，必须互斥在同一把锁上，
 * 否则它们会同时写同一份目录。锁的值是实例 id 加随机后缀，而不是裸的实例 id，因为
 * 同一实例内部也会出现老线程删掉新线程锁的情况，见 {@link LeaseToken#holderId()}。
 *
 * <p>理由见 {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
 */
@Component
public class RedisExecutionLease implements ExecutionLease {

    private static final Logger log = LoggerFactory.getLogger(RedisExecutionLease.class);

    /**
     * 键前缀，后面接 {@code WorkspaceId} 的文本形式（两个 UUID，冒号分隔）。
     *
     * <p>每棵树一个独立键、各自带 TTL，一棵树的锁过期不影响另一棵；用 hash 结构做不到
     * 这种彼此独立的过期，也没有必要。
     */
    private static final String KEY_PREFIX = "codeloom:workspace-lock:";

    /**
     * 续约与释放共用同一个判断：值对得上才动手。
     *
     * <p>必须在 Lua 里做才能原子。拆成 GET 比对和 PEXPIRE/DEL 两条命令时，中间锁可能
     * 已经过期并被别人拿到，于是我们会续期或删掉别人的锁。
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

    /**
     * @param redis      Redis 客户端
     * @param fence      MySQL 侧的 fencing 发号与校验
     * @param instanceId 本实例标识；默认随机 UUID
     * @param leaseTtl   租约 TTL；必须为正
     * @throws IllegalArgumentException TTL 不是正数
     */
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

    /**
     * 锁的键。公开是为了排障时能在 {@code redis-cli} 里找到它。
     *
     * @param workspaceId 这棵树
     * @return Redis 键
     */
    public static String keyFor(WorkspaceId workspaceId) {
        return KEY_PREFIX + workspaceId.value();
    }

    /**
     * {@inheritDoc}
     *
     * <p>取 TTL 的三分之一：给续约留出余量。执行器一次续约失败或抛异常就按失去执行权
     * 处理，不会等到 TTL 结束；这个间隔只是让失败发生在锁过期之前。TTL 也不能太短，
     * 否则一个跑构建的长工具调用会在中途失去锁。
     */
    @Override
    public Duration renewInterval() {
        return leaseTtl.dividedBy(3);
    }

    /**
     * {@inheritDoc}
     *
     * <p>不阻塞、不排队：拿不到就返回空，由调用方决定怎么处理。Redis 连不上时抛
     * {@link LeaseUnavailableException}，而不是返回空，因为“问不到锁”和“别人持有锁”
     * 的处置不同。抢到锁之后才发 fencing token；发号失败会先还锁再抛出。
     *
     * @throws LeaseUnavailableException Redis 连不上、超时或命令被拒
     */
    @Override
    public Optional<LeaseToken> tryAcquire(Session session) {
        Objects.requireNonNull(session, "session");
        // 入参是会话，锁的键是它所在的那棵树；这个换算只在这里做一次。
        WorkspaceId workspaceId = session.workspaceId();
        String key = keyFor(workspaceId);
        String holderId = instanceId + "/" + UUID.randomUUID();

        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, holderId, leaseTtl));
        } catch (DataAccessException e) {
            // 连不上、超时、命令被拒：这把锁现在问不到，不能当成“别人持有”。
            throw new LeaseUnavailableException(
                    "执行租约服务（Redis）连不上 —— 这一轮没法开始。稍后重试", e);
        }
        if (!acquired) {
            return Optional.empty();
        }

        try {
            // 发号必须在抢到锁之后。反过来的话，抢锁失败的竞争者也会把号推过当前持有者，
            // 导致持有者下一次写入被 fence 校验拒掉。
            long fencingToken = fence.issue(workspaceId);
            return Optional.of(new LeaseToken(session.id(), workspaceId, fencingToken, holderId));
        } catch (RuntimeException e) {
            // 锁拿到了却没还回去，会一直占到 TTL 结束，期间谁都拿不到这棵树的锁。
            // 发号失败（例如工作区还不存在）是调用方的问题，不该因此锁住整棵树。
            quietly("还锁", key, holderId);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>返回 false 表示已经失去执行权，调用方必须立刻中止本轮：后续写入会被 fencing
     * token 拒绝，但工作区里已经发生的文件改动不会被拒绝。
     */
    @Override
    public boolean renew(LeaseToken token) {
        Long renewed = redis.execute(RENEW_SCRIPT, List.of(keyFor(token.workspaceId())),
                token.holderId(), String.valueOf(leaseTtl.toMillis()));
        return renewed == 1L;
    }

    @Override
    public void release(LeaseToken token) {
        quietly("还锁", keyFor(token.workspaceId()), token.holderId());
    }

    /**
     * 还锁，绝不把异常抛出去。
     *
     * <p>两种调用位置都要求它不抛：{@link #tryAcquire} 的失败分支已经有一个正在抛出的
     * 异常，再抛一个会把它顶掉；{@code finally} 里抛出的东西会盖掉本轮真正的结果。
     *
     * <p>还不上也不严重：锁有 TTL，最坏是别人多等一个 TTL。失败记日志，不能静默。
     */
    private void quietly(String what, String key, String holderId) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key), holderId);
        } catch (RuntimeException e) {
            log.warn("{}失败（{}），锁会在 TTL 到期时自己消失", what, key, e);
        }
    }
}
