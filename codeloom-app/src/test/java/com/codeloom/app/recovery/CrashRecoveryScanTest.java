package com.codeloom.app.recovery;

import com.codeloom.app.support.TestSessions;
import com.codeloom.app.support.TestUsers;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolInterrupted;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 崩溃恢复的**扫描入口**（{@link CrashRecovery#recoverUnfinishedSessions()}）。
 *
 * <h2>为什么这一条是单测，而其余几条在真库上跑</h2>
 * 那个入口会遍历**整个库**。在连着共享开发库的测试里调它，等于让测试去动别人
 * 手工留下的会话 —— `CrashRecoveryTest` 的类注释里写明了这一点，所以它只调包级可见的
 * {@code recover(session)}。而"一条坏了不拖住其他"这条行为恰恰只能从入口看，
 * 于是把它放进一个不碰库的单测里。
 *
 * <p>顺带的好处：这个类不连 MySQL/Redis，在任何人机器上都跑。
 */
@ExtendWith(MockitoExtension.class)
class CrashRecoveryScanTest {

    private static final ProjectId PROJECT_ID = ProjectId.of("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private SessionRepository sessions;

    @Mock
    private EventStore events;

    @Mock
    private ExecutionLease leases;

    @Test
    @DisplayName("一条会话恢复失败不拖住其他的 —— 剩下的还能救")
    void oneBadSessionDoesNotStopTheOthers() {
        Session broken = session();
        Session fine = session();
        when(sessions.findAll()).thenReturn(List.of(broken, fine));
        when(leases.tryAcquire(broken))
                .thenThrow(new IllegalStateException("模拟恢复这一条时出错"));
        // 第二条：拿不到租约（有实例在管它），于是 recover 会提前返回 ——
        // 我们要验的只是**它被访问到了**
        when(leases.tryAcquire(fine)).thenReturn(Optional.empty());

        new CrashRecovery(sessions, events, leases).recoverUnfinishedSessions();

        verify(leases).tryAcquire(fine);
    }

    @Test
    @DisplayName("【它做的事】把悬空的调用补写成 ToolInterrupted，然后用完就把锁还了")
    void danglingCallsArePatchedAndTheLeaseIsReturned() {
        Session session = session();
        LeaseToken token = tokenFor(session);
        when(sessions.findAll()).thenReturn(List.of(session));
        when(leases.tryAcquire(session)).thenReturn(Optional.of(token));
        // 造一条"请求了、但没有结果"的调用 —— 那就是进程死掉时留下的形状
        when(events.readAll(session.id())).thenReturn(List.of(
                new StoredEvent(session.id(), 1L, Instant.parse("2026-09-25T10:00:00Z"),
                        new ToolCallRequested("call_1", "read_file", "{}"))));

        new CrashRecovery(sessions, events, leases).recoverUnfinishedSessions();

        // 这条事实不补的话，模型下次会以为那个调用成功了，可能把写操作重放一遍
        verify(events).append(eq(session.id()), any(ToolInterrupted.class), eq(token));
        // 锁一定要还。不还的话这条会话就永远被这个实例占着，用户下次发话只会拿到 Busy ——
        // 而恢复本身是启动时跑一次的后台动作，没有人会去排查"为什么一直忙"
        verify(leases).release(token);
    }

    @Test
    @DisplayName("没有悬空的调用就一个字都不写 —— 一条好好停着的会话不该被重启打扰")
    void nothingIsWrittenWhenNothingIsDangling() {
        Session session = session();
        LeaseToken token = tokenFor(session);
        when(sessions.findAll()).thenReturn(List.of(session));
        when(leases.tryAcquire(session)).thenReturn(Optional.of(token));
        when(events.readAll(session.id())).thenReturn(List.of());

        new CrashRecovery(sessions, events, leases).recoverUnfinishedSessions();

        // 类型参数写出来是为了消歧：EventStore.append 有单条和批量两个重载
        verify(events, never()).append(any(SessionId.class), any(ToolInterrupted.class),
                any(LeaseToken.class));
        // 但锁照样要放 —— 抢到了就得负责还
        verify(leases).release(token);
    }

    // ------------------------------------------------------------------

    private static Session session() {
        return Session.create(SessionId.generate(), PROJECT_ID, TestUsers.OWNER,
                TestSessions.DEFAULT_MODEL);
    }

    /**
     * 这一组是 mock 出来的租约，没有真库去发号 —— 所以号直接编一个。
     * 但 {@code workspaceId} 还得从会话推，因为 {@code MyBatisEventStore} 会拿它去校验。
     */
    private static LeaseToken tokenFor(Session session) {
        return new LeaseToken(session.id(), session.workspaceId(), 1L, "test-instance");
    }
}
