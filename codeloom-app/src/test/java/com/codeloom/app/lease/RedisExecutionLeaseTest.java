package com.codeloom.app.lease;

import com.codeloom.app.support.Await;
import com.codeloom.app.support.TestSessions;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import com.codeloom.realtime.lease.RedisExecutionLease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 执行租约的 Redis 实现，真连 Redis 和 MySQL。
 *
 * <p>为什么需要 MySQL：{@code tryAcquire} 里发的号来自 {@link WorkspaceFence}，
 * 那是持久化在 workspace 表上的。所以这里的"锁"和"号"都是真的。
 *
 * <p>为什么每个测试自己 {@code new} 租约实例而不是注入 Spring 那个：
 * 需要给不同实例配**不同的 TTL**，才能在不手工删键的前提下制造"租约真的过期了"。
 * 手工删键测的是"键没了会怎样"，让 TTL 自然过期测的才是**接管这条真实路径**。
 *
 * <h2>隔离靠"每个测试一棵新树"，不再是"每条新会话"</h2>
 * Redis 的状态不参与事务回滚，所以键必须天然唯一，否则上一个测试留下的锁会把下一个卡住
 * （表现成"抢锁莫名失败"）。锁的键现在是一棵树，而树由「人 + 项目」决定 —— 项目是常量，
 * 所以**每个测试自己生成一个 owner**。这一点很容易在改测试时踩到：以前生成一个新
 * sessionId 就够了，现在生成新 sessionId 会落到**同一棵树**上。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
@Transactional
class RedisExecutionLeaseTest {

    private static final Duration LONG_TTL = Duration.ofSeconds(30);
    private static final ProjectId PROJECT_ID = ProjectId.of("77777777-7777-7777-7777-777777777777");

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private WorkspaceFence fence;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private WorkspaceRepository worktrees;

    // ------------------------------------------------------------------
    // 互斥
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一棵树同一时刻只有一个执行者：第二个抢不到，而且不排队")
    void onlyOneHolderAtATime() {
        Session session = savedSession();

        assertThat(lease("inst-a").tryAcquire(session)).isPresent();
        // 返回空而不是阻塞等待 —— 拿不到就是拿不到，由调用方决定怎么办
        assertThat(lease("inst-b").tryAcquire(session)).isEmpty();
    }

    @Test
    @DisplayName("【共享工作区】同一个人的两条会话互斥 —— 它们共用一棵树，不能同时写")
    void twoSessionsOfOnePersonAreMutuallyExclusive() {
        // 这是把树的键从"每会话"提到"每人"之后**必须**成立的一条：
        // 两条会话共用一份目录，要是各自拿到一把锁，它们就会同时改写同一批文件 ——
        // 而那正是 worktree 隔离本来要防的事。
        Session first = savedSession();
        Session second = sameTreeAs(first);

        assertThat(lease("inst-a").tryAcquire(first)).isPresent();
        assertThat(lease("inst-b").tryAcquire(second)).isEmpty();
    }

    @Test
    @DisplayName("【隔离还在】两个不同的人各有各的树，照旧能同时跑")
    void twoPeopleAreNotBlockedByEachOther() {
        // 隔离没有消失，只是粒度从"每条会话"提到了"每个人"。
        // 少了这一条，上面那条互斥可以被"干脆全局一把锁"蒙过去 —— 而那会让协作变成排队
        Session alice = savedSession();
        Session bob = savedSession();   // 另一个 owner，另一棵树

        assertThat(lease("inst-a").tryAcquire(alice)).isPresent();
        assertThat(lease("inst-b").tryAcquire(bob)).isPresent();
    }

    @Test
    @DisplayName("正常释放之后别人立刻能接管，不用等到 TTL")
    void releaseFreesTheLockImmediately() {
        Session session = savedSession();
        RedisExecutionLease holder = lease("inst-a");
        LeaseToken first = holder.tryAcquire(session).orElseThrow();

        holder.release(first);

        LeaseToken second = lease("inst-b").tryAcquire(session).orElseThrow();
        // 接管必然拿到更大的号，否则 fencing 就失效了
        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
    }

    // ------------------------------------------------------------------
    // 续约与误删
    // ------------------------------------------------------------------

    @Test
    @DisplayName("续约只对当前持有者有效")
    void renewOnlyWorksForTheCurrentHolder() {
        Session session = savedSession();
        RedisExecutionLease holder = lease("inst-a");
        LeaseToken token = holder.tryAcquire(session).orElseThrow();

        assertThat(holder.renew(token)).isTrue();

        // 值对不上（比如已经换成别人了）就必须返回 false —— 调用方看到它要立刻中止本轮，
        // 而租约本身的返回值只是个提示：真正拦住写入的是 fencing token
        assertThat(holder.renew(new LeaseToken(session.id(), session.workspaceId(),
                token.fencingToken(), "inst-b/other"))).isFalse();
    }

    @Test
    @DisplayName("【安全约束】租约过期后别人接管，老持有者的 release 删不掉新锁")
    void expiredHolderCannotReleaseTheNewLock() {
        Session session = savedSession();
        // 老实例的 TTL 只有 300ms，让它自己过期 —— 走的才是真实的接管路径
        RedisExecutionLease oldHolder = lease("inst-old", Duration.ofMillis(300));
        RedisExecutionLease newHolder = lease("inst-new");

        LeaseToken old = oldHolder.tryAcquire(session).orElseThrow();
        // 等老租约**真的**过期。固定 sleep(400) 对 300ms 的 TTL 看着够，但机器一忙就不够 ——
        // 而那会表现成"抢锁失败抛 NoSuchElement"，看起来像锁实现坏了。
        // 轮询到抢到为止，慢机器上多等一会儿就对了
        LeaseToken fresh = Await.untilPresent("老租约过期、新持有者接管",
                () -> newHolder.tryAcquire(session));

        // 老持有者从停顿里醒来，它并不知道自己已经失去执行权
        oldHolder.release(old);

        // 新锁必须还在。这正是 LeaseToken.holderId 每次抢锁都要换一个的原因：
        // 如果锁的值只是实例 id，那上面这行删掉的就是新持有者的锁 ——
        // 而且"同一个实例内部的误删"用实例 id 是分不出来的
        assertThat(newHolder.renew(fresh)).isTrue();
        assertThat(fresh.fencingToken()).isGreaterThan(old.fencingToken());
    }

    // ------------------------------------------------------------------
    // 失败路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("发号失败时锁要还回去，不能让它空占到 TTL 结束")
    void lockIsReturnedWhenIssuingFails() {
        // 库里没有这棵树，发号会抛。注意造的是"没落过库的**树**"（新 owner），
        // 而不是"没落过库的会话" —— 号是按树发的
        Session unknown = orphanSession();
        RedisExecutionLease holder = lease("inst-a");

        assertThatThrownBy(() -> holder.tryAcquire(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区不存在");

        // 键不在了 —— 是"还回去了"，不是"挂着等 30 秒"。否则这段时间里这棵树谁也进不来
        assertThat(redis.hasKey(RedisExecutionLease.keyFor(unknown.workspaceId()))).isFalse();
    }

    @Test
    @DisplayName("TTL 不接受非正数 —— 那等于一把立刻过期的锁")
    void nonPositiveTtlIsRejected() {
        assertThatThrownBy(() -> lease("inst-a", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须是正数");
    }

    @Test
    @DisplayName("续约节奏是 TTL 的三分之一 —— 允许连续两次续约失败才判死")
    void renewIntervalIsOneThirdOfTtl() {
        assertThat(lease("inst-a", Duration.ofSeconds(30)).renewInterval())
                .isEqualTo(Duration.ofSeconds(10));
    }

    // ------------------------------------------------------------------

    private RedisExecutionLease lease(String instanceId) {
        return lease(instanceId, LONG_TTL);
    }

    private RedisExecutionLease lease(String instanceId, Duration ttl) {
        return new RedisExecutionLease(redis, fence, instanceId, ttl);
    }

    /**
     * 一条真会话**和它那棵树**，owner 每次都是新的。
     *
     * <p>owner 必须每次不同：树的键是「人 + 项目」而项目是常量，所以复用 owner 就会让两个
     * 测试落在同一棵树上 —— 而 Redis 的锁不跟着事务回滚。
     */
    private Session savedSession() {
        return savedFor(UserId.generate());
    }

    /** 同一个人的另一条会话 —— 同一棵树。 */
    private Session sameTreeAs(Session other) {
        return savedFor(other.ownerId());
    }

    /** 一条会话和一个 owner，但**不落那棵树** —— 用来测"对不存在的树发号"。 */
    private Session orphanSession() {
        return Session.create(SessionId.generate(), PROJECT_ID, UserId.generate(),
                TestSessions.DEFAULT_MODEL);
    }

    private Session savedFor(UserId owner) {
        Session session = TestSessions.persist(sessions, worktrees, SessionId.generate(),
                PROJECT_ID, owner, "D:/ws/" + SessionId.generate().value(), "basecommit0");
        return session;
    }
}
