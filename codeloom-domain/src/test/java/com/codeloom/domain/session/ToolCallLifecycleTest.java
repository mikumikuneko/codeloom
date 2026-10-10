package com.codeloom.domain.session;

import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.SessionStateChanged;
import com.codeloom.domain.event.ToolApprovalRequested;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolCancelled;
import com.codeloom.domain.event.ToolInterrupted;
import com.codeloom.domain.event.ToolRejected;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.ToolResultsCleared;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住"哪些事件算一次调用的哪一步" —— 它是**全仓唯一**一处这么说的。
 *
 * <p>为什么值得单独钉：几个地方（投影、崩溃恢复）都靠它回答"这次调用结束了没有"，
 * 而它们问的**不是同一个问题** —— 同一件事在两边含义相反。所以这里钉的是**分类本身**
 * （哪一步是哪一步），不是谁拿它做什么。
 */
class ToolCallLifecycleTest {

    private static final UserId LI = UserId.of("u-li");

    private static List<Optional<String>> allSteps(Event event) {
        return List.of(
                ToolCallLifecycle.openedBy(event),
                ToolCallLifecycle.closedBy(event),
                ToolCallLifecycle.approvalAskedBy(event),
                ToolCallLifecycle.approvalGrantedBy(event),
                ToolCallLifecycle.approvalRefusedBy(event));
    }

    @Test
    @DisplayName("终局的那四种：跑完 / 被取消 / 被中断 / 被拒绝")
    void theFourWaysACallEnds() {
        List<Event> ends = List.of(
                new ToolResult("c1", true, "ok", false, 0, 12L),
                new ToolCancelled("c1"),
                new ToolInterrupted("c1"),
                new ToolRejected("c1"));

        for (Event event : ends) {
            assertThat(ToolCallLifecycle.closedBy(event))
                    .as("%s 应该有结局", event.getClass().getSimpleName())
                    .contains("c1");
            // 一次调用只有一步 —— 终局的那几条不该同时是别的
            assertThat(ToolCallLifecycle.openedBy(event)).isEmpty();
            assertThat(ToolCallLifecycle.approvalAskedBy(event)).isEmpty();
        }
    }

    @Test
    @DisplayName("起点、挂起、批准、拒绝，各归各的")
    void theOtherSteps() {
        assertThat(ToolCallLifecycle.openedBy(new ToolCallRequested("c1", "read_file", "{}")))
                .contains("c1");
        assertThat(ToolCallLifecycle.approvalAskedBy(new ToolApprovalRequested("c1", "要问你")))
                .contains("c1");
        assertThat(ToolCallLifecycle.approvalGrantedBy(new ToolApprovalResolved("c1", true, LI, null)))
                .contains("c1");
        assertThat(ToolCallLifecycle.approvalRefusedBy(new ToolApprovalResolved("c1", false, LI, null)))
                .contains("c1");
    }

    @Test
    @DisplayName("**批准不算终局** —— 那条调用接下来还要跑")
    void approvalIsNotAnEnding() {
        assertThat(ToolCallLifecycle.closedBy(new ToolApprovalResolved("c1", true, LI, null))).isEmpty();
        // 拒了才算：它从此没有下文
        assertThat(ToolCallLifecycle.closedBy(new ToolApprovalResolved("c1", false, LI, null))).isEmpty();
    }

    @Test
    @DisplayName("和这次调用无关的事件，五步一个都不匹配")
    void unrelatedEventsMatchNothing() {
        List<Event> unrelated = List.of(
                new UserMessage("你好"),
                new SessionStateChanged(SessionState.THINKING, SessionState.WAITING_USER, null),
                new ContextCompacted(1L, "摘要"),
                new ToolResultsCleared(List.of("c1")));

        for (Event event : unrelated) {
            assertThat(allSteps(event))
                    .as("%s 不该落在任何一步", event.getClass().getSimpleName())
                    .allMatch(Optional::isEmpty);
        }
    }
}
