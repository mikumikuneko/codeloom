package com.codeloom.realtime.event;

import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.EventType;
import com.codeloom.domain.event.ModelChanged;
import com.codeloom.domain.event.PlatformInstruction;
import com.codeloom.domain.event.ReasoningDelta;
import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.SessionSynced;
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
import com.codeloom.domain.event.LlmRetryScheduled;
import com.codeloom.domain.event.WorkspaceChanges;
import com.codeloom.domain.event.VerificationResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * {@link Event} 与 {@link EventType} 加 JSON payload 之间的双向映射。
 *
 * <p>两个方向的 switch 都穷尽且没有 {@code default}：新增事件类型时这里编译不过。
 * 不用 Jackson 多态注解，是因为注解得加在 domain 的 sealed 接口上，会让零依赖的领域
 * 模型依赖序列化框架；而且手写映射的字段名就是 record 组件名，不受全局 Jackson 配置影响。
 *
 * <p>实例无可变状态，配置完成后的 {@link ObjectMapper} 只读，可跨线程共用。
 * 理由见 {@code docs/decisions/architecture/2026-09-24-handwritten-exhaustive-switch-in-event-codec.md}。
 */
public final class EventCodec {

    /**
     * 落库与传输共用的 mapper，刻意不复用 Spring 容器里的那个。
     *
     * <p>容器里那个面向 Web 层，配置会随接口需求变化；这里编码的是持久化格式，
     * 全局配置一变，同一事实就会被重新编码成另一种字节。所以单独配一份，只服务编解码。
     *
     * <p>{@code ALWAYS} 让 null 组件显式写出，例如空项目起步时的 {@code baseCommit}；
     * 关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES} 让旧行里多出来的键被忽略，保证 append-only
     * 日志里的历史始终读得出来。
     *
     * <p>包级可见：{@link EventEnvelopeCodec} 在同一 wire format 外面再包一层信封，
     * 两层必须共用一套编码设置。
     */
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .defaultPropertyInclusion(JsonInclude.Value.ALL_ALWAYS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** 异常信息里带上 payload 是为了排障，但不能把几万字符的工具输出整条塞进日志。 */
    private static final int MAX_ABBREVIATED_PAYLOAD = 500;

    /**
     * 一次编码的结果：判别字段与内容分开。
     *
     * <p>两个调用方都需要这一对：写库时填入 {@code event} 表的 {@code type} 与
     * {@code payload} 两列；SSE 下行时写进帧的 {@code data:} JSON，键名同为
     * {@code type} 与 {@code payload}，不使用 {@code event:} 字段。
     *
     * @param type    事件类型
     * @param payload 事件本体的 JSON
     */
    public record EncodedEvent(EventType type, String payload) {

        public EncodedEvent {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * 事件转换为类型加 payload。
     *
     * <p>也接受易失事件（例如 {@link AssistantDelta}），SSE 下行需要这个格式。易失事件
     * 不能落库，这一条由 {@code EventStore.append} 的形参类型
     * {@link com.codeloom.domain.event.PersistentEvent} 在编译期拦住，见
     * {@code docs/decisions/architecture/2026-09-24-events-split-persistent-and-ephemeral.md}。
     *
     * @param event 任意事件
     * @return 类型与 JSON payload
     * @throws EventCodecException 序列化失败
     */
    public EncodedEvent encode(Event event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case SessionStarted e -> new EncodedEvent(EventType.SESSION_STARTED, write(e));
            case SessionStateChanged e -> new EncodedEvent(EventType.SESSION_STATE_CHANGED, write(e));
            case UserMessage e -> new EncodedEvent(EventType.USER_MESSAGE, write(e));
            case AgentNoteDelivered e -> new EncodedEvent(EventType.AGENT_NOTE_DELIVERED, write(e));
            case PlatformInstruction e -> new EncodedEvent(EventType.PLATFORM_INSTRUCTION, write(e));
            case AssistantMessage e -> new EncodedEvent(EventType.ASSISTANT_MESSAGE, write(e));
            case ToolCallRequested e -> new EncodedEvent(EventType.TOOL_CALL_REQUESTED, write(e));
            case ToolResult e -> new EncodedEvent(EventType.TOOL_RESULT, write(e));
            case ToolResultsCleared e -> new EncodedEvent(EventType.TOOL_RESULTS_CLEARED, write(e));
            case ToolCancelled e -> new EncodedEvent(EventType.TOOL_CANCELLED, write(e));
            case ToolInterrupted e -> new EncodedEvent(EventType.TOOL_INTERRUPTED, write(e));
            case ToolApprovalRequested e ->
                    new EncodedEvent(EventType.TOOL_APPROVAL_REQUESTED, write(e));
            case ToolApprovalResolved e ->
                    new EncodedEvent(EventType.TOOL_APPROVAL_RESOLVED, write(e));
            case ToolRejected e -> new EncodedEvent(EventType.TOOL_REJECTED, write(e));
            case CheckpointCreated e -> new EncodedEvent(EventType.CHECKPOINT_CREATED, write(e));
            case WorkspaceChanges e -> new EncodedEvent(EventType.WORKSPACE_CHANGES, write(e));
            case LlmRetryScheduled e -> new EncodedEvent(EventType.LLM_RETRY_SCHEDULED, write(e));
            case ContextCompacted e -> new EncodedEvent(EventType.CONTEXT_COMPACTED, write(e));
            case SessionRewound e -> new EncodedEvent(EventType.SESSION_REWOUND, write(e));
            case SessionSynced e -> new EncodedEvent(EventType.SESSION_SYNCED, write(e));
            case TodoListUpdated e -> new EncodedEvent(EventType.TODO_LIST_UPDATED, write(e));
            case VerificationResult e -> new EncodedEvent(EventType.VERIFICATION_RESULT, write(e));
            case TurnTokensUsed e -> new EncodedEvent(EventType.TURN_TOKENS_USED, write(e));
            case ModelChanged e -> new EncodedEvent(EventType.MODEL_CHANGED, write(e));
            case AssistantDelta e -> new EncodedEvent(EventType.ASSISTANT_DELTA, write(e));
            case ReasoningDelta e -> new EncodedEvent(EventType.REASONING_DELTA, write(e));
        };
    }

    /**
     * 类型加 payload 转换为事件；{@link EventType} 的每一种类型都能解。
     *
     * <p>包括 {@link EventType#ASSISTANT_DELTA}：编解码服务两条线，{@code event} 表和
     * Redis Pub/Sub 消息体。流式增量不落库，但要跨实例推送，所以反序列化端必须认得它。
     *
     * <p>“谁能落库”不归 codec 管：{@link com.codeloom.realtime.persistence.MyBatisEventStore}
     * 从表里读出一行后才检查它是不是 {@link com.codeloom.domain.event.EphemeralEvent}。
     *
     * @param type    事件类型
     * @param payload 事件本体的 JSON
     * @return 对应事件
     * @throws EventCodecException payload 无法反序列化
     */
    public Event decode(EventType type, String payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        return switch (type) {
            case SESSION_STARTED -> read(payload, SessionStarted.class, type);
            case SESSION_STATE_CHANGED -> read(payload, SessionStateChanged.class, type);
            case USER_MESSAGE -> read(payload, UserMessage.class, type);
            case AGENT_NOTE_DELIVERED -> read(payload, AgentNoteDelivered.class, type);
            case PLATFORM_INSTRUCTION -> read(payload, PlatformInstruction.class, type);
            case ASSISTANT_MESSAGE -> read(payload, AssistantMessage.class, type);
            case TOOL_CALL_REQUESTED -> read(payload, ToolCallRequested.class, type);
            case TOOL_RESULT -> read(payload, ToolResult.class, type);
            case TOOL_RESULTS_CLEARED -> read(payload, ToolResultsCleared.class, type);
            case TOOL_CANCELLED -> read(payload, ToolCancelled.class, type);
            case TOOL_INTERRUPTED -> read(payload, ToolInterrupted.class, type);
            case TOOL_APPROVAL_REQUESTED -> read(payload, ToolApprovalRequested.class, type);
            case TOOL_APPROVAL_RESOLVED -> read(payload, ToolApprovalResolved.class, type);
            case TOOL_REJECTED -> read(payload, ToolRejected.class, type);
            case CHECKPOINT_CREATED -> read(payload, CheckpointCreated.class, type);
            case WORKSPACE_CHANGES -> read(payload, WorkspaceChanges.class, type);
            case LLM_RETRY_SCHEDULED -> read(payload, LlmRetryScheduled.class, type);
            case CONTEXT_COMPACTED -> read(payload, ContextCompacted.class, type);
            case SESSION_REWOUND -> read(payload, SessionRewound.class, type);
            case SESSION_SYNCED -> read(payload, SessionSynced.class, type);
            case TODO_LIST_UPDATED -> read(payload, TodoListUpdated.class, type);
            case VERIFICATION_RESULT -> read(payload, VerificationResult.class, type);
            case TURN_TOKENS_USED -> read(payload, TurnTokensUsed.class, type);
            case MODEL_CHANGED -> read(payload, ModelChanged.class, type);
            case ASSISTANT_DELTA -> read(payload, AssistantDelta.class, type);
            case REASONING_DELTA -> read(payload, ReasoningDelta.class, type);
        };
    }

    private static String write(Event event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 序列化的是我们自己定义的 record，正常不该失败
            throw new EventCodecException("事件序列化失败：" + event, e);
        }
    }

    private static <T extends Event> T read(String payload, Class<T> type, EventType expected) {
        try {
            return MAPPER.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new EventCodecException(
                    "事件反序列化失败：type=" + expected + " payload=" + abbreviate(payload), e);
        }
    }

    private static String abbreviate(String payload) {
        return payload.length() <= MAX_ABBREVIATED_PAYLOAD
                ? payload
                : payload.substring(0, MAX_ABBREVIATED_PAYLOAD) + "…（共 " + payload.length() + " 字符）";
    }
}
