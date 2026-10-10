package com.codeloom.realtime.event;

import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.EventType;
import com.codeloom.domain.event.LlmRetryScheduled;
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
import com.codeloom.domain.event.ToolRejected;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.ToolResultsCleared;
import com.codeloom.domain.event.TodoListUpdated;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.event.WorkspaceChanges;
import com.codeloom.domain.workspace.FileChange;
import com.codeloom.domain.event.VerificationResult;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.SessionState;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事件编解码。这是**落库格式**的测试，所以它盯的不只是「能不能转回去」，
 * 还有格式本身长什么样 —— 后者一旦变了，库里已有的行就读不出来了。
 */
class EventCodecTest {

    private final EventCodec codec = new EventCodec();

    // ------------------------------------------------------------------
    // 全类型往返
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("每一种事件都能原样往返，落库的和易失的都一样")
    void everyEventTypeRoundTrips(EventType expected, Event event) {
        EventCodec.EncodedEvent encoded = codec.encode(event);

        assertThat(encoded.type()).isEqualTo(expected);
        assertThat(codec.decode(encoded.type(), encoded.payload())).isEqualTo(event);
    }

    @Test
    @DisplayName("样本覆盖了每一个 EventType —— 新增事件类型却忘了加样本时这条会红")
    void samplesCoverEveryEventType() {
        // 「两个 switch 都没有 default，新增类型编译不过」是这套设计的第一道防线，
        // 但它拦不住「类型加了、Codec 也改了、测试样本忘了加」——
        // 那样往返测试会安静地漏测新类型。这条断言补上那个缺口。
        Set<EventType> covered = samples()
                .map(arguments -> (EventType) arguments.get()[0])
                .collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrder(EventType.values());
    }

    /**
     * 每一种事件各一份样本，以及各自应当映射到的 {@link EventType}。
     * （刻意不写「共 N 种」—— 那个数字每次加类型都要改，而不改也不会有人发现。）
     *
     * <p>数据尽量贴近真实：命令、退出码、工具调用 id 都写成实际会出现的样子，
     * 而不是 {@code "x"}、{@code 1} 这类占位值 —— 占位值掩盖不住字段名写错，
     * 但真实数据能（比如 {@code argumentsJson} 里那串带着转义的 JSON）。
     */
    private static Stream<Arguments> samples() {
        return Stream.of(
                Arguments.of(EventType.SESSION_STARTED, new SessionStarted(
                        "session/3f2a", "D:/codeloom/workspaces/3f2a", "basecommit0")),
                Arguments.of(EventType.SESSION_STATE_CHANGED, new SessionStateChanged(
                        SessionState.IDLE, SessionState.THINKING, null)),
                Arguments.of(EventType.USER_MESSAGE, new UserMessage("看一下 OrderService 的并发处理")),
                Arguments.of(EventType.AGENT_NOTE_DELIVERED, new AgentNoteDelivered(
                        SessionId.of("0b1c2d3e-0000-0000-0000-000000000001"), UserId.of("u-li"),
                        "我给 OrderService 加好锁了，你那边别再重复改同一个方法")),
                Arguments.of(EventType.PLATFORM_INSTRUCTION, new PlatformInstruction(
                        "平台自动跑了一次验证，未通过，请修正后再说完成", "verification-failed")),
                Arguments.of(EventType.ASSISTANT_MESSAGE,
                        new AssistantMessage("改好了，加了一把锁", "deepseek-flash")),
                Arguments.of(EventType.TOOL_CALL_REQUESTED, new ToolCallRequested(
                        "call_1", "read_file", "{\"path\":\"OrderService.java\"}")),
                Arguments.of(EventType.TOOL_RESULT, new ToolResult(
                        "call_1", true, "public class OrderService {}", true, 0, 37L)),
                Arguments.of(EventType.TOOL_RESULTS_CLEARED,
                        new ToolResultsCleared(List.of("call_1", "call_4"))),
                // 退避等待里那些数**必须原样往返**：界面上要拿 delayMs 倒计时、
                // 拿 attempt/maxAttempts 画"第 2/3 次"，四舍五入一下就对不上了
                Arguments.of(EventType.LLM_RETRY_SCHEDULED,
                        new LlmRetryScheduled(2, 3, 4_500L, "限流")),
                Arguments.of(EventType.TOOL_CANCELLED, new ToolCancelled("call_2")),
                Arguments.of(EventType.TOOL_INTERRUPTED, new ToolInterrupted("call_3")),
                Arguments.of(EventType.TOOL_APPROVAL_REQUESTED,
                        new ToolApprovalRequested("call_7", "测试：这条命令要人批一下")),
                Arguments.of(EventType.TOOL_APPROVAL_RESOLVED, new ToolApprovalResolved(
                        "call_7", false, UserId.of("u-li"), "这条命令会把整个 build 目录删掉")),
                Arguments.of(EventType.TOOL_REJECTED, new ToolRejected("call_7")),
                Arguments.of(EventType.CHECKPOINT_CREATED, new CheckpointCreated("c0ffee1", 2)),
                Arguments.of(EventType.CONTEXT_COMPACTED, new ContextCompacted(
                        42L, "用户让我给 OrderService 的并发处理加锁；我读了文件、加了一把锁，"
                                + "mvn -q test 通过")),
                Arguments.of(EventType.MODEL_CHANGED,
                        new ModelChanged("deepseek-chat", "deepseek-reasoner")),
                Arguments.of(EventType.SESSION_REWOUND,
                        new SessionRewound("deadbee", 733L, UserId.of("u-li"))),
                Arguments.of(EventType.SESSION_SYNCED,
                        new SessionSynced("aaaa111", "bbbb222")),
                // truncated 取 true：那个字段两边都得进 JSON（false 走的是同一段代码）。
                // turnIndex 取一个**非零**的数：0 是个合法的轮次号，拿 0 试等于没试出
                // "缺字段"和"第 0 轮"的区别
                Arguments.of(EventType.WORKSPACE_CHANGES, new WorkspaceChanges("deadbee", List.of(
                        new FileChange("src/OrderService.java", 12, 3, false, false),
                        new FileChange("assets/logo.png", 0, 0, true, true)), true, 7)),
                Arguments.of(EventType.TODO_LIST_UPDATED, new TodoListUpdated(List.of(
                        new TodoListUpdated.Item("把 OrderService 的并发处理加把锁",
                                TodoListUpdated.State.COMPLETED),
                        new TodoListUpdated.Item("补一条并发用例",
                                TodoListUpdated.State.IN_PROGRESS),
                        new TodoListUpdated.Item("mvn -q test",
                                TodoListUpdated.State.PENDING)))),
                Arguments.of(EventType.VERIFICATION_RESULT, new VerificationResult(
                        "verify-1", "mvn -q test", false, 1, "Tests run: 5, Failures: 1")),
                Arguments.of(EventType.TURN_TOKENS_USED, new TurnTokensUsed(
                        12_345, 678, 400, 9_000, 11_800, 64_000, "deepseek-flash")),
                // 流式增量：不落库，但要跨实例推送（Pub/Sub 的消息体），所以编解码都得认它
                Arguments.of(EventType.ASSISTANT_DELTA, new AssistantDelta("半个句")),
                Arguments.of(EventType.REASONING_DELTA, new ReasoningDelta("先看看这个文件……")));
    }

    // ------------------------------------------------------------------
    // 可空组件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【编解码】少一个字段时读成 null，不是 0 —— 0 是个看着像位置的值")
    void aMissingFieldDecodesAsNullNotAsZero() {
        String payload = "{\"toCommitSha\":\"deadbee\",\"byUserId\":{\"value\":\"u-li\"}}";

        Event decoded = codec.decode(EventType.SESSION_REWOUND, payload);

        // toCheckpointSeq 是回滚的定位键，seq 从 1 开始 —— 被读成 0 的话它会"看着像"
        // 一个位置，然后拿它去查一条不存在的 checkpoint。所以它是包装类型，
        // 缺了就是 null，而 null 走的是明确的"查不到"分支（见 SessionRewound 的类注释）
        assertThat(decoded).isEqualTo(new SessionRewound("deadbee", null, UserId.of("u-li")));
    }

    @Test
    @DisplayName("【向后兼容·删字段】多出来的键被安静忽略 —— 那是「以后还能删字段」的前提")
    void staleKeysInAPayloadAreIgnored() {
        // 比现在的 record 多 toTurnIndex / fromTurnIndex 两个键。多出来的键要被忽略 ——
        // 这是"以后还能删字段"的前提，机制见 EventCodec.MAPPER
        String payload = "{\"toCommitSha\":\"deadbee\",\"toTurnIndex\":1,"
                + "\"toCheckpointSeq\":733,\"fromTurnIndex\":4,\"byUserId\":{\"value\":\"u-li\"}}";

        Event decoded = codec.decode(EventType.SESSION_REWOUND, payload);

        // 多出来的两个键被安静地忽略，位置和人都照样读得出来
        assertThat(decoded).isEqualTo(new SessionRewound("deadbee", 733L, UserId.of("u-li")));
    }

    @Test
    @DisplayName("【向后兼容·删字段】删掉 source 之前落的那条用量事件仍然读得出来")
    void legacyTurnTokensUsedStillDecodesAfterAFieldWasRemoved() {
        // 库里已有的就是这种 payload：比现在的 record 多一个 source。
        // 这条盯的是"**删**字段"这个做法管不管用 —— 管用的话旧行照读，
        // 多出来的那个键被安静地忽略（见 EventCodec.MAPPER）
        String legacy = "{\"inputTokens\":292,\"outputTokens\":52,"
                + "\"cachedInputTokens\":0,\"model\":\"deepseek-flash\",\"source\":\"USER_TURN\"}";

        Event decoded = codec.decode(EventType.TURN_TOKENS_USED, legacy);

        assertThat(decoded).isEqualTo(new TurnTokensUsed(292, 52, 0, 0, null, null, "deepseek-flash"));
    }

    @Test
    @DisplayName("【向后兼容·加字段】加 reasoningTokens 之前落的那条用量事件仍然读得出来")
    void legacyTurnTokensUsedStillDecodesAfterAFieldWasAdded() {
        // 和上面那条是**反方向**的，而它们坏起来的方式不一样：删字段靠
        // FAIL_ON_UNKNOWN_PROPERTIES 兜住，加字段靠的却是 Jackson 对 record 构造器
        // 缺失组件的容忍。后者是默认行为、没有一句配置写着它 —— 所以必须有一条测试盯着，
        // 不然哪天有人打开 FAIL_ON_MISSING_CREATOR_PROPERTIES，库里所有旧的用量事件
        // 会在读历史时集体炸掉，而那时没人会想到是这里
        //
        // 这条同时又覆盖了**后加的 contextTokens / contextWindow**：
        // 缺了就缺了，读出来是 null（没有读数），hasContext() 会说"别画"
        String legacy = "{\"inputTokens\":292,\"outputTokens\":52,"
                + "\"cachedInputTokens\":0,\"model\":\"deepseek-flash\"}";

        Event decoded = codec.decode(EventType.TURN_TOKENS_USED, legacy);

        // 缺失的 reasoningTokens 是 **0**（"这家 provider 没报"），不是 null ——
        // 它是基本类型，而 **0 在这个字段上是个正常的真值**：非推理模型本来就没有思考 token。
        // 上下文那两个不一样：`上下文 = 0` 不可能发生，所以 0 在那儿只能是暗号，
        // 它们因此是包装类型，缺了读出来是 null
        assertThat(decoded).isEqualTo(new TurnTokensUsed(292, 52, 0, 0, null, null, "deepseek-flash"));
        assertThat(((TurnTokensUsed) decoded).hasContext()).isFalse();
    }

    @Test
    @DisplayName("可空组件往返后仍是 null —— 不是空串，也不是「键都不见了」")
    void nullableComponentsStayNull() {
        // 三处真实会出现 null 的地方：
        //   空项目起步时没有 baseCommit、纯内存工具没有 exitCode、正常状态流转没有 reason
        assertRoundTrip(new SessionStarted("b", "p", null));
        assertRoundTrip(new ToolResult("c1", true, "ok", false, null, 3L));
        assertRoundTrip(new SessionStateChanged(SessionState.THINKING, SessionState.FAILED, null));
    }

    @Test
    @DisplayName("null 组件在 JSON 里是显式的 null，不是被省略掉")
    void nullsAreWrittenExplicitly() {
        // 落库格式要自解释：「没有 baseCommit」应该看得见，而不是靠"这个键不存在"去暗示。
        // 这也让 payload 不依赖 mapper 的 non-null 策略 —— 那条策略是给 Web 层用的。
        EventCodec.EncodedEvent encoded = codec.encode(new SessionStarted("b", "p", null));

        assertThat(encoded.payload()).contains("\"baseCommit\":null");
    }

    // ------------------------------------------------------------------
    // 格式本身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("payload 的字段名就是 record 的组件名 —— 这是落库格式，改组件名等于改格式")
    void payloadUsesComponentNames() {
        EventCodec.EncodedEvent encoded = codec.encode(
                new ToolResult("c1", false, "boom", true, 1, 42L));

        assertThat(encoded.payload())
                .contains("\"callId\":\"c1\"")
                .contains("\"success\":false")
                .contains("\"output\":\"boom\"")
                .contains("\"truncated\":true")
                .contains("\"exitCode\":1")
                .contains("\"durationMs\":42");
    }

    @Test
    @DisplayName("argumentsJson 原样存回，没有被解析再序列化")
    void toolArgumentsSurviveVerbatim() {
        // 这串要原样回灌给模型。任何「解析成对象再序列化回去」的往返都可能改变字段顺序
        // 或数字精度，而模型对这两者敏感 —— 所以它一路都是字符串。
        String raw = "{\"path\":\"A.java\",\"old_string\":\"x = 1\",\"n\":1.10}";
        EventCodec.EncodedEvent encoded = codec.encode(
                new ToolCallRequested("call_1", "edit_file", raw));

        Event decoded = codec.decode(encoded.type(), encoded.payload());

        assertThat(((ToolCallRequested) decoded).argumentsJson()).isEqualTo(raw);
    }

    // ------------------------------------------------------------------
    // 坏数据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("codec 是纯粹的映射：它不知道「谁能落库」，那条规则在存储层")
    void codecKnowsNothingAboutPersistenceRules() {
        // 「易失事件不许落库」这条规则**不在这一层**。codec 服务两条线
        // （event 表 + Pub/Sub 消息体），而流式增量本来就要走后者，所以它必须解得开 delta。
        // 真正该拦住的是「从 event 表里读出一行 delta」—— 那件事由 MyBatisEventStore 管，
        // 因为只有它知道那一行是从哪儿来的。
        EventCodec.EncodedEvent encoded = codec.encode(new AssistantDelta("半个句"));

        assertThat(encoded.type()).isEqualTo(EventType.ASSISTANT_DELTA);
        assertThat(codec.decode(encoded.type(), encoded.payload()))
                .isEqualTo(new AssistantDelta("半个句"));
    }

    @Test
    @DisplayName("payload 坏掉时的报错要指向具体事件类型，而不是笼统的解析异常")
    void corruptPayloadIsReportedWithItsType() {
        assertThatThrownBy(() -> codec.decode(EventType.TOOL_RESULT, "{\"callId\":"))
                .isInstanceOf(EventCodecException.class)
                .hasMessageContaining("TOOL_RESULT");
    }

    @Test
    @DisplayName("旧行里多出来的键不会让反序列化失败 —— append-only 的日志必须读得动历史")
    void unknownPropertiesAreTolerated() {
        // 将来某个 record 删掉一个组件，旧行里就会多出这个键 ——
        // 「历史读不出来」比「一个字段丢了」严重得多（见 EventCodec.MAPPER）。
        Event decoded = codec.decode(EventType.USER_MESSAGE,
                "{\"text\":\"你好\",\"removedInAFutureVersion\":true}");

        assertThat(decoded).isEqualTo(new UserMessage("你好"));
    }

    // ------------------------------------------------------------------

    private void assertRoundTrip(Event event) {
        EventCodec.EncodedEvent encoded = codec.encode(event);

        assertThat(codec.decode(encoded.type(), encoded.payload()))
                .as("往返后应与原对象相等")
                .isEqualTo(event);
    }
}
