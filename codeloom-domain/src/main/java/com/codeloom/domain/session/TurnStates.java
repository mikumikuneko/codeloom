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
 * <p>只有「工具在跑」和「模型在推理」这两件事是能从事件上看出来的，其余事件要么发生在
 * 推理期间（模型说话、平台注入指令），要么根本不影响状态机（checkpoint、模型切换）。
 *
 * <p>switch 没有 {@code default}：新增事件类型时这里编译不过，逼着人回答
 * 「它要不要改状态」。这和 {@code EventCodec}、{@code ContextAssembler} 是同一套做法。
 *
 * <p>它和 {@link SessionState#canTransitionTo} 是**同一条规则的两半**：这里回答
 * "哪条事件让它动"，那里回答"从哪个状态能到哪个状态"。两半都住在域里，就是为了一起读 ——
 * 分成两个模块的话没有任何东西强制它们一致，而不一致的表现是 `Session.withState`
 * 在跑到一半时抛，不是编译不过。
 */
public final class TurnStates {

    private TurnStates() {
    }

    /** @return 要迁到的状态；这条事件不改变状态时返回空 */
    public static Optional<SessionState> after(PersistentEvent event) {
        return switch (event) {
            // 模型要动手了 → 工具执行阶段
            case ToolCallRequested ignored -> Optional.of(SessionState.EXECUTING_TOOL);
            // 要人来批 → 挂起等答复
            case ToolApprovalRequested ignored -> Optional.of(SessionState.AWAITING_APPROVAL);
            // 答复到了 → 这一轮收尾。approve 那个接口随后会 resume，
            // 走的正是已有的 WAITING_USER → THINKING
            case ToolApprovalResolved ignored -> Optional.of(SessionState.WAITING_USER);
            // 工具回来了（成、败、被取消、崩溃中断）→ 回到推理阶段看结果
            case ToolResult ignored -> Optional.of(SessionState.THINKING);
            case ToolCancelled ignored -> Optional.of(SessionState.THINKING);
            case ToolInterrupted ignored -> Optional.of(SessionState.THINKING);

            // 下面这些都不改变状态。逐条列出来而不是用 default，见类注释。
            case SessionStarted ignored -> Optional.empty();
            case SessionStateChanged ignored -> Optional.empty();
            case UserMessage ignored -> Optional.empty();
            // 一条留言只是"有人捎了句话"，不改变会话跑到哪一步了
            case AgentNoteDelivered ignored -> Optional.empty();
            case PlatformInstruction ignored -> Optional.empty();
            case AssistantMessage ignored -> Optional.empty();
            case CheckpointCreated ignored -> Optional.empty();
            // 记了一笔"这一轮改了什么"—— 那是一份记录，不是会话跑到哪一步了
            case WorkspaceChanges ignored -> Optional.empty();
            // 重试**不改变这一轮处在哪个状态**：它还在等模型，只是又等了一次
            case LlmRetryScheduled ignored -> Optional.empty();
            // 压缩改的是投影，不是会话跑到哪一步了
            case ContextCompacted ignored -> Optional.empty();
            case SessionRewound ignored -> Optional.empty();
            case VerificationResult ignored -> Optional.empty();
            // 清理旧结果只是省地方，会话该在哪一步还在哪一步
            case ToolResultsCleared ignored -> Optional.empty();
            // 拒绝的**收尾标记**不改状态机：状态已经被上面那条答复推到 WAITING_USER 了，
            // 它只是告诉消费端"这次调用不会再有下文"。见 ToolRejected
            case ToolRejected ignored -> Optional.empty();
            // 换模型改的是"下一轮用谁"，不是"现在跑到哪一步"
            case ModelChanged ignored -> Optional.empty();
            // 写清单是模型在一轮**内部**做的一件事（和读文件、跑命令同级）：
            // 它不改变会话在做什么，只是把"要做到哪一步"记下来
            case TodoListUpdated ignored -> Optional.empty();
            // 同步不改变会话**在做什么** —— 它只是把对方的代码拉进了工作区，
            // 而"这条会话还在等你说下一句"这件事没变
            case SessionSynced ignored -> Optional.empty();
            // 用量结算只是一笔账，不改状态机
            case TurnTokensUsed ignored -> Optional.empty();
        };
    }
}
