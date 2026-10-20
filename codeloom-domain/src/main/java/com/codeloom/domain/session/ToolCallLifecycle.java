package com.codeloom.domain.session;

import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.LlmRetryScheduled;
import com.codeloom.domain.event.ModelChanged;
import com.codeloom.domain.event.PlatformInstruction;
import com.codeloom.domain.event.ReasoningDelta;
import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.SessionStarted;
import com.codeloom.domain.event.SessionStateChanged;
import com.codeloom.domain.event.SessionSynced;
import com.codeloom.domain.event.TodoListUpdated;
import com.codeloom.domain.event.ToolApprovalRequested;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolCancelled;
import com.codeloom.domain.event.ToolInterrupted;
import com.codeloom.domain.event.ToolRejected;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.ToolResultsCleared;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.event.VerificationResult;
import com.codeloom.domain.event.WorkspaceChanges;

import java.util.Optional;

/**
 * 「一次工具调用走到了哪一步」——从事件里读出来，全仓只有这一处。
 *
 * <p>投影、崩溃恢复与压缩都要问"这次调用结束了没有"，但各自问的不是同一件事；共同点只有
 * "哪些事件算终局"。若终局事件在两处各列一遍，漏掉一种不会报错——崩溃恢复会给一条早已
 * 结束的调用再补一条"进程重启、没有完成"。这里只给步名，同一件事在各处含义相反，由各方
 * 自己判断。见 {@code docs/decisions/bug-fix/2026-10-10-a-waiting-call-is-not-an-interrupted-call.md}。
 *
 * <p>实现是穷尽 switch 表达式、不带 default：新增一种事件，这里编译不过。
 */
public final class ToolCallLifecycle {

    private ToolCallLifecycle() {
    }

    /** 这条事件在调用生命线上的一步。 */
    private enum Change {
        /** 请求了。 */
        OPENED,
        /** 有结局了：跑完 / 被取消 / 被中断 / 被拒绝——终局是这四种。 */
        CLOSED,
        /** 挂起等人批。 */
        APPROVAL_ASKED,
        /** 批了（那条调用于是又欠一次执行）。 */
        APPROVAL_GRANTED,
        /** 拒了（随后由 {@code ToolRejected} 收尾）。 */
        APPROVAL_REFUSED,
    }

    private record Step(String callId, Change change) {
    }

    /**
     * 这次调用被请求了——生命线的起点。
     *
     * @param event 待判定的事件
     * @return 调用 id；事件不是 {@code ToolCallRequested} 时为空
     */
    public static Optional<String> openedBy(Event event) {
        return callIdOf(event, Change.OPENED);
    }

    /**
     * 这次调用有结局了：跑完、被取消、被中断、被拒绝。
     *
     * @param event 待判定的事件
     * @return 调用 id；事件不是这四种终局之一时为空
     */
    public static Optional<String> closedBy(Event event) {
        return callIdOf(event, Change.CLOSED);
    }

    /**
     * 这次调用挂起等人批。
     *
     * @param event 待判定的事件
     * @return 调用 id；事件不是 {@code ToolApprovalRequested} 时为空
     */
    public static Optional<String> approvalAskedBy(Event event) {
        return callIdOf(event, Change.APPROVAL_ASKED);
    }

    /**
     * 这次调用被批准了。
     *
     * @param event 待判定的事件
     * @return 调用 id；事件不是批准答复时为空
     */
    public static Optional<String> approvalGrantedBy(Event event) {
        return callIdOf(event, Change.APPROVAL_GRANTED);
    }

    /**
     * 这次调用被拒绝了。
     *
     * @param event 待判定的事件
     * @return 调用 id；事件不是拒绝答复时为空
     */
    public static Optional<String> approvalRefusedBy(Event event) {
        return callIdOf(event, Change.APPROVAL_REFUSED);
    }

    private static Optional<String> callIdOf(Event event, Change wanted) {
        return stepOf(event).filter(step -> step.change() == wanted).map(Step::callId);
    }

    private static Optional<Step> stepOf(Event event) {
        return switch (event) {
            case ToolCallRequested(String callId, String ignoredTool, String ignoredArgs) ->
                    Optional.of(new Step(callId, Change.OPENED));
            case ToolResult result -> Optional.of(new Step(result.callId(), Change.CLOSED));
            case ToolCancelled(String callId) -> Optional.of(new Step(callId, Change.CLOSED));
            case ToolInterrupted(String callId) -> Optional.of(new Step(callId, Change.CLOSED));
            // 拒绝也是终局：这次调用没跑过，也不会有下文，只是终局原因不同
            // （见 docs/decisions/bug-fix/2026-10-05-a-rejected-call-gets-its-own-event.md）
            case ToolRejected(String callId) -> Optional.of(new Step(callId, Change.CLOSED));
            case ToolApprovalRequested asked ->
                    Optional.of(new Step(asked.callId(), Change.APPROVAL_ASKED));
            case ToolApprovalResolved resolved -> Optional.of(new Step(resolved.callId(),
                    resolved.approved() ? Change.APPROVAL_GRANTED : Change.APPROVAL_REFUSED));

            // 其余事件与"这次调用走到哪一步"无关；穷尽列出，见类注释
            case AgentNoteDelivered ignored -> Optional.empty();
            case AssistantDelta ignored -> Optional.empty();
            case AssistantMessage ignored -> Optional.empty();
            case CheckpointCreated ignored -> Optional.empty();
            case ContextCompacted ignored -> Optional.empty();
            case LlmRetryScheduled ignored -> Optional.empty();
            case ModelChanged ignored -> Optional.empty();
            case PlatformInstruction ignored -> Optional.empty();
            case ReasoningDelta ignored -> Optional.empty();
            case SessionRewound ignored -> Optional.empty();
            case SessionStarted ignored -> Optional.empty();
            case SessionStateChanged ignored -> Optional.empty();
            case SessionSynced ignored -> Optional.empty();
            case TodoListUpdated ignored -> Optional.empty();
            case ToolResultsCleared ignored -> Optional.empty();
            case TurnTokensUsed ignored -> Optional.empty();
            case UserMessage ignored -> Optional.empty();
            case VerificationResult ignored -> Optional.empty();
            case WorkspaceChanges ignored -> Optional.empty();
        };
    }
}
