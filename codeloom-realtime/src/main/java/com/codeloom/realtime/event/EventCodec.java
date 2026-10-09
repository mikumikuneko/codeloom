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
 * {@link Event} 与「{@link EventType} + JSON payload」之间的双向映射。
 *
 * <h2>手写穷尽 switch，而不是让 Jackson 自己认多态</h2>
 * Jackson 有多态反序列化（{@code @JsonTypeInfo}），一行注解就能自动分派。不用它，有三个理由：
 *
 * <ol>
 *   <li><b>注解写不进 domain。</b> {@code @JsonTypeInfo} 得加在 sealed 接口上，而 domain
 *       模块是零依赖的 —— 加 Jackson 注解就等于让领域模型依赖序列化框架，
 *       以后换格式还得改领域代码。这是六边形最不想出现的那条反向依赖。
 *   <li><b>编译器替我们记着。</b> 下面两个 switch 都没有 {@code default}。
 *       新增一个事件类型，这里**编译不过**，逼着人做决定。用注解自动分派就丢掉了这道防线：
 *       新类型会被安静地接受，然后在某个字段上以错误的方式序列化。
 *   <li><b>落库格式要稳定。</b> 手写的映射里，字段名就是 record 的组件名，不受全局
 *       Jackson 配置（命名策略、非空省略之类）影响。见下面对 {@code MAPPER} 的说明。
 * </ol>
 *
 * <h2>无状态，可共享</h2>
 * 实例不持有任何可变状态，{@code ObjectMapper} 本身也是线程安全的（配置完成后只读），
 * 所以可以安全地在多线程/多虚拟线程间共用。
 */
public final class EventCodec {

    /**
     * 编解码用的 mapper，**刻意不复用 Spring 容器里那个**。
     *
     * <p>容器里那个是给 Web 层用的，它的配置（比如 {@code default-property-inclusion}）
     * 会随接口需求变化。而这里是**落库的持久化格式**：某天有人为了「让响应体更瘦」
     * 把全局的 null 省略一开，历史事件就会以另一种形式被重新编码 —— 同一个事实两种字节，
     * 那是 append-only 日志最不该出现的事。所以这里自己配一份，配置只服务于这一个用途。
     *
     * <p>两条具体的设置：
     * <ul>
     *   <li>{@code ALWAYS}：null 组件也写出去，不省略。「没有 baseCommit」（空项目起步）
     *       在 JSON 里就应该看得见是 null，而不是靠"这个键不存在"来暗示。
     *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} 关掉：事件表是 append-only 的，
     *       里面的行要能一直读得出来。将来某个 record 删掉一个组件，旧行里多出来的键
     *       不该让它直接报错 —— 那是「历史读不出来」，比一个字段丢失严重得多。
     * </ul>
     *
     * <p>包级可见而不是私有：{@link EventEnvelopeCodec} 要在它外面再包一层信封
     * （会话、seq、时间）。那一层和这一层是**同一个 wire format 的两部分**，
     * 各配一份 mapper 只会让两层的编码设置有机会走岔。
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
     * <p>之所以是两条信息一起返回，是因为两个调用方都要这一对：
     * 写库时要填 {@code event} 表的 {@code type} 与 {@code payload} 两列；
     * SSE 下行时要把类型名放进帧的 {@code event:} 字段、payload 放进 {@code data:}。
     */
    public record EncodedEvent(EventType type, String payload) {

        public EncodedEvent {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * 事件 → 类型 + payload。
     *
     * <p>对易失事件（{@link AssistantDelta}）同样有效 —— SSE 下行要把流式增量发给浏览器，
     * 用的就是这个格式。它只是不该进 {@code event} 表，而那件事由
     * {@code EventStore.append(...)} 的形参类型（{@code PersistentEvent}）在编译期拦住。
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
     * 类型 + payload → 事件，**全类型、双向对称**。
     *
     * <h2>为什么 {@link EventType#ASSISTANT_DELTA} 也解得了</h2>
     * 因为编解码现在服务**两条**线：{@code event} 表，和 Redis Pub/Sub 的消息体。
     * 流式增量不走数据库，但要跨实例推送给正在看的人 —— 反序列化端必须认它。
     *
     * <p>"谁能落库"这条规则不在 codec 里，而在 {@code MyBatisEventStore}：它读出一行之后
     * 检查 {@code instanceof EphemeralEvent}。codec 是一个纯粹的映射，不该知道这件事。
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
