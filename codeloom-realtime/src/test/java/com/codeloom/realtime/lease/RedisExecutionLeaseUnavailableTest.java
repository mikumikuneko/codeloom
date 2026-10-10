package com.codeloom.realtime.lease;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.LeaseUnavailableException;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真连一个**够不到**的 Redis，验的是那一下翻译。
 *
 * <p>要区分的是两件事：{@code tryAcquire} 返回空 = "这棵树有人管着"（409 那一类）；
 * 抛这个异常 = "这把锁现在问不到"（503 那一类）。落成同一个东西的话，客户端会把
 * "中间件挂了"读成"别人在用"，然后去等等看 —— 而真正该做的是退避重试。
 *
 * <p>**和 {@code app} 包里那个同名测试是两件事**，不能并：那个要 Redis **在**（它连真的），
 * 这个要 Redis **够不到**；并进去的话，Redis 一挂它就被跳过 —— 而那正是它唯一要验的那一刻。
 */
class RedisExecutionLeaseUnavailableTest {

    @Test
    @DisplayName("【真跑】Redis 够不到时抛 LeaseUnavailableException，不是默默返回'没抢到'")
    void anUnreachableRedisIsNotTheSameAsSomeoneElseHoldingTheLock() {
        RedisExecutionLease lease = new RedisExecutionLease(
                templateAgainstNothing(), fenceThatMustNotBeCalled(), "test", Duration.ofSeconds(30));

        assertThatThrownBy(() -> lease.tryAcquire(session()))
                .isInstanceOf(LeaseUnavailableException.class);
    }

    @Test
    @DisplayName("【真跑】还锁还不上也不抛 —— 它站在 finally 上，抛出去会盖掉本轮真正的结果")
    void releasingAgainstAnUnreachableRedisDoesNotThrow() {
        RedisExecutionLease lease = new RedisExecutionLease(
                templateAgainstNothing(), fenceThatMustNotBeCalled(), "test", Duration.ofSeconds(30));
        LeaseToken token = new LeaseToken(SessionId.generate(),
                WorkspaceId.of(UserId.generate(), ProjectId.generate()), 1, "test");

        assertThatCode(() -> lease.release(token)).doesNotThrowAnyException();
    }

    /**
     * 指向一个**没人监听**的端口。
     *
     * <p>超时给得很短、而"拒绝连接"本来就是立刻的 —— 这条测试不该在连接上耗时间。
     */
    private static StringRedisTemplate templateAgainstNothing() {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(500))
                                .build())
                        .build())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1), client);
        factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }

    /** 走到发号就说明锁已经"抢到了"，而这条测试里根本连不上 —— 它不该被碰到。 */
    private static WorkspaceFence fenceThatMustNotBeCalled() {
        return new WorkspaceFence() {
            @Override
            public long issue(WorkspaceId workspaceId) {
                throw new UnsupportedOperationException("连不上 Redis 时不该走到发号");
            }

            @Override
            public void assertValid(LeaseToken token) {
                throw new UnsupportedOperationException("这条测试不校验");
            }
        };
    }

    private static Session session() {
        return Session.create(SessionId.generate(), ProjectId.generate(), UserId.generate(),
                new ModelConfig(ProviderId.of("deepseek"), "deepseek-flash", null));
    }
}
