package com.codeloom.app.recovery;

import com.codeloom.app.support.TestSessions;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolApprovalRequested;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolInterrupted;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.WorkspaceFence;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.session.SessionId;
import com.codeloom.app.support.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 崩溃恢复的扫描，真库。
 *
 * <p>直接调包级可见的 {@link CrashRecovery#recover} 而不是那个遍历全库的入口 ——
 * 后者在测试里会去动别的测试留下的会话。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
@Transactional
class CrashRecoveryTest {

    private static final ProjectId PROJECT_ID = ProjectId.of("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private CrashRecovery recovery;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private EventStore events;

    @Autowired
    private WorkspaceRepository worktrees;

    @Autowired
    private WorkspaceFence fence;

    @Autowired
    private ExecutionLease leases;

    @Test
    @DisplayName("【防重放】执行到一半的调用被补写成 ToolInterrupted —— 而不是让它重跑一遍")
    void interruptedToolCallsAreRecordedAsFacts() {
        Session session = savedSession();
        appendToolCall(session, "call_1");
        // 注意：**没有**对应的 ToolResult —— 模拟"进程在工具执行中途被杀"

        recovery.recover(session);

        assertThat(interruptedCallIds(session))
                .containsExactly("call_1");
    }

    @Test
    @DisplayName("已经收场的调用不会被误判成中断")
    void completedToolCallsAreLeftAlone() {
        Session session = savedSession();
        appendToolCall(session, "call_1");
        appendEvent(session, new ToolResult("call_1", true, "ok", false, 0, 5L));

        recovery.recover(session);

        assertThat(interruptedCallIds(session)).isEmpty();
    }

    @Test
    @DisplayName("【重启不毁批准】挂着等人批的调用**不是**执行到一半 —— 恢复不该碰它")
    void callsWaitingForApprovalAreNotInterrupted() {
        Session session = savedSession();
        appendToolCall(session, "call_1");
        appendEvent(session, new ToolApprovalRequested("call_1", "测试：这条命令要人批一下"));
        // 它就停在这儿等人 —— 没有 ToolResult，而且那是**刻意的**：
        // 那个调用压根还没跑，它的"结果"要等用户答复时那条 ToolApprovalResolved

        recovery.recover(session);

        // ★ 这一条守的不是"少写一条事件"，而是「重启之后那个批准还点得动」。
        //   从前这里会补一条 ToolInterrupted，它在 TurnStates 里映成 THINKING ——
        //   而 AWAITING_APPROVAL → THINKING 是非法迁移，于是**重启一次，
        //   那个等着你点的批准就变成了一个 FAILED**
        assertThat(interruptedCallIds(session)).isEmpty();
    }

    @Test
    @DisplayName("【批准过的不能漏】批完还没跑就崩了 → 仍然算执行到一半")
    void approvedButUnrunCallsAreStillInterrupted() {
        Session session = savedSession();
        appendToolCall(session, "call_1");
        appendEvent(session, new ToolApprovalRequested("call_1", "测试：这条命令要人批一下"));
        appendEvent(session, new ToolApprovalResolved("call_1", true, UserId.of("alice"), null));

        recovery.recover(session);

        // 批了就该跑。漏掉它的后果比"跑了一半"更糟 ——
        // 模型会以为那件事已经做完了，而它压根没开始
        assertThat(interruptedCallIds(session)).containsExactly("call_1");
    }

    @Test
    @DisplayName("【拒了就是结束了】被拒绝的调用不该被当成中断")
    void rejectedCallsAreFinished() {
        Session session = savedSession();
        appendToolCall(session, "call_1");
        appendEvent(session, new ToolApprovalRequested("call_1", "测试：这条命令要人批一下"));
        appendEvent(session, new ToolApprovalResolved("call_1", false, UserId.of("alice"), "别跑这个"));

        recovery.recover(session);

        assertThat(interruptedCallIds(session)).isEmpty();
    }

    @Test
    @DisplayName("【幂等】恢复跑几遍都一样 —— 不会重复补写")
    void recoveryIsIdempotent() {
        Session session = savedSession();
        appendToolCall(session, "call_1");

        recovery.recover(session);
        recovery.recover(session);
        recovery.recover(session);

        // 补写过的会从"待补"名单里划掉，所以第二次开始什么也不做。
        // 这一点很重要：这条路径**没有**"只跑一次"的机制保证（比如启动标记），
        // 它的安全性来自"重复执行不产生额外效果"这件事本身
        assertThat(interruptedCallIds(session)).containsExactly("call_1");
    }

    @Test
    @DisplayName("别的执行者还活着（树上的租约被持有）→ 跳过，不碰那条会话")
    void sessionsOwnedByAnotherInstanceAreSkipped() {
        Session session = savedSession();
        appendToolCall(session, "call_1");

        // 注意持的是**这棵树**的租约 —— 换个人（另一棵树）持锁不影响这条
        LeaseToken heldElsewhere = leases.tryAcquire(session).orElseThrow();
        try {
            recovery.recover(session);
            // 拿不到租约就什么都不做 —— 那棵树有人在管
            assertThat(interruptedCallIds(session)).isEmpty();
        } finally {
            leases.release(heldElsewhere);
        }
    }

    // ------------------------------------------------------------------

    private Session savedSession() {
        SessionId id = SessionId.generate();
        return TestSessions.persist(sessions, worktrees, id, PROJECT_ID, TestUsers.OWNER,
                "D:/ws/" + id.value());
    }

    private void appendToolCall(Session session, String callId) {
        appendEvent(session, new ToolCallRequested(callId, "edit_file", "{}"));
    }

    /** 追加事件要带 token —— 事实来源上没有"这条不用记账"的例外（测试也不例外）。 */
    private void appendEvent(Session session, com.codeloom.domain.event.PersistentEvent event) {
        events.append(session.id(), event, TestSessions.mint(session, fence));
    }

    private List<String> interruptedCallIds(Session session) {
        return events.readAll(session.id()).stream()
                .map(StoredEvent::event)
                .filter(ToolInterrupted.class::isInstance)
                .map(event -> ((ToolInterrupted) event).callId())
                .toList();
    }
}
