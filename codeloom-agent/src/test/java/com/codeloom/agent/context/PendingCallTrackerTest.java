package com.codeloom.agent.context;

import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PendingCallTracker} 的回滚规则 —— **直接喂这个单元**。
 *
 * <p>为什么不走投影：这条规则只关于它自己那两张表（哪次调用有过结局、请求长什么样），
 * 而走投影还得连带造出能过消息折叠的事件流 —— 判据会被不相干的东西牵扯。
 * "同一个调用 id 跨两条时间线"那一版在 {@code ContextAssemblerTest} 里也有一条（端到端）。
 */
class PendingCallTrackerTest {

    private static final SessionId SESSION = SessionId.of("tracker");

    private final PendingCallTracker tracker = new PendingCallTracker();
    private long seq;

    private void accept(Event event) {
        tracker.accept(new StoredEvent(SESSION, ++seq, Instant.parse("2026-10-10T10:00:00Z"), event));
    }

    @Test
    @DisplayName("没有回滚时：尾部是一次批准答复、那次调用没有收尾 → 就是它")
    void anApprovedCallWithoutAnOutcomeIsPending() {
        accept(new ToolCallRequested("call_1", "run_command", "{\"command\":\"pwd\"}"));
        accept(new ToolApprovalResolved("call_1", true, UserId.of("u-li"), null));

        assertThat(tracker.pendingApprovedCall()).hasValueSatisfying(call ->
                assertThat(call.argumentsJson()).contains("pwd"));
    }

    @Test
    @DisplayName("【回滚】被退掉的那条旧收尾不算数 —— 同一个 id 再被批准时还得补跑")
    void aRewindForgetsTerminalRecordsFromTheDiscardedTimeline() {
        accept(new ToolCallRequested("call_1", "run_command", "{\"command\":\"ls\"}"));
        accept(new ToolResult("call_1", true, "a.java", false, 0, 4));
        accept(new SessionRewound("sha0", 1L, UserId.of("u-li")));
        // 新的时间线上，模型又用了同一个 id
        accept(new ToolCallRequested("call_1", "run_command", "{\"command\":\"pwd\"}"));
        accept(new ToolApprovalResolved("call_1", true, UserId.of("u-li"), null));

        // 旧的那条收尾要是不作废，这次批准会被判成"已经结束了"，于是**补跑不会发生**：
        // 用户点了批准，那条命令却再也不会跑（和漏广播是同一类症状 —— 操作了、没反应）
        assertThat(tracker.pendingApprovedCall()).hasValueSatisfying(call ->
                assertThat(call.argumentsJson()).contains("pwd"));
    }

    @Test
    @DisplayName("【回滚】切点**之前**的那条收尾照算 —— 它说的是这条时间线上的事")
    void terminalRecordsBeforeTheCutStillCount() {
        accept(new ToolCallRequested("call_1", "run_command", "{\"command\":\"ls\"}"));
        accept(new ToolResult("call_1", true, "a.java", false, 0, 4));
        accept(new SessionRewound("sha0", 2L, UserId.of("u-li")));
        accept(new ToolApprovalResolved("call_1", true, UserId.of("u-li"), null));

        assertThat(tracker.pendingApprovedCall()).isEmpty();
    }
}
