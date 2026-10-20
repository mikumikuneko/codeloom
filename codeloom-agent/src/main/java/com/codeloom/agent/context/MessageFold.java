package com.codeloom.agent.context;

import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.agent.tool.ToolNames;
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
import com.codeloom.domain.event.StoredEvent;
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
import com.codeloom.domain.user.UserId;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 把一批事件折成消息。{@link ContextAssembler.Projection} 持有它并负责跨轮推进与重建，
 * 这里只处理交给它的这批事件，并记住折叠所需的几张表。
 *
 * <p>需要状态，是因为 OpenAI 兼容格式要求一轮回复的正文和它的工具调用合并在同一条
 * assistant 消息里，而事件流把它们分开落库（一个 {@code AssistantMessage} 加 N 个
 * {@code ToolCallRequested}）。
 */
final class MessageFold {

    /** 时钟格式只到分钟：更细的粒度会让同一次交互里的每条消息都变成不同的前缀。 */
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final List<LlmMessage> messages;
    /** 正文已被清掉的调用 id，由 {@link ContextAssembler.Projection} 在折之前扫出。 */
    private final Set<String> clearedCallIds;
    /** 按 id 查用户名，只用于别人捎来的留言。 */
    private final Function<UserId, String> displayNameOf;
    /** 对话起始下标：前面的系统提示词不属于对话，回滚时不能一起清掉。 */
    private final int conversationStart;

    /**
     * checkpoint → 它落在消息列表的下标，用于回滚截断。
     *
     * <p>键用事件 seq：{@code turnIndex} 会撞（会话开始与第一轮挂着等人批时都是 0），
     * sha 也会撞（一轮没有改动时 {@code commit} 返回上一个 HEAD）；撞键会静默退到别的位置。
     *
     * <p>查不到键时退到 {@code conversationStart}：checkpoint 可能被压缩挡在水位线之前，
     * 此时无从知道边界；多退总好过留下一段描述已作废改动的摘要。
     */
    private final Map<Long, Integer> checkpointIndexes = new HashMap<>();

    private String pendingText;

    /**
     * 当前模型回复的思考过程；下一条 {@code AssistantMessage} 到达时覆盖，不随 {@link #flush()} 清除。
     *
     * <p>一次回复可能被拆成多条 assistant 消息（工具调用分批落库），而带 {@code tool_calls} 的消息
     * 必须携带当时的 {@code reasoning_content}，否则下一次请求返回 400。若某次回复既没有正文也没有
     * 思考，残留的思考会归因到下一次回复；接受这一点，因为漏字段会让整轮失败。
     */
    private String responseReasoning;

    /**
     * 产出 {@link #responseReasoning} 的模型，覆盖与保留时机同它。
     * 思考只对产生它的模型成立；是否回传由适配器决定（见 {@code OpenAiCompatibleClient#wireMessage}）。
     */
    private String responseModel;
    private final List<ToolCall> pendingCalls = new ArrayList<>();
    private boolean hasPending;

    /**
     * 请求了、但流里还没有任何结束事实的调用 id，按请求顺序。
     *
     * <p>工具执行期间这一轮可能被中断（回调抛异常、租约失效、进程被杀），事件流里只留下请求；
     * 而 API 要求每个 tool_call 都有配对的 tool 消息。{@link #closeOpenCalls()} 是发给模型前的兜底。
     */
    private final Set<String> openCallIds = new LinkedHashSet<>();

    /** 已请求审批、尚未答复的调用；兜底文案与真中断不同，因为它压根没有执行过。 */
    private final Set<String> awaitingApproval = new LinkedHashSet<>();

    /** {@code tool_call_id} → 它的 tool 消息下标；答复原地替换，位置紧跟带 {@code tool_calls} 的消息。 */
    private final Map<String, Integer> toolMessageAt = new HashMap<>();

    MessageFold(List<LlmMessage> messages, Set<String> clearedCallIds,
                Function<UserId, String> displayNameOf) {
        this.messages = messages;
        this.clearedCallIds = clearedCallIds;
        this.displayNameOf = displayNameOf;
        this.conversationStart = messages.size();
    }

    void accept(StoredEvent stored) {
        Event event = stored.event();
        // 穷尽 switch，没有 default：新增事件类型时编译失败，迫使实现决定它是否进入上下文。
        switch (event) {
            case ToolCallRequested requested -> {
                pendingCalls.add(new ToolCall(requested.callId(), requested.toolName(),
                        requested.argumentsJson()));
                openCallIds.add(requested.callId());
                hasPending = true;
            }
            case ToolResult result -> {
                flush();
                openCallIds.remove(result.callId());
                answerCall(result.callId(), clearedCallIds.contains(result.callId())
                        ? CLEARED_PLACEHOLDER
                        : describe(result));
            }
            case ToolResultsCleared ignored -> {
                // 正文替换已在投影对应的 ToolResult 时完成；此事件本身没有可投影内容。
            }
            // 两种“工具没跑完”的事实：注入它们而不是重新执行，让模型看着事实决定下一步。
            case ToolCancelled cancelled -> {
                flush();
                openCallIds.remove(cancelled.callId());
                answerCall(cancelled.callId(), "（这次调用被用户取消了，没有完成）");
            }
            case ToolInterrupted interrupted -> {
                flush();
                openCallIds.remove(interrupted.callId());
                answerCall(interrupted.callId(),
                        "（上次执行到这一步时进程重启，该调用没有完成。"
                                + "不要直接重试，先用 read_file 确认相关文件的当前状态。）");
            }
            // 审批请求本身不进上下文：ToolCallRequested 已经表达了“模型请求了这次调用”。
            case ToolApprovalRequested asked -> {
                // 只记下“这条在等人”，供兜底文案区分；事件本身不进上下文。
                awaitingApproval.add(asked.callId());
            }
            case ToolApprovalResolved resolved -> {
                // 答复必须进上下文，否则模型会一直等下去；但它是临时的，批准后真实结果会原地替换它，
                // 否则同一个 tool_call_id 会有两条 tool 消息（见 answerCall）。
                flush();
                openCallIds.remove(resolved.callId());
                awaitingApproval.remove(resolved.callId());
                answerCall(resolved.callId(), answerTo(resolved));
            }
            // 拒绝的收尾标记不进上下文：给模型的话已由审批答复给出，再进一条会重复。
            case ToolRejected ignored -> {
            }
            case UserMessage user -> {
                flush();
                // 时钟跟着用户消息走，并用事件落库时刻而非装配时的 now()，保证投影确定性。
                messages.add(LlmMessage.user(user.text() + "\n\n" + clockAt(stored.occurredAt())));
            }
            case TodoListUpdated updated -> {
                // 每次变化追加一份快照，旧快照留在历史里不变；最新一份在最后，空清单也追加。
                flush();
                messages.add(LlmMessage.user(todoSnapshot(updated, stored.occurredAt())));
            }
            case AgentNoteDelivered note -> {
                // 消息格式没有“第三方”角色，别人的留言只能以 user 身份出现；不标来源模型会当成自己用户的指示。
                flush();
                messages.add(LlmMessage.user(
                        "[来自 " + senderOf(displayNameOf, note.fromUserId()) + " 的 agent 的留言]\n"
                                + note.text()));
            }
            case PlatformInstruction instruction -> {
                // 平台注入的指令必须标明来源，模型才能区分用户发言与平台指令。
                flush();
                messages.add(LlmMessage.user("[平台指令] " + instruction.text()));
            }
            case AssistantMessage assistant -> {
                flush();
                pendingText = assistant.text();
                // 新的一次回复从这里开始；覆盖上一次回复的思考与来源模型。
                responseReasoning = assistant.reasoning();
                responseModel = assistant.model();
                hasPending = true;
            }
            // 以下事件不进上下文：要么是派生的（验证结论来自工具结果），要么只用于审计。
            case SessionStarted ignored -> {
            }
            case SessionStateChanged ignored -> {
            }
            case SessionRewound rewound -> {
                // 先 flush 再截，否则未交出的回复会留在切点之后，带着已作废轮次的内容。
                flush();
                truncateTo(rewound.toCheckpointSeq());
            }
            case CheckpointCreated checkpoint -> {
                // 先 flush，把这一轮最后的回复落进 messages，记下的下标才包含它；
                // checkpoint 之后同一轮还可能写 WorkspaceChanges / TurnTokensUsed / 状态变化，
                // 它们都不进上下文，提前 flush 不会拆开待合并的消息。
                flush();
                checkpointIndexes.put(stored.seq(), messages.size());
            }
            case ContextCompacted ignored -> {
                // 摘要和水位线由 ContextAssembler.Projection 在开始处预处理，此事件没有可投影内容。
            }
            // 我们这边的重试与模型无关，且每次重试都多一条，让前缀失效。
            case LlmRetryScheduled ignored -> {
            }
            // 模型自己知道改了什么；这条事件只在造出新提交且算得出项目内改动时出现，
            // 进入上下文只会让前缀无谓分叉。
            case WorkspaceChanges ignored -> {
            }
            case VerificationResult ignored -> {
            }
            // 用量只在正常收尾时落（失败收尾没有），且不改变模型该看到什么；
            // 进入上下文只会让前缀多出一段与决策无关的差异。
            case TurnTokensUsed ignored -> {
            }
            // 换模型不进上下文：模型需要的是“之前干了什么”，那已经在历史里。
            case ModelChanged ignored -> {
            }
            // 同步不进上下文：代码变了模型读文件就能看见，平台同步这件事与它的判断无关。
            case SessionSynced ignored -> {
            }
            // 易失事件不落库，正常不会到这里；真出现也忽略，完整正文已由 AssistantMessage 覆盖。
            case AssistantDelta ignored -> {
            }
            // 思考增量为展示用途，是否回传由 provider 适配层决定。
            case ReasoningDelta ignored -> {
            }
        }
    }

    /**
     * 把对话截断到某条 checkpoint 之前。代码与对话必须对齐，否则模型会针对已经从磁盘消失的状态推理。
     *
     * <p>按 seq 查边界；查不到就退到 {@code conversationStart}。截断后必须清理记录消息下标的表，
     * 否则后续答复会覆盖无关消息，或给已不在对话里的调用补结果。
     *
     * @param checkpointSeq 目标 checkpoint 的 seq；null 表示事件没有记录目标
     */
    private void truncateTo(Long checkpointSeq) {
        Integer boundary = checkpointSeq == null ? null : checkpointIndexes.get(checkpointSeq);
        int from = boundary == null ? conversationStart : boundary;
        messages.subList(from, messages.size()).clear();

        // toolMessageAt 与 checkpointIndexes 记录消息下标，按切点清理；openCallIds / awaitingApproval
        // 记录调用 id，只保留请求仍在切点之前的。漏清会让后续答复覆盖无关消息、按旧下标再次截断，
        // 或给已不在对话里的调用补一条没有 tool_calls 前置的 tool 消息。
        toolMessageAt.values().removeIf(at -> at >= from);
        checkpointIndexes.values().removeIf(at -> at >= from);
        openCallIds.removeIf(callId -> !requestedWithin(callId, from));
        awaitingApproval.removeIf(callId -> !requestedWithin(callId, from));
    }

    /**
     * 这次调用的请求是否还留在切点之前。
     *
     * @param callId 调用 id
     * @param from   切点下标
     * @return 请求仍在切点之前时为 true
     */
    private boolean requestedWithin(String callId, int from) {
        for (LlmMessage message : messages.subList(0, from)) {
            for (ToolCall call : message.toolCalls()) {
                if (call.id().equals(callId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 把压缩摘要作为一条普通 user 消息注入。
     *
     * <p>位置必须落在 {@code conversationStart} 之后，回滚到它之前时才能一起截掉；
     * 放进系统提示词会永远躲过截断，让模型面对描述已消失改动的摘要。
     *
     * @param summary 摘要正文
     */
    void acceptSummary(String summary) {
        messages.add(LlmMessage.user(SUMMARY_HEADER + summary));
    }

    /**
     * 收尾：给请求了却没有结果的调用补一条合成结果，满足 API 的配对要求。
     *
     * <p>正常事件流不会走到这里，因此不影响前缀缓存。补成结果而不是删掉调用：
     * 模型确实请求过它，工具甚至可能已经改了文件。
     */
    void closeOpenCalls() {
        for (String callId : openCallIds) {
            // 等人批的调用没有执行过，不能套用“先确认文件状态”的中断文案。
            answerCall(callId, awaitingApproval.contains(callId)
                    ? "（这次调用还在等你批准，没有结果。）"
                    : "（这次调用没有留下结果 —— 那一轮在它执行期间中断了。"
                            + "不要直接重试，先确认相关文件的当前状态。）");
        }
        openCallIds.clear();
        awaitingApproval.clear();
    }

    /**
     * 回答一次工具调用：一个 {@code tool_call_id} 只保留一条 {@code role="tool"} 消息。
     *
     * <p>审批答复与真实结果可能先后到达；后来的原地替换先前那条，位置紧跟带 {@code tool_calls}
     * 的 assistant 消息。追加两条会让 OpenAi 兼容格式报 400。见
     * {@code docs/decisions/bug-fix/2026-09-30-one-answer-per-tool-call.md}。
     *
     * @param callId  被回答的调用 id
     * @param content 给模型看的结果
     */
    private void answerCall(String callId, String content) {
        LlmMessage message = LlmMessage.toolResult(callId, content);
        Integer at = toolMessageAt.get(callId);
        if (at == null) {
            toolMessageAt.put(callId, messages.size());
            messages.add(message);
            return;
        }
        messages.set(at, message);
    }

    /** 把攒着的 assistant 消息交出去；有工具调用时正文可以为空。 */
    void flush() {
        if (!hasPending) {
            return;
        }
        String text = pendingText == null ? "" : pendingText;
        // responseReasoning 与 responseModel 不在这里清：同一回复的下一个批次还要用（见字段注释）。
        messages.add(pendingCalls.isEmpty()
                ? LlmMessage.assistant(text, responseModel, responseReasoning)
                : LlmMessage.assistantWithToolCalls(text, List.copyOf(pendingCalls),
                        responseModel, responseReasoning));
        pendingText = null;
        pendingCalls.clear();
        hasPending = false;
    }

    private static String describe(ToolResult result) {
        StringBuilder out = new StringBuilder();
        out.append(result.success() ? "成功" : "失败");
        if (result.exitCode() != null) {
            out.append("（退出码 ").append(result.exitCode()).append("）");
        }
        out.append('\n').append(result.output());
        if (result.truncated()) {
            // 截断必须说明，否则模型会把这段当成全部输出。
            out.append("\n...（输出过长已截断，如需更多内容请缩小范围重试）");
        }
        return out.toString();
    }

    /** 摘要消息开头；不点明的话模型会把摘要当成一条新指令执行。 */
    private static final String SUMMARY_HEADER = "[此前对话的摘要 —— 更早的消息已被它替换]\n";

    /** 正文被清掉后留给模型的占位；必须说明调用仍在、只是内容不看了，否则模型会重试。 */
    private static final String CLEARED_PLACEHOLDER =
            "（这次调用的输出已经为了省上下文被清理掉了。如果还需要它的内容，重新执行一次）";

    /**
     * 一次审批答复给模型看的话。拒绝分两种：用户留了指示就附上；没留就明确要求停下等待，
     * 因为此时猜一个方向很可能正是用户刚叫停的那一个。
     *
     * @param resolved 审批答复；{@code reason} 为空表示用户没说下一步怎么办
     * @return 给模型的答复文本
     */
    private static String answerTo(ToolApprovalResolved resolved) {
        if (resolved.approved()) {
            return "（用户批准了这次调用，可以执行）";
        }
        String reason = resolved.reason();
        if (reason == null || reason.isBlank()) {
            return "（用户拒绝了这次调用，它没有执行。**停下**，等用户告诉你接下来怎么办）";
        }
        return "（用户拒绝了这次调用：" + reason + "。请换一种做法，不要原样重试）";
    }

    /**
     * 留言发起者的称呼。查不到时用“协作者”泛指，不能编名字。
     *
     * @param displayNameOf 查名函数
     * @param id            发起者 id
     * @return 显示名或兜底称呼
     */
    private static String senderOf(Function<UserId, String> displayNameOf, UserId id) {
        String name = displayNameOf.apply(id);
        return name == null || name.isBlank() ? "协作者" : name;
    }

    /**
     * 用户消息后面的时钟，取事件落库时刻而非装配时刻，保证重放结果逐字符相同。
     *
     * @param at 用户消息的落库时间
     * @return 平台指令行
     */
    private static String clockAt(Instant at) {
        ZoneId zone = ZoneId.systemDefault();
        return "[平台指令] 现在是 " + LocalDateTime.ofInstant(at, zone).format(CLOCK_FORMAT)
                + "（" + zone.getId() + "）。用户说「今天」「最近」时按这个时区算。";
    }

    /**
     * 一份清单快照，带自身时刻和“以最后一份为准”，让模型在旧快照仍留在历史里时判断当前版本。
     *
     * @param updated 清单事件
     * @param at      事件落库时间
     * @return 快照文本
     */
    private static String todoSnapshot(TodoListUpdated updated, Instant at) {
        ZoneId zone = ZoneId.systemDefault();
        String time = LocalDateTime.ofInstant(at, zone).format(CLOCK_FORMAT);
        String header = "[当前任务清单 · " + time + " —— 你自己列的，用 "
                + ToolNames.TODO_WRITE + " 更新它；以最后一份为准]\n";
        return updated.items().isEmpty() ? header + "（已经清空）" : header + renderTodos(updated.items());
    }

    /**
     * 把清单写成几行字，状态沿用工具参数里的 pending / in_progress / completed。
     *
     * @param items 清单项
     * @return 文本快照
     */
    private static String renderTodos(List<TodoListUpdated.Item> items) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            TodoListUpdated.Item item = items.get(index);
            text.append(index + 1).append(". [").append(item.state().name().toLowerCase()).append("] ")
                    .append(item.content()).append('\n');
        }
        return text.toString();
    }
}
