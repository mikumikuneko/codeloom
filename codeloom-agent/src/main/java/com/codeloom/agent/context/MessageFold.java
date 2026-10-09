package com.codeloom.agent.context;

import com.codeloom.agent.llm.ChatMessage;
import com.codeloom.agent.llm.ToolCall;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * **一次**把事件折成消息 —— {@link ContextAssembler.Projection} 持有它。
 *
 * <p>分工：那边管"跨轮往前推进、什么时候推倒重来"，这里只管**把交给它的这批事件变成消息**，
 * 顺带记住折的过程中要用的那几张表（checkpoint 落在哪、每个调用的答复在第几条）。
 *
 * <p>需要一点状态，是因为 OpenAI 的消息格式要求「一轮回复的正文和它的工具调用
 * 必须合并在同一条 assistant 消息里」。而我们是分开落库的
 * （一个 {@code AssistantMessage} + N 个 {@code ToolCallRequested}），
 * 所以投影时要把它们重新合成一条。
 */
final class MessageFold {

    private final List<ChatMessage> messages;
    /** 正文已经被清掉的那些调用 id（由 {@link ContextAssembler.Projection} 在折之前扫出来）。 */
    private final Set<String> clearedCallIds;
    /** 见 {@link ContextAssembler} 的那个查名字的办法 —— 它只用在一处（别人捎来的留言）。 */
    private final Function<UserId, String> displayNameOf;
    /** 对话从第几条开始 —— 前面那条系统提示词不属于对话，回滚时不能一起清掉。 */
    private final int conversationStart;

    /**
     * 每个 checkpoint 落在消息列表的**哪个下标**上 —— 回滚靠它把对话截断到准确的位置。
     *
     * <h2>为什么索引键是序号</h2>
     * 因为另外两种键都会撞车。{@code turnIndex} 会：会话开始那条（"一次都还没跑完" = 0）
     * 和第一轮**挂着等人批**时那条**都是 0** —— 后者属于第一轮，可它写下来的时候
     * 那一轮还没跑完。sha 也会：一轮什么都没改时 {@code commit} 返回的还是上一个 HEAD。
     * 撞车的后果不是"查不到"，而是"查到别的那条" —— 于是回滚会退过头，
     * 而这种错**看起来是对的**（退回了某个真实存在的位置）。
     *
     * <p>序号（事件在流里的 seq）唯一，所以这里也用 {@code put} 就够了。
     *
     * <h2>查不到的时候怎么办</h2>
     * 有一种真实情况：那条 checkpoint 被压缩挡在**水位线之前**了（见
     * {@link ContextAssembler.Projection} 的 {@code watermark}），于是那一轮我们压根没遍历到。
     * 那时候只能退到 {@code conversationStart}（连摘要一起清掉）——
     * 因为我们无从知道边界在哪，而"退得比该退的多"总好过"留一段描述着已经作废的改动的摘要"。
     */
    private final Map<Long, Integer> checkpointIndexes = new HashMap<>();

    private String pendingText;

    /**
     * **当前这次模型回复**的思考过程。它在**下一条 {@code AssistantMessage} 到达时被覆盖**
     *（那是新的一次回复开始的唯一标志），**不跟着 {@link #flush()} 清** —— 理由在下面。
     *
     * <p>一次回复可能被投影成**多条** assistant 消息（模型一次发了两个工具调用、执行时按批切开，
     * {@code ToolCallRequested} 是分批落库的），而带 {@code tool_calls} 的消息**必须**带上当时的
     * {@code reasoning_content}（见 {@link com.codeloom.agent.llm.ChatMessage#assistant}）——
     * 在第一条 flush 时清掉的话，第二条发出去就是 400：
     * 「The `reasoning_content` in the thinking mode must be passed back to the API.」
     *
     * <p>残留一种：某次回复**既没有正文也没有思考**却调了工具，就没有新消息来覆盖它，
     * 这段思考会跟着发出去（一次不准确的归因）。接受 —— 错的归因只让模型多想一点，
     * 漏掉字段是整轮直接失败。
     */
    private String responseReasoning;

    /**
     * 上面那段思考是**哪个模型**产的。
     *
     * <p>它跟着 {@code responseReasoning} 走：同一个"上一次回复"的属性，
     * 覆盖的时机、保留的时机都完全一样（连"下一批还要用它"那条也一样）。
     *
     * <p>为什么要带着它 —— **思考只对产生它的模型成立**。换了模型之后那份思考
     * 不该再发给新模型：它不是新模型产的，而 wire 上那个字段（{@code reasoning_content}）
     * 在新模型那边意味着什么，没人能替它回答。判定在 adapter 那一层做
     *（见 {@code OpenAiCompatibleClient#wireMessage}），这里只负责**把来源带过去**。
     */
    private String responseModel;
    private final List<ToolCall> pendingCalls = new ArrayList<>();
    private boolean hasPending;

    /**
     * 请求了、但整个流里都没有任何结束事实的调用 id（按请求顺序排）。
     *
     * <p>为什么需要它：{@code ToolCallRequested} 落库之后、对应的
     * {@code ToolResult} 落库之前，中间隔着**真正把这个工具跑完**的那段时间。
     * 那段时间里这一轮如果被中断（回调抛异常、fencing token 失效、进程被杀），
     * 事件流里就只留下「请求了」，永远等不到「结果」。
     *
     * <p>而 API 要求每个 tool_call 都配一条 tool 消息 —— 不配就是 400。所以这里是
     * 发给模型之前的**最后一道兜底**，见 {@link #closeOpenCalls()}。
     */
    private final Set<String> openCallIds = new LinkedHashSet<>();

    /**
     * 每个 {@code tool_call_id} 的答复已经落在消息列表的哪个下标上，见 {@link #answerCall}。
     *
     * <p>存**下标**而不是消息本身，是因为要的是"原地换掉" —— 位置必须留在
     * 那条带 {@code tool_calls} 的 assistant 消息后面。
     */
    private final Map<String, Integer> toolMessageAt = new HashMap<>();

    MessageFold(List<ChatMessage> messages, Set<String> clearedCallIds,
                Function<UserId, String> displayNameOf) {
        this.messages = messages;
        this.clearedCallIds = clearedCallIds;
        this.displayNameOf = displayNameOf;
        this.conversationStart = messages.size();
    }

    void accept(StoredEvent stored) {
        Event event = stored.event();
        // 穷尽 switch，没有 default —— 新增事件类型时这里会编译不过，
        // 逼着我们决定它该不该进上下文。这正是 sealed 的用处。
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
                // 正文的替换已经在投影上面那条 ToolResult 时做掉了
                // （集合是遍历之前扫出来的），这条事件本身没有可投影的东西
            }
            // 这两种是"工具没跑完"的事实。**注入它们而不是重新执行**，
            // 是崩溃恢复与用户取消的正确做法 —— 让模型看着事实自己决定下一步。
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
            // 审批请求本身**不进上下文**："模型请求了这次调用"已经由上面的
            // ToolCallRequested 表达了，再来一条只会让模型以为有两回事
            case ToolApprovalRequested ignored -> {
            }
            case ToolApprovalResolved resolved -> {
                // 答复要进 —— 模型必须知道那个调用是被批了还是被拒了，
                // 否则它会一直等下去。
                //
                // 但**它是临时的**：批准之后那次调用会真的跑，跑完的
                // ToolResult 会把这一条**原地换掉**（见 answerCall）。
                // 两条都留着的话，消息列表里就有两条 role="tool"
                // 回答同一个 tool_call_id —— 下一次请求直接 400。
                flush();
                openCallIds.remove(resolved.callId());
                answerCall(resolved.callId(), answerTo(resolved));
            }
            // 拒绝的**收尾标记**。**不进上下文** —— 模型要听的那句话已经由上面那条答复
            // 说了（"用户拒绝了这次调用…停下等指示"），这里再进一条，
            // 同一件事就会在消息列表里出现两遍。
            // 它存在的意义在消费端：判"这次调用结束了没有"靠的是有没有收尾记录
            case ToolRejected ignored -> {
            }
            case UserMessage user -> {
                flush();
                messages.add(ChatMessage.user(user.text()));
            }
            case AgentNoteDelivered note -> {
                // **来源必须标出来**。消息格式里没有"第三方"这个角色，所以别人的留言
                // 只能以 user 的身份出现 —— 而模型默认会把 user 的话当成"我的用户在说"。
                // 不标的话，它会照着**别人的请求**去改自己这边的代码，
                // 而那很可能违背它自己用户的意图
                flush();
                messages.add(ChatMessage.user(
                        "[来自 " + senderOf(displayNameOf, note.fromUserId()) + " 的 agent 的留言]\n"
                                + note.text()));
            }
            case PlatformInstruction instruction -> {
                // 平台注入的指令（自动验证失败之类）。**必须标明来源** ——
                // 模型要能分清"用户说的"和"平台说的"，而且它在事件流里
                // 也是独立类型，审计时不会跟用户发言混起来
                flush();
                messages.add(ChatMessage.user("[平台指令] " + instruction.text()));
            }
            case AssistantMessage assistant -> {
                flush();
                pendingText = assistant.text();
                // **新的一次回复从这里开始** —— 这是它唯一的标志。
                // 注意这一行是"覆盖"而不是"追加"：它同时把上一次回复的思考换掉
                responseReasoning = assistant.reasoning();
                // 来源模型和思考同生共死：它俩是同一个"上一次回复"的两个属性。
                // 换模型之后，新回复带的思考记的就是新模型 —— 于是老的那几份
                // 自然不会再被当成"新模型说的"发出去
                responseModel = assistant.model();
                hasPending = true;
            }
            // 以下都不进上下文 —— 它们要么是派生的（验证结论来自工具结果），
            // 要么只是给审计看的（checkpoint、状态变化）
            case SessionStarted ignored -> {
            }
            case SessionStateChanged ignored -> {
            }
            case SessionRewound rewound -> {
                // **先 flush 再截**：那条还没交出去的回复属于被退掉的那一轮，得让它一起被砍掉；
                // 反过来先截再 flush，它会被留在切点之后 —— 一条来自已作废轮次的消息
                flush();
                truncateTo(rewound.toCheckpointSeq());
            }
            case CheckpointCreated checkpoint -> {
                // 先 flush：这一轮最后那条回复此刻还在 pending 里，不落进 messages 的话
                // 记下的下标会少一条 —— 回滚**正好退到那条之前**，于是每次回滚都少留一段。
                //
                // **提前 flush 不会把该合并的消息拆开**，因为顺序：CheckpointCreated 永远是一轮的
                // **最后**一条，它后面第一条进上下文的事件一定是下一轮的 UserMessage（那条本来就会先 flush）。
                // 前缀因此逐字节不变（缓存的前提，见类注释）。
                //
                // 哪天有种事件插在这两者之间，这条推理就塌了 —— 那时得回来重想。
                flush();
                // 用 put 就够：序号唯一，不会撞车
                checkpointIndexes.put(stored.seq(), messages.size());
            }
            case ContextCompacted ignored -> {
                // 摘要、以及"从哪个序号开始投影"，都在 ContextAssembler.Projection
                // 开头那一下预先处理掉了 —— 这条事件本身没有可投影的东西
            }
            // 我们这边在重试，和模型无关 —— 它知道了只会以为自己该说点什么。
            // 而且它每重试一次就多一条，进去还会让前缀缓存失效
            case LlmRetryScheduled ignored -> {
            }
            // 这一轮改了哪些文件**不进上下文**：模型自己做过什么它清楚，而这条每轮都有 ——
            // 进去会让每一轮的消息前缀都不一样，prompt 缓存就此作废（见 WorkspaceChanges）
            case WorkspaceChanges ignored -> {
            }
            case VerificationResult ignored -> {
            }
            // 用量结算不进上下文：它改变不了模型该看到什么，而它**每一轮都会出现** ——
            // 一旦进去，每轮的消息前缀都不一样，prompt 缓存就此作废
            case TurnTokensUsed ignored -> {
            }
            // 换模型**不进上下文**。Claude Code 走的是另一条路：它的系统提示词里写着
            // "你由模型 X 驱动"，而那段提示词**每次请求重写**，于是模型自己就知道换了；
            // 另发的那条"换了模型"是给**用户**看的系统消息，不会进 API。
            // 我们没有那个前提（系统提示词是用户自己配的、不含模型身份），所以什么都不用做：
            // 模型需要的是"之前干了什么"，那在历史里，和"之前是谁干的"无关
            case ModelChanged ignored -> {
            }
            // 同步也不进上下文。模型需要知道的是「**代码变了**」——
            // 而那件事它读文件就看得见。把"平台做了一次同步"塞进来，
            // 只会让每轮的消息前缀多一段和它的判断无关的东西
            case SessionSynced ignored -> {
            }
            // 清单**不在这里进上下文**：它在装配的最后单独注入一条
            // （见 ContextAssembler.Projection），因为要的是"最后一份、摆在末尾"，
            // 而不是"每一份都留在历史里"
            case TodoListUpdated ignored -> {
            }
            // 易失事件不落库，正常不会出现在这里；真出现了也忽略，
            // 因为完整正文已经由 AssistantMessage 覆盖了
            case AssistantDelta ignored -> {
            }
            // 思考过程和正文同理。而且它**本来就一个字都不进上下文** ——
            // 那是展示用途，回不回传给模型由 provider 适配层决定（各家要求相反）
            case ReasoningDelta ignored -> {
            }
        }
    }

    /**
     * 把对话截断到某条 checkpoint 之前 —— **回滚是唯一一处回头砍已经投出去的消息的地方**。
     *
     * <p>代码和对话绑死在对齐的位置上（写在 {@code SessionRewound} 上的约定）：不截的话，
     * 模型会对着一个**已经从磁盘上消失的现状**继续推理，那种错很难查。
     *
     * <p>截到哪儿按**序号**查；查不到就退到 {@code conversationStart}（连摘要一起清）——
     * 为什么按序号而不按 sha、两种"查不到"为什么走同一个保守分支，都写在
     * {@link #checkpointIndexes} 上，这里不重复。
     *
     * @param checkpointSeq 退到的那条 checkpoint 在流里的序号；{@code null} = 那条事件没记下退到哪儿
     */
    private void truncateTo(Long checkpointSeq) {
        Integer boundary = checkpointSeq == null ? null : checkpointIndexes.get(checkpointSeq);
        messages.subList(boundary == null ? conversationStart : boundary, messages.size()).clear();
    }

    /**
     * 把压缩摘要作为**一条普通 user 消息**注入。
     *
     * <p>**位置是决定性的**：它必须落在 {@code conversationStart} 之后 ——
     * {@link com.codeloom.domain.event.SessionRewound} 退到它前面时才能把它一起截掉
     *（退到它之后时留着，那时它描述的对话还都在）。放进系统提示词那一侧就没有这个余地：
     * 它永远躲过截断，回滚之后模型会揣着一段描述着**已经从磁盘上消失的改动**的摘要说话 ——
     * 那正是这个类最想避免的失效模式。
     *
     * <p>附带的理由：摘要讲的是"之前聊过什么"，那本来就属于**对话**，不是"这次要遵守的设定"。
     */
    void acceptSummary(String summary) {
        messages.add(ChatMessage.user(SUMMARY_HEADER + summary));
    }

    /**
     * 收尾：给「请求了、却没有任何结果」的调用补一条合成结果。
     *
     * <p>它对应一条真实路径：工具执行途中这一轮被中断（回调抛异常、fencing token 失效、
     * 进程被杀），{@code ToolCallRequested} 已落库而结果永远不来 —— 直接投影出去就违反了
     * API 的配对要求。正常的事件流走不到这里（{@code openCallIds} 为空），
     * 所以**对前缀缓存没有影响**。
     *
     * <p>补成「结果」而不是把这个调用删掉：删掉等于对模型说"你没请求过它"，
     * 而它确实请求过、那个工具甚至可能已经改了文件。
     */
    void closeOpenCalls() {
        for (String callId : openCallIds) {
            answerCall(callId,
                    "（这次调用没有留下结果 —— 那一轮在它执行期间中断了。"
                            + "不要直接重试，先确认相关文件的当前状态。）");
        }
        openCallIds.clear();
    }

    /**
     * 回答一次工具调用 —— **一个 {@code tool_call_id} 只留一条 {@code role="tool"} 消息**。
     *
     * <p>一次调用的答复可能来两次（审批那条要先充当"你批了/你拒了"，否则模型一直等；
     * 然后才是真正跑完的 {@code ToolResult}），两条都 append 就是**两条 tool 消息回答同一个
     * tool_call_id**，而 OpenAI 兼容格式要求一对一 —— 下一次请求当场 400：
     * 「Messages with role 'tool' must be a response to a preceding message with 'tool_calls'」。
     *
     * <p>所以后来的**原地替换**先前那条，位置不动 —— 它必须紧跟在那条带 {@code tool_calls}
     * 的 assistant 消息后面，挪了反而配不上对。
     */
    private void answerCall(String callId, String content) {
        ChatMessage message = ChatMessage.toolResult(callId, content);
        Integer at = toolMessageAt.get(callId);
        if (at == null) {
            toolMessageAt.put(callId, messages.size());
            messages.add(message);
            return;
        }
        messages.set(at, message);
    }

    /** 把攒着的 assistant 消息交出去。有工具调用时正文可以为空。 */
    void flush() {
        if (!hasPending) {
            return;
        }
        String text = pendingText == null ? "" : pendingText;
        // `responseReasoning` **不在这里清** —— 见它的字段注释：
        // 同一回复的下一个批次还要用它，清了的话那一条就是一个 400。
        // `responseModel` 同理：它和那份思考是一体的
        messages.add(pendingCalls.isEmpty()
                ? ChatMessage.assistant(text, responseModel, responseReasoning)
                : ChatMessage.assistantWithToolCalls(text, List.copyOf(pendingCalls),
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
            // 截断了必须说出来，否则模型会以为输出就这么多
            out.append("\n...（输出过长已截断，如需更多内容请缩小范围重试）");
        }
        return out.toString();
    }

    /**
     * 摘要消息的开头。模型得能一眼看出"这不是用户刚说的，而是此前对话的浓缩"——
     * 不点明的话，它会把摘要当成一条新指令去执行。
     */
    private static final String SUMMARY_HEADER = "[此前对话的摘要 —— 更早的消息已被它替换]\n";

    /**
     * 正文被清掉之后，那条结果留给模型看的东西。
     *
     * <p>**必须说清"它还在、只是内容不看了"**，而不是让它消失 —— 消失的话，
     * 模型会看到一次"没有结果的调用"，然后理直气壮地把它重试一遍。
     */
    private static final String CLEARED_PLACEHOLDER =
            "（这次调用的输出已经为了省上下文被清理掉了。如果还需要它的内容，重新执行一次）";

    /**
     * 一次审批答复，给模型看的那句话。
     *
     * <h2>拒绝分两种，而且是**两句话**</h2>
     * Claude Code 就是这么分的，两种各有一条固定的说法：用户留了话，就把话附上、
     * 让他照着走；**没留话**，就明说"停下来等用户"。
     *
     * <p>为什么没留话时不能只说一句"换个做法"：那种情况下模型的处境是——用户把这件事
     * 叫停了，而他还没说下一步怎么办。这时候自己猜一个做法往下跑，多半是白烧一轮；
     * 而且他叫停的**往往正是这个方向**。Claude Code 在这一支上更狠：直接把整个回合中止掉。
     *
     * <p>（{@code reason} 为 null 时不能把 null 直接拼进去：模型会读到**字面量 null**。）
     *
     * @param resolved 那次答复。{@code reason} 为空 = 用户只说了"不行"，没说怎么办
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
     * 留言发起者叫什么。
     *
     * <p>查不到（或者没接这一层）时用一句**泛指**的话：那句话是要给模型看的，
     * 它只需要知道"这不是我自己的用户在说话"；编一个名字出来，会让它照着错的人去理解。
     */
    private static String senderOf(Function<UserId, String> displayNameOf, UserId id) {
        String name = displayNameOf.apply(id);
        return name == null || name.isBlank() ? "协作者" : name;
    }
}
