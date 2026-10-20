package com.codeloom.domain.session;

import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.ModelChanged;
import com.codeloom.domain.event.SessionSynced;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.PlatformInstruction;
import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.SessionStarted;
import com.codeloom.domain.event.SessionStateChanged;
import com.codeloom.domain.event.ToolApprovalRequested;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolCancelled;
import com.codeloom.domain.event.ToolInterrupted;
import com.codeloom.domain.event.TodoListUpdated;
import com.codeloom.domain.event.ToolRejected;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.ToolResultsCleared;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.event.VerificationResult;
import com.codeloom.domain.event.LlmRetryScheduled;
import com.codeloom.domain.event.WorkspaceChanges;

import java.util.Optional;

/**
 * 事件 → 它引起的会话状态变化。
 *
 * <p>会推着会话走的都在 {@link #after} 的 switch 里：工具请求与结果，以及等人批那条线。
 * 其余事件要么发生在推理期间，要么根本不影响状态机（checkpoint、模型切换）。
 *
 * <p>它与 {@link SessionState#canTransitionTo} 是同一条规则的两半：这里回答"哪条事件让它动"，
 * 那里回答"从哪个状态能到哪个状态"；分开就没有东西强制一致，不一致只会在
 * {@link Session#withState} 运行时抛异常。switch 不带 default，新增事件类型编译不过（同 {@code EventCodec}、{@code ContextAssembler}）。
 */
public final class TurnStates {

    private TurnStates() {
    }

    /**
     * 这条事件把会话推到哪个状态。
     *
     * @param event 待判定的事件
     * @return 要迁到的状态；这条事件不改变状态时为空
     */
    public static Optional<SessionState> after(PersistentEvent event) {
        return switch (event) {
            case ToolCallRequested ignored -> Optional.of(SessionState.EXECUTING_TOOL);
            case ToolApprovalRequested ignored -> Optional.of(SessionState.AWAITING_APPROVAL);
            // 答复到了先回 WAITING_USER；approve 接口随后 resume，走的正是已有的
            // WAITING_USER → THINKING
            case ToolApprovalResolved ignored -> Optional.of(SessionState.WAITING_USER);
            // 工具结果、取消、崩溃中断都回到推理阶段看结果
            case ToolResult ignored -> Optional.of(SessionState.THINKING);
            case ToolCancelled ignored -> Optional.of(SessionState.THINKING);
            case ToolInterrupted ignored -> Optional.of(SessionState.THINKING);

            // 以下都不改变状态。逐条列出而不是用 default，见类注释。
            case SessionStarted ignored -> Optional.empty();
            case SessionStateChanged ignored -> Optional.empty();
            case UserMessage ignored -> Optional.empty();
            // 留言只表示"有人捎了句话"，不改变会话跑到哪一步
            case AgentNoteDelivered ignored -> Optional.empty();
            case PlatformInstruction ignored -> Optional.empty();
            case AssistantMessage ignored -> Optional.empty();
            case CheckpointCreated ignored -> Optional.empty();
            // 记录"这一轮改了什么"，不是会话跑到哪一步
            case WorkspaceChanges ignored -> Optional.empty();
            // 重试不改变这一轮的状态：它还在等模型，只是又等了一次
            case LlmRetryScheduled ignored -> Optional.empty();
            // 压缩改的是投影，不是会话跑到哪一步
            case ContextCompacted ignored -> Optional.empty();
            case SessionRewound ignored -> Optional.empty();
            case VerificationResult ignored -> Optional.empty();
            // 清理旧结果只是省地方
            case ToolResultsCleared ignored -> Optional.empty();
            // 拒绝的收尾标记不改状态机：状态已被答复推到 WAITING_USER，它只告诉消费端
            // "这次调用不会再有下文"。见 ToolRejected
            case ToolRejected ignored -> Optional.empty();
            // 换模型改的是"下一轮用谁"，不是"现在跑到哪一步"
            case ModelChanged ignored -> Optional.empty();
            // 写清单是模型在一轮内部做的事，和读文件、跑命令同级，不改变会话在做什么
            case TodoListUpdated ignored -> Optional.empty();
            // 同步只把对方的代码拉进工作区，"这条会话还在等你说下一句"没变
            case SessionSynced ignored -> Optional.empty();
            // 用量只记录消耗，不改变会话跑到哪一步
            case TurnTokensUsed ignored -> Optional.empty();
        };
    }
}
