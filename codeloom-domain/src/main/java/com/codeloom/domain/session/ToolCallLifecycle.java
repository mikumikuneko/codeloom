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
 * 「一次工具调用走到了哪一步」—— 从事件里读出来，**全仓只有这一处**。
 *
 * <h2>为什么要有它</h2>
 * 好几个地方都要问"这次调用结束了没有"，而它们问的**不是同一个问题**：投影要判
 * "尾部那次批准了但还没补跑"，崩溃恢复要判"哪些是执行到一半"，压缩要判"哪些结果能清"。
 * 问题不同、答案不同，但**"哪些事件算一次调用的终局"是同一件事**。
 *
 * <p>这件事从前在两个地方各列了一遍（一处 {@code instanceof} 链、一处带 {@code default}
 * 的 switch），而**漏掉一种终局事件不会报错**：恢复会给一条早就结束的调用再补一条
 * "进程重启、没有完成"。
 *
 * <h2>它只回答"是哪一步"，不回答"那一步意味着什么"</h2>
 * 同一件事对不同的人意味着相反的东西，这是**刻意**的：{@code ToolApprovalRequested}
 * 在投影那边算"还没收尾"（那条 {@code tool_calls} 还等着配对），在崩溃恢复那边算
 * "不是执行到一半"（那条命令压根还没跑）。所以这里只给步名，各自去判。
 *
 * <p>实现是**一个穷尽 switch 表达式、不带 default**：新增一种事件，这里编译不过 ——
 * 而那正是要的（新事件多半得先答一句"它落在调用生命线的哪一步"）。
 */
public final class ToolCallLifecycle {

    private ToolCallLifecycle() {
    }

    /** 这条事件在调用生命线上的一步。 */
    private enum Change {
        /** 请求了。 */
        OPENED,
        /** 有结局了：跑完 / 被取消 / 被中断 / 被拒绝 —— **终局是这四种**。 */
        CLOSED,
        /** 挂起等人批。 */
        APPROVAL_ASKED,
        /** 批了（那条调用于是又欠一次执行）。 */
        APPROVAL_GRANTED,
        /** 拒了（它从此没有下文）。 */
        APPROVAL_REFUSED,
    }

    private record Step(String callId, Change change) {
    }

    /** 这次调用被请求了 —— 生命线的起点。 */
    public static Optional<String> openedBy(Event event) {
        return callIdOf(event, Change.OPENED);
    }

    /** 这次调用**有结局了**：跑完、被取消、被中断、被拒绝。 */
    public static Optional<String> closedBy(Event event) {
        return callIdOf(event, Change.CLOSED);
    }

    /** 这次调用挂起等人批。 */
    public static Optional<String> approvalAskedBy(Event event) {
        return callIdOf(event, Change.APPROVAL_ASKED);
    }

    /** 这次调用被批准了。 */
    public static Optional<String> approvalGrantedBy(Event event) {
        return callIdOf(event, Change.APPROVAL_GRANTED);
    }

    /** 这次调用被拒绝了。 */
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
            // 拒绝 = 这次调用**到此为止**：它没跑过，而且不会再有下文。
            // 和上面那三种一样是"终局"，只是终局的原因不同
            case ToolRejected(String callId) -> Optional.of(new Step(callId, Change.CLOSED));
            case ToolApprovalRequested asked ->
                    Optional.of(new Step(asked.callId(), Change.APPROVAL_ASKED));
            case ToolApprovalResolved resolved -> Optional.of(new Step(resolved.callId(),
                    resolved.approved() ? Change.APPROVAL_GRANTED : Change.APPROVAL_REFUSED));

            // 剩下的和"这次调用走到哪一步"没关系。**穷尽列出来**，见类注释
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
