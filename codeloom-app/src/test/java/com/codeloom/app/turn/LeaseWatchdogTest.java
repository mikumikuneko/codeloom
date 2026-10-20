package com.codeloom.app.turn;

import com.codeloom.app.support.Await;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 续约看门狗的规则：续不上就视为失去执行权，无论续约被拒还是调用本身抛异常。
 *
 * <p>后者若不接住，异常会从虚拟线程冒出、线程静默死亡，而 {@code leaseLost} 永远为假，
 * 本轮会以为锁还在并照常跑完。
 */
class LeaseWatchdogTest {

    private static final LeaseToken TOKEN = new LeaseToken(SessionId.generate(),
            WorkspaceId.of(UserId.generate(), ProjectId.generate()), 1, "test");

    @Test
    @DisplayName("续约被拒 → 判失去执行权，并取消这一轮")
    void aRejectedRenewalLosesTheLease() {
        assertLosesTheLease(new FakeLease(Renewal.REFUSED));
    }

    @Test
    @DisplayName("【接住异常】续约调用抛异常 → 与'被拒'同一条路，而不是让线程静默死掉")
    void aThrowingRenewalLosesTheLease() {
        assertLosesTheLease(new FakeLease(Renewal.THROWS));
    }

    private void assertLosesTheLease(ExecutionLease lease) {
        CancellationToken cancellation = new CancellationToken();
        TurnExecutor.LeaseWatchdog watchdog =
                TurnExecutor.LeaseWatchdog.start(lease, TOKEN, cancellation);
        try {
            Await.until("看门狗判定失去执行权", watchdog::leaseLost, Duration.ofSeconds(5));
            assertThat(watchdog.leaseLost()).isTrue();
            assertThat(cancellation.isCancelled())
                    .as("判定失去执行权之后要立刻取消这一轮 —— 循环会在下一个工具边界上停")
                    .isTrue();
        } finally {
            watchdog.stop();
        }
    }

    private enum Renewal {
        REFUSED,
        THROWS
    }

    /** 续约间隔很短：这条测试验证判定，不验证等待。 */
    private static final class FakeLease implements ExecutionLease {

        private final Renewal behaviour;

        private FakeLease(Renewal behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public Duration renewInterval() {
            return Duration.ofMillis(10);
        }

        @Override
        public boolean renew(LeaseToken token) {
            if (behaviour == Renewal.THROWS) {
                throw new IllegalStateException("Redis 中途挂了就是这个样子");
            }
            return false;
        }

        @Override
        public Optional<LeaseToken> tryAcquire(Session session) {
            throw new UnsupportedOperationException("这条测试从「已经拿到锁」开始");
        }

        @Override
        public void release(LeaseToken token) {
            throw new UnsupportedOperationException("这条测试不释放锁");
        }
    }
}
