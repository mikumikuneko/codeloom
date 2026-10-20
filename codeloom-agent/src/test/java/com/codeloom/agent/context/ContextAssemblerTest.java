package com.codeloom.agent.context;

import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.agent.llm.LlmRole;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.PlatformInstruction;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TodoListUpdated;
import com.codeloom.domain.event.ToolApprovalRequested;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolCancelled;
import com.codeloom.domain.event.ToolInterrupted;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.ToolResultsCleared;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    private static final String SYSTEM = "你是一个协作开发助手。";
    private static final SessionId SESSION = SessionId.of("s1");
    private static final Instant AT = Instant.parse("2026-09-25T10:00:00Z");

    /** 用户消息后的时钟；取自事件落库时间，因此测试中固定。 */
    private static String clockAt(Instant at) {
        ZoneId zone = ZoneId.systemDefault();
        return "[平台指令] 现在是 " + LocalDateTime.ofInstant(at, zone)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                + "（" + zone.getId() + "）。用户说「今天」「最近」时按这个时区算。";
    }

    private static String said(String text) {
        return text + "\n\n" + clockAt(AT);
    }

    /** 装配器；按 id 查询用户名，只用于给协作者留言标注来源。 */
    private final ContextAssembler assembler =
            new ContextAssembler(id -> id.equals(UserId.of("u-li")) ? "小李" : "某人");
    private final List<StoredEvent> events = new ArrayList<>();

    /** 追加事件并返回 seq；回滚按序号定位目标。 */
    private long append(Event event) {
        long seq = events.size() + 1L;
        events.add(new StoredEvent(SESSION, seq, AT, event));
        return seq;
    }

    private List<LlmMessage> assemble() {
        return assembler.assemble(events, SYSTEM);
    }

    /**
     * 投影对外可见的全部产出：消息与派生事实。只比较消息会漏掉推倒重来时未清理的事实。
     *
     * @param messages           消息
     * @param readPaths          读过的路径
     * @param pendingApprovedCall 待补跑的调用
     */
    private record ProjectionView(List<LlmMessage> messages, Set<String> readPaths,
                                  Optional<ToolCall> pendingApprovedCall) {
    }

    /** 读取投影当前对外可见的产出。 */
    private static ProjectionView viewOf(ContextAssembler.Projection projection,
                                         List<LlmMessage> sink) {
        return new ProjectionView(List.copyOf(sink), projection.readPaths(),
                projection.pendingApprovedCall());
    }

    /** 整条流一次喂完的结果，作为等价断言基准。 */
    private ProjectionView fedInOneGo() {
        ContextAssembler.Projection fresh = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        fresh.advance(events, sink);
        return viewOf(fresh, sink);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("系统提示词排在最前，且只有一条")
    void systemPromptComesFirst() {
        append(new UserMessage("你好"));

        List<LlmMessage> messages = assemble();

        assertThat(messages.getFirst().role()).isEqualTo(LlmRole.SYSTEM);
        assertThat(messages.getFirst().content()).isEqualTo(SYSTEM);
        assertThat(messages.get(1).role()).isEqualTo(LlmRole.USER);
    }

    @Test
    @DisplayName("【换模型】每条回复带着**自己那个**来源模型 —— 判断才做得了")
    void eachReplyCarriesItsOwnSourceModel() {
        append(new UserMessage("先看 A"));
        // 第一条回复由 flash 产出。
        append(new AssistantMessage("看完了", "deepseek-flash", "先读哪个文件"));
        append(new UserMessage("换个模型接着说"));
        // 第二条回复由 pro 产出。
        append(new AssistantMessage("接着说", "deepseek-pro", "这次换个路子"));

        List<LlmMessage> messages = assemble();

        // 每条思考必须带来源模型，否则适配器无法判断它是否属于目标模型。
        assertThat(messages.get(2).reasoning()).isEqualTo("先读哪个文件");
        assertThat(messages.get(2).model()).isEqualTo("deepseek-flash");
        assertThat(messages.get(4).reasoning()).isEqualTo("这次换个路子");
        assertThat(messages.get(4).model()).isEqualTo("deepseek-pro");
    }

    @Test
    @DisplayName("【并行调用】两条 ToolCallRequested 相邻 → 合成一条带两个 tool_calls 的 assistant 消息")
    void parallelToolCallsAreGroupedTogether() {
        append(new UserMessage("把 A 和 B 都读一下"));
        append(new AssistantMessage("我先读两个文件", null));
        append(new ToolCallRequested("call_1", "read_file", "{\"path\":\"A.java\"}"));
        append(new ToolCallRequested("call_2", "read_file", "{\"path\":\"B.java\"}"));
        append(new ToolResult("call_1", true, "class A {}", false, 0, 5));
        append(new ToolResult("call_2", true, "class B {}", false, 0, 5));
        append(new AssistantMessage("两个都看完了", null));

        List<LlmMessage> messages = assemble();

        // system, user, assistant(2个tool_calls), tool, tool, assistant
        assertThat(messages).hasSize(6);
        LlmMessage assistant = messages.get(2);
        assertThat(assistant.role()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(assistant.content()).isEqualTo("我先读两个文件");
        assertThat(assistant.toolCalls()).hasSize(2);
        assertThat(assistant.toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(assistant.toolCalls().get(1).id()).isEqualTo("call_2");

        assertThat(messages.get(3).role()).isEqualTo(LlmRole.TOOL);
        assertThat(messages.get(3).toolCallId()).isEqualTo("call_1");
        assertThat(messages.get(3).content()).contains("成功").contains("class A {}");

        assertThat(messages.get(5).content()).isEqualTo("两个都看完了");
    }

    @Test
    @DisplayName("【思考回传】带工具调用的那条 assistant 消息必须带上它当时的思考")
    void reasoningRidesAlongWithTheToolCallMessage() {
        // 推理模型常只调工具、不说话但有思考；丢失思考会让下一次请求 400。
        append(new UserMessage("读一下 A"));
        // 三参构造依次是 text、model、reasoning。
        append(new AssistantMessage("", "deepseek-reasoner", "我在想先看哪个文件"));
        append(new ToolCallRequested("call_1", "read_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("call_1", true, "class A {}", false, 0, 5));
        append(new AssistantMessage("看完了", "deepseek-reasoner", "没什么特别的"));

        List<LlmMessage> messages = assemble();

        LlmMessage toolTurn = messages.get(2);
        assertThat(toolTurn.role()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(toolTurn.toolCalls()).hasSize(1);
        assertThat(toolTurn.reasoning()).isEqualTo("我在想先看哪个文件");

        // 结论消息不带工具调用，但下一轮同样要发送。
        assertThat(messages.get(4).reasoning()).isEqualTo("没什么特别的");
    }

    @Test
    @DisplayName("【思考回传·分批发工具】一次回复被投影成两条 assistant 消息，两条都要带思考")
    void oneResponseSplitIntoTwoMessagesKeepsReasoningOnBoth() {
        // 一次回复的两个调用按批执行：两条 ToolCallRequested 不相邻，
        // 中间隔着前一批结果，导致第一条 assistant 消息已被 flush。
        append(new UserMessage("写个 helloworld"));
        append(new AssistantMessage("", "deepseek-reasoner", "先看看目录里有什么"));
        append(new ToolCallRequested("call_A", "glob", "{\"pattern\":\"**/*.class\"}"));
        append(new ToolResult("call_A", true, "没有匹配", false, 0, 1));
        // 同一回复的第二批；此时第一条 assistant 消息已经 flush。
        append(new ToolCallRequested("call_B", "run_command", "{\"command\":\"java -version\"}"));
        append(new ToolApprovalRequested("call_B", "测试：这条命令要人批一下"));
        append(new ToolApprovalResolved("call_B", true, UserId.of("root"), null));

        List<LlmMessage> messages = assemble();

        // system, user, assistant([A]), tool(A), assistant([B]), tool(B)
        assertThat(messages).hasSize(6);
        assertThat(messages.get(2).toolCalls()).singleElement()
                .satisfies(c -> assertThat(c.id()).isEqualTo("call_A"));
        assertThat(messages.get(4).toolCalls()).singleElement()
                .satisfies(c -> assertThat(c.id()).isEqualTo("call_B"));

        // 两条都带同一份思考；带 tool_calls 的那条缺少它会让下一次请求返回 400。
        assertThat(messages.get(2).reasoning()).isEqualTo("先看看目录里有什么");
        assertThat(messages.get(4).reasoning()).isEqualTo("先看看目录里有什么");
    }

    @Test
    @DisplayName("【批准后真的跑】批准答复被真实结果**原地替换**，不是再加一条 tool 消息")
    void anExecutedApprovedCallReplacesTheApprovalPlaceholder() {
        append(new UserMessage("跑一下 javac"));
        append(new AssistantMessage("", "deepseek-reasoner", "先试试 javac"));
        append(new ToolCallRequested("call_1", "run_command", "{\"command\":\"javac -version\"}"));
        append(new ToolApprovalRequested("call_1", "测试：这条命令要人批一下"));
        append(new ToolApprovalResolved("call_1", true, UserId.of("root"), null));
        // 批准后调用真正执行，结果落库。
        append(new ToolResult("call_1", true, "javac 21.0.1", false, 0, 5));

        List<LlmMessage> messages = assemble();

        // 结构：system、user、assistant([call_1])、tool(call_1)，只有一条 tool。
        assertThat(messages).hasSize(4);
        LlmMessage answered = messages.get(3);
        assertThat(answered.role()).isEqualTo(LlmRole.TOOL);
        assertThat(answered.toolCallId()).isEqualTo("call_1");
        assertThat(answered.content()).contains("javac 21.0.1");
        assertThat(answered.content()).doesNotContain("批准");

        // 同一 tool_call_id 只能有一条 tool 消息。
        assertThat(answeredCalls(messages)).containsExactly("call_1");
    }

    @Test
    @DisplayName("【拒绝的】不补跑，批准答复就一直留着当它的结果")
    void aRejectedCallKeepsThePlaceholderAsItsAnswer() {
        append(new UserMessage("跑一下"));
        append(new AssistantMessage("", "deepseek-reasoner", "试试"));
        append(new ToolCallRequested("call_1", "run_command", "{\"command\":\"rm -rf /\"}"));
        append(new ToolApprovalResolved("call_1", false, UserId.of("root"), "太危险"));

        List<LlmMessage> messages = assemble();

        assertThat(messages).hasSize(4);
        assertThat(messages.get(3).content()).contains("用户拒绝了这次调用").contains("太危险");
        assertThat(answeredCalls(messages)).containsExactly("call_1");
    }

    @Test
    @DisplayName("【拒绝·没留指示】给模型的是「停下等用户」，不是把 null 拼进去")
    void aRejectionWithoutInstructionsTellsTheModelToStop() {
        append(new UserMessage("跑一下"));
        append(new AssistantMessage("", "deepseek-reasoner", "试试"));
        append(new ToolCallRequested("call_1", "run_command", "{\"command\":\"rm -rf /\"}"));
        // 用户只点拒绝、未留言。
        append(new ToolApprovalResolved("call_1", false, UserId.of("root"), null));

        List<LlmMessage> messages = assemble();

        assertThat(messages).hasSize(4);
        assertThat(messages.get(3).content())
                .contains("用户拒绝了这次调用")
                .contains("停下");
        // reason 为 null 时不能把字面量 null 拼进句子。
        assertThat(messages.get(3).content()).doesNotContain("null");
    }

    @Test
    @DisplayName("【不变式】一串混合事件下来，每个 tool_call_id 至多只有一条 tool 消息")
    void everyToolCallIsAnsweredExactlyOnce() {
        append(new UserMessage("先读、再跑、再写"));
        append(new AssistantMessage("", "deepseek-reasoner", "一步一步来"));
        append(new ToolCallRequested("call_1", "read_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("call_1", true, "class A {}", false, 0, 5));
        append(new ToolCallRequested("call_2", "run_command", "{\"command\":\"javac A.java\"}"));
        append(new ToolApprovalResolved("call_2", true, UserId.of("root"), null));
        append(new ToolResult("call_2", true, "编译通过", false, 0, 12));
        append(new ToolCallRequested("call_3", "run_command", "{\"command\":\"rm -rf /\"}"));
        append(new ToolApprovalResolved("call_3", false, UserId.of("root"), "太危险"));

        List<LlmMessage> messages = assemble();

        assertThat(answeredCalls(messages)).containsExactly("call_1", "call_2", "call_3");
    }

    /**
     * 每个 tool_call_id 被回答的次数；同一 id 出现两次会让请求 400。只统计 tool 消息。
     *
     * @param messages 消息
     * @return 被回答的调用 id
     */
    private static List<String> answeredCalls(List<LlmMessage> messages) {
        return messages.stream()
                .filter(message -> message.role() == LlmRole.TOOL)
                .map(LlmMessage::toolCallId)
                .toList();
    }

    @Test
    @DisplayName("换一次回复之后，思考跟着换 —— 不把上一条的留着")
    void aNewResponseReplacesTheReasoning() {
        append(new UserMessage("先读再写"));
        append(new AssistantMessage("我先读一下", "deepseek-reasoner", "第一轮的思考"));
        append(new ToolCallRequested("call_1", "read_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("call_1", true, "class A {}", false, 0, 5));
        append(new AssistantMessage("再改一下", "deepseek-reasoner", "第二轮的思考"));
        append(new ToolCallRequested("call_2", "edit_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("call_2", true, "已修改", false, 0, 3));

        List<LlmMessage> messages = assemble();

        assertThat(messages.get(2).reasoning()).isEqualTo("第一轮的思考");
        assertThat(messages.get(4).reasoning()).isEqualTo("第二轮的思考");
    }

    @Test
    @DisplayName("没想过的那条 assistant 消息，思考是 null（不是空串）")
    void absentReasoningStaysNull() {
        append(new UserMessage("读一下 A"));
        append(new AssistantMessage("我读一下", null));
        append(new ToolCallRequested("call_1", "read_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("call_1", true, "class A {}", false, 0, 5));
        append(new AssistantMessage("读完了", null));

        List<LlmMessage> messages = assemble();

        // null 表示不发送字段；空串会被发送，而服务商拒收空串。
        assertThat(messages.subList(1, messages.size()))
                .allSatisfy(m -> assertThat(m.reasoning()).isNull());
    }

    @Test
    @DisplayName("【串行调用】调用-取结果-再调用 → 必然是两条 assistant 消息，这是格式要求的")
    void sequentialToolTurnsProduceTwoAssistantMessages() {
        append(new UserMessage("先读再改"));
        append(new AssistantMessage("先读一下", null));
        append(new ToolCallRequested("call_1", "read_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("call_1", true, "class A {}", false, 0, 5));
        append(new ToolCallRequested("call_2", "edit_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("call_2", true, "已修改", false, 0, 3));
        append(new AssistantMessage("改好了", null));

        List<LlmMessage> messages = assemble();

        // 无法合并：tool 结果必须紧跟带 tool_calls 的 assistant 消息，中间隔着其他结果。
        assertThat(messages).hasSize(7);
        assertThat(messages.get(4).role()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(messages.get(4).content()).isEmpty();     // 第二次调用前模型没说话
        assertThat(messages.get(4).toolCalls()).singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("edit_file"));
        assertThat(messages.get(6).content()).isEqualTo("改好了");
    }

    @Test
    @DisplayName("模型没说话只调工具时，assistant 消息的正文是空串而不是 null")
    void toolCallWithoutTextStillProducesAMessage() {
        append(new UserMessage("读一下"));
        append(new ToolCallRequested("c1", "read_file", "{}"));

        LlmMessage assistant = assemble().get(2);

        assertThat(assistant.role()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(assistant.content()).isEmpty();
        assertThat(assistant.hasToolCalls()).isTrue();
    }

    @Test
    @DisplayName("截断的工具输出必须带上提示 —— 否则模型以为输出就这么多")
    void truncatedOutputIsLabelled() {
        append(new UserMessage("跑测试"));
        append(new ToolCallRequested("c1", "run_command", "{}"));
        append(new ToolResult("c1", false, "Tests run: 12, Failures: 1", true, 1, 900));

        String toolContent = assemble().get(3).content();

        assertThat(toolContent).contains("失败").contains("退出码 1").contains("已截断");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【配对兜底】请求了却没留下结果的调用，要补一条合成结果 —— 不补的话发出去就是 400")
    void unmatchedToolCallGetsASyntheticResult() {
        append(new UserMessage("跑一下测试"));
        append(new ToolCallRequested("c1", "run_command", "{}"));
        // 执行期间轮次中断，结果永远不会来，事件流里只剩请求。

        List<LlmMessage> messages = assemble();

        // system, user, assistant([c1]), tool(c1)
        assertThat(messages).hasSize(4);
        assertThat(messages.get(2).toolCalls()).singleElement()
                .satisfies(c -> assertThat(c.id()).isEqualTo("c1"));

        LlmMessage tool = messages.get(3);
        assertThat(tool.role()).isEqualTo(LlmRole.TOOL);
        assertThat(tool.toolCallId()).isEqualTo("c1");
        assertThat(tool.content()).contains("没有留下结果").contains("不要直接重试");
    }

    @Test
    @DisplayName("前面几次调用都正常收场时，只有最后悬着的那一个被补上")
    void onlyTheDanglingCallIsPatched() {
        append(new UserMessage("先读再跑"));
        append(new ToolCallRequested("c1", "read_file", "{}"));
        append(new ToolResult("c1", true, "class A {}", false, 0, 5));
        append(new ToolCallRequested("c2", "run_command", "{}"));
        // c2 执行期间轮次中断，没有结果。

        List<LlmMessage> messages = assemble();

        // system, user, assistant([c1]), tool(c1), assistant([c2]), tool(c2)
        assertThat(messages).hasSize(6);
        assertThat(messages.get(3).content()).contains("class A {}");   // c1 是真实结果，没被动过
        assertThat(messages.get(5).toolCallId()).isEqualTo("c2");
        assertThat(messages.get(5).content()).contains("没有留下结果");
    }

    @Test
    @DisplayName("【微压缩】被清掉正文的结果换成占位符 —— 但那条消息还在、配对还在、位置也没动")
    void clearedResultsBecomeAPlaceholder() {
        append(new UserMessage("读个文件"));
        append(new ToolCallRequested("c1", "read_file", "{}"));
        append(new ToolResult("c1", true, "class A { 一大堆正文 }", false, 0, 5));
        append(new ToolResultsCleared(List.of("c1")));

        List<LlmMessage> messages = assemble();

        // 只清正文，消息与配对结构保持完整。
        assertThat(messages).hasSize(4);
        LlmMessage tool = messages.get(3);
        assertThat(tool.role()).isEqualTo(LlmRole.TOOL);
        assertThat(tool.toolCallId()).isEqualTo("c1");
        assertThat(tool.content())
                .contains("已经为了省上下文被清理")
                .doesNotContain("一大堆正文");
    }

    @Test
    @DisplayName("【思考内容】只用于展示 —— 投影进上下文时一个字都不带")
    void reasoningIsNeverProjectedIntoTheContext() {
        append(new UserMessage("算一下"));
        append(new AssistantMessage("等于 42", "deepseek-reasoner",
                "我先看看题目……嗯，看起来是 42"));

        List<LlmMessage> messages = assemble();

        assertThat(messages.get(2).content()).isEqualTo("等于 42");
        // 思考只用于展示；是否回传由适配器决定，投影不把它写进正文。
        assertThat(messages).noneSatisfy(m -> assertThat(m.content()).contains("我先看看题目"));
    }

    @Test
    @DisplayName("【留言】别的会话发来的消息要标明来源 —— 否则模型会当成自己用户的指示照做")
    void noteFromAnotherSessionIsLabelled() {
        append(new UserMessage("继续改订单服务"));
        append(new AgentNoteDelivered(SessionId.of("s-other"), UserId.of("u-li"), "我把锁加好了，你别重复改"));

        List<LlmMessage> messages = assemble();

        LlmMessage note = messages.get(2);
        assertThat(note.role()).isEqualTo(LlmRole.USER);
        assertThat(note.content())
                .contains("来自 小李")
                .contains("我把锁加好了");
    }

    @Test
    @DisplayName("【压缩】水位线之前的事件不再投影，由一条摘要 user 消息代表")
    void compactionDropsEarlierEventsAndInjectsSummary() {
        append(new UserMessage("第一句"));
        append(new AssistantMessage("第一次回答", null));
        append(new UserMessage("第二句"));
        append(new ContextCompacted(3, "用户先问了一句，我答了一句"));
        append(new UserMessage("第三句"));

        List<LlmMessage> messages = assemble();

        // system、user(摘要)、user(第三句)；此前内容由摘要代表。
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).role()).isEqualTo(LlmRole.USER);
        assertThat(messages.get(1).content()).contains("摘要").contains("我答了一句");
        assertThat(messages.get(2).content()).isEqualTo(said("第三句"));
    }

    @Test
    @DisplayName("【压缩】压过两次时取最后一条 —— 前一次的摘要已经被后一次总结进去了")
    void theLastCompactionWins() {
        append(new UserMessage("早期的话"));
        append(new ContextCompacted(1, "第一次的摘要内容"));
        append(new UserMessage("中期的话"));
        append(new ContextCompacted(3, "第二次的摘要，已经涵盖了前面全部"));
        append(new UserMessage("最新的话"));

        List<LlmMessage> messages = assemble();

        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).content()).contains("第二次的摘要");
        // 使用第一条水位线会让已被覆盖的中期内容重新出现。
        assertThat(messages.get(1).content()).doesNotContain("第一次的摘要内容");
        assertThat(messages.get(2).content()).isEqualTo(said("最新的话"));
    }

    @Test
    @DisplayName("【回滚】退到第一次交互结束：第一轮留住，只丢第二轮之后")
    void rewindKeepsTheConversationUpToTheCheckpoint() {
        append(new CheckpointCreated("base", 0));           // 会话刚开始（第 0 轮之前）
        append(new UserMessage("第一轮"));
        append(new AssistantMessage("我改了 A.java", null));
        long afterFirst = append(new CheckpointCreated("sha1", 0));   // 第 0 轮结束
        append(new UserMessage("第二轮"));
        append(new AssistantMessage("我又改了 B.java", null));
        append(new CheckpointCreated("sha2", 1));           // 第 1 轮结束
        append(new UserMessage("第三轮"));
        append(new AssistantMessage("我改了 C.java", null));

        // 退到"第 0 轮结束"那个点
        append(new SessionRewound("sha1", afterFirst, UserId.of("u-li")));

        List<LlmMessage> messages = assemble();

        // 保留系统提示词与第一轮；第二、三轮被截掉。整段清空会让模型忘记仍留在磁盘上的改动。
        assertThat(messages).extracting(LlmMessage::content)
                .containsExactly(SYSTEM, said("第一轮"), "我改了 A.java");
    }

    @Test
    @DisplayName("【回滚】退到会话最开始：什么都不留，只剩系统提示词")
    void rewindToTheVeryBeginningKeepsNothing() {
        long veryBeginning = append(new CheckpointCreated("base", 0));
        append(new UserMessage("第一轮"));
        append(new AssistantMessage("改了 A.java", null));
        append(new CheckpointCreated("sha1", 0));
        append(new UserMessage("第二轮"));
        append(new AssistantMessage("改了 B.java", null));

        // base 记在第一条消息之前，因此退到它等于撤销全部对话。
        append(new SessionRewound("base", veryBeginning, UserId.of("u-li")));

        assertThat(assemble()).singleElement()
                .satisfies(m -> assertThat(m.role()).isEqualTo(LlmRole.SYSTEM));
    }

    @Test
    @DisplayName("【回滚】两条 checkpoint 撞同一个 sha（那一轮什么都没改）→ 退到**你选的那条**")
    void rewindGoesToTheChosenBoundaryWhenShasCollide() {
        append(new CheckpointCreated("base", 0));                // 会话开始
        append(new UserMessage("第一轮"));
        append(new AssistantMessage("答一", null));
        // 第一轮未改文件，commit 返回上一个 HEAD，两条 checkpoint sha 相同。
        long afterFirst = append(new CheckpointCreated("base", 1));
        append(new UserMessage("第二轮"));
        append(new AssistantMessage("答二", null));
        append(new CheckpointCreated("changed", 2));

        // 用户选择第一轮结束点，它与会话开始那条同 sha。
        append(new SessionRewound("base", afterFirst, UserId.of("u-li")));

        // 按 sha 取最早匹配会退到会话开始；按 seq 才能退到用户选择的位置。
        assertThat(assemble()).extracting(LlmMessage::content)
                .containsExactly(SYSTEM, said("第一轮"), "答一");
    }

    @Test
    @DisplayName("【回滚】连序号都没有 → 保守地清空整段，而不是拿 sha 猜一条")
    void rewindWithoutASeqClearsEverythingInsteadOfGuessing() {
        append(new CheckpointCreated("base", 0));
        append(new UserMessage("第一轮"));
        append(new AssistantMessage("答一", null));
        append(new CheckpointCreated("base", 1));
        append(new UserMessage("第二轮"));
        append(new AssistantMessage("答二", null));
        append(new CheckpointCreated("changed", 2));

        // toCheckpointSeq = null。
        append(new SessionRewound("base", null, UserId.of("u-li")));

        // sha 在这条会话里出现过两次，无法定位；事件没记录目标时退到最保守位置。
        assertThat(assemble()).extracting(LlmMessage::content)
                .containsExactly(SYSTEM);
    }

    @Test
    @DisplayName("【回滚·兜底】边界被压缩挡在水位线之前 → 保守地清空整段对话（摘要也清）")
    void rewindToAnUnlocatableCheckpointClearsEverything() {
        append(new UserMessage("早期"));
        append(new ContextCompacted(1, "早前聊过什么"));
        append(new UserMessage("后来"));
        append(new AssistantMessage("后来我改了 A.java", null));
        // checkpoint 在水位线之前，未遍历到，无法定位消息下标；用流中不存在的 seq 表示。
        append(new SessionRewound("sha0", 999L, UserId.of("u-li")));

        List<LlmMessage> messages = assemble();

        // 查不到边界时退到 conversationStart，连摘要一起清掉；多退胜过保留已作废的摘要。
        assertThat(messages).singleElement()
                .satisfies(m -> assertThat(m.role()).isEqualTo(LlmRole.SYSTEM));
    }

    @Test
    @DisplayName("【崩溃恢复】被中断的调用注入成事实，并明确要求先确认文件状态而不是直接重试")
    void interruptedCallIsInjectedAsAFact() {
        append(new UserMessage("改一下"));
        append(new ToolCallRequested("c1", "edit_file", "{}"));
        append(new ToolInterrupted("c1"));

        String toolContent = assemble().get(3).content();

        assertThat(toolContent)
                .contains("没有完成")
                .contains("不要直接重试")
                .contains("read_file");
    }

    @Test
    @DisplayName("被取消的调用同样注入成事实")
    void cancelledCallIsInjectedAsAFact() {
        append(new UserMessage("跑测试"));
        append(new ToolCallRequested("c1", "run_command", "{}"));
        append(new ToolCancelled("c1"));

        assertThat(assemble().get(3).content()).contains("被用户取消了，没有完成");
    }

    @Test
    @DisplayName("【平台指令】投影成带来源标记的消息，而不是伪装成用户发言")
    void platformInstructionIsMarkedAsSuch() {
        append(new UserMessage("改一下"));
        append(new PlatformInstruction("你声明完成后，平台自动验证失败，请修正", "verification-failed"));

        LlmMessage instruction = assemble().get(2);

        // 模型需要按指令行动，因此占 user 角色；正文带来源标记，事件类型独立。
        assertThat(instruction.role()).isEqualTo(LlmRole.USER);
        assertThat(instruction.content())
                .startsWith("[平台指令]")
                .contains("平台自动验证失败");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【回滚】代码 reset 了，对话也必须跟着截断 —— 否则模型对着一个不存在的现状说话")
    void rewindTruncatesTheConversation() {
        append(new UserMessage("改一下 A.java"));
        append(new AssistantMessage("改好了", null));
        append(new SessionRewound("c0ffee", 999L, UserId.of("u-li")));
        append(new UserMessage("这次换个做法"));

        List<LlmMessage> messages = assemble();

        // 保留系统提示词与回滚后的消息。
        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst().role()).isEqualTo(LlmRole.SYSTEM);
        // 系统提示词不属于对话，清掉会让后续调用失去缓存前缀。
        assertThat(messages.getFirst().content()).isEqualTo(SYSTEM);
        assertThat(messages.get(1).content()).isEqualTo(said("这次换个做法"));
        // 被回滚掉的那些轮次一个字都不该留下
        assertThat(messages).noneMatch(m -> "改好了".equals(m.content()));
        assertThat(messages).noneMatch(m -> "改一下 A.java".equals(m.content()));
    }

    // ------------------------------------------------------------------
    // 任务清单
    // ------------------------------------------------------------------

    private static TodoListUpdated todo(String... contents) {
        List<TodoListUpdated.Item> items = new ArrayList<>();
        for (String content : contents) {
            items.add(new TodoListUpdated.Item(content, TodoListUpdated.State.PENDING));
        }
        return new TodoListUpdated(items);
    }

    @Test
    @DisplayName("【清单】写完清单，它作为**最后一条**消息出现在模型眼前")
    void theTodoListRidesAtTheTail() {
        append(new UserMessage("把这几件事做了"));
        append(new TodoListUpdated(List.of(
                new TodoListUpdated.Item("改三个文件", TodoListUpdated.State.IN_PROGRESS),
                new TodoListUpdated.Item("跑测试", TodoListUpdated.State.PENDING))));

        List<LlmMessage> messages = assemble();

        // 状态沿用工具参数中的原词，写作与读回之间不翻译。
        assertThat(messages.getLast().role()).isEqualTo(LlmRole.USER);
        assertThat(messages.getLast().content())
                .startsWith("[当前任务清单")
                .contains("1. [in_progress] 改三个文件")
                .contains("2. [pending] 跑测试");
    }

    @Test
    @DisplayName("【清单】再写一次是**追加一份快照**：旧的不动（缓存），最新的在最后")
    void aSecondWriteAppendsASnapshot() {
        append(todo("第一步"));
        append(new UserMessage("继续"));
        append(new TodoListUpdated(List.of(
                new TodoListUpdated.Item("第二步", TodoListUpdated.State.IN_PROGRESS))));

        List<LlmMessage> messages = assemble();

        // 旧快照保留且不再变化，新快照追加在最后。
        assertThat(messages).filteredOn(m -> m.content().contains("当前任务清单")).hasSize(2);
        assertThat(messages).filteredOn(m -> m.content().contains("当前任务清单"))
                .extracting(LlmMessage::content)
                .anySatisfy(content -> assertThat(content).contains("第一步"));
        assertThat(messages.getLast().content()).contains("第二步").doesNotContain("第一步");
    }

    @Test
    @DisplayName("【清单·关键】压缩之后它**还在** —— 这是它写成事件而不是工具结果的全部理由")
    void theTodoListSurvivesCompaction() {
        append(new UserMessage("把这几件事做了"));
        append(todo("跑测试"));
        // 水位线落在该事件上，此前内容不再投影。
        long cutoff = append(new AssistantMessage("好，我开始了", null));
        append(new ContextCompacted(cutoff, "前面聊过要做这几件事"));
        append(new UserMessage("继续"));

        List<LlmMessage> messages = assemble();

        // 水位线之前的内容由摘要代表。
        assertThat(messages).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("前面聊过要做这几件事"));
        assertThat(messages).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("好，我开始了"));
        // 清单快照不被摘要替换；工具结果则可能被清理，丢失计划会让模型忘记进度。
        assertThat(messages).extracting(LlmMessage::content)
                .anySatisfy(content -> assertThat(content)
                        .startsWith("[当前任务清单")
                        .contains("跑测试"));
    }

    @Test
    @DisplayName("【清单·压缩】水位线之前只带最新一份，旧的由摘要代表 —— 不把整摞旧快照搬过来")
    void compactionKeepsOnlyTheLatestTodoSnapshot() {
        append(new UserMessage("开始"));
        append(todo("第一步"));
        append(todo("第二步"));
        long cutoff = append(new AssistantMessage("好", null));
        append(new ContextCompacted(cutoff, "前面聊过要做一些事"));
        append(new UserMessage("继续"));

        List<LlmMessage> messages = assemble();

        assertThat(messages).extracting(LlmMessage::content)
                .anySatisfy(content -> assertThat(content)
                        .startsWith("[当前任务清单")
                        .contains("第二步"));
        assertThat(messages).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("第一步"));
    }

    @Test
    @DisplayName("【清单】回滚退到写清单之前 → 清单跟着退回去")
    void aRewindTakesTheTodoListWithIt() {
        long beforeAnything = append(new CheckpointCreated("sha0", 0));
        append(new UserMessage("做点事"));
        append(todo("跑测试"));
        append(new AssistantMessage("改好了", null));

        append(new SessionRewound("sha0", beforeAnything, UserId.of("u-li")));

        // 退到写清单之前，清单快照必须一并作废；判据与对话截断相同。
        assertThat(assemble()).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("当前任务清单"));
    }

    @Test
    @DisplayName("【清单】空清单 = 追加一条「已经清空」，旧快照不会继续冒充当前")
    void anEmptyListAppendsAClearedSnapshot() {
        append(todo("第一步"));
        // 全部完成后写入空清单，而不是保留一份全完成项。
        append(new TodoListUpdated(List.of()));

        List<LlmMessage> messages = assemble();
        assertThat(messages).filteredOn(m -> m.content().contains("当前任务清单")).hasSize(2);
        assertThat(messages.getLast().content())
                .startsWith("[当前任务清单")
                .contains("已经清空");
    }

    // ------------------------------------------------------------------
    // 可推进的投影
    // ------------------------------------------------------------------

    /** 分多次喂给同一条投影；最终结果必须与一次喂完逐字符相同。 */
    private List<LlmMessage> inSteps(int... cuts) {
        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        for (int cut : cuts) {
            projection.advance(events.subList(0, cut), sink);
        }
        return List.copyOf(sink);
    }

    @Test
    @DisplayName("【推进】分几次喂 ≡ 一次喂完 —— 而且专挑「调用还没回结果」那一刻切")
    void advancingInStepsMatchesAOneShotAssembly() {
        append(new UserMessage("改一下"));
        append(new ToolCallRequested("c1", "edit_file", "{}"));
        append(new ToolApprovalResolved("c1", true, UserId.of("root"), null));
        append(new ToolResult("c1", true, "改好了", false, null, 5));
        append(new AssistantMessage("改完了", null));
        append(new UserMessage("再改一处"));

        // 切在请求已落库、结果未回的位置：投影会先合成结果，之后由真实结果原地替换。
        assertThat(inSteps(2, 4, 6)).isEqualTo(assemble());
        // 最终不应残留合成结果。
        assertThat(inSteps(2, 4, 6)).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("没有留下结果"));
    }

    @Test
    @DisplayName("【推进】同一批再喂一次，结果一模一样 —— 推进是幂等的")
    void advancingTwiceWithNothingNewChangesNothing() {
        append(new UserMessage("说句话"));

        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        projection.advance(events, sink);
        List<LlmMessage> once = List.copyOf(sink);
        projection.advance(events, sink);

        assertThat(sink).isEqualTo(once);
    }

    @Test
    @DisplayName("【推进】中途来了一次压缩 → 推倒重来，结果仍与一次喂完相同")
    void compactionForcesARebuild() {
        append(new UserMessage("第一句"));
        append(new AssistantMessage("答一", null));
        long cutoff = append(new AssistantMessage("答二", null));
        append(new UserMessage("第二句"));
        append(new AssistantMessage("答三", null));

        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        // 先推进前两条，此时尚无压缩。
        projection.advance(events.subList(0, 2), sink);
        // 压缩随后到达，替换整段前缀，必须从头重建。
        append(new ContextCompacted(cutoff, "前面聊过这些"));
        projection.advance(events, sink);

        assertThat(viewOf(projection, sink)).isEqualTo(fedInOneGo());
        assertThat(sink).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("前面聊过这些"));
    }

    @Test
    @DisplayName("【分页重建】重建时 begin 拿到整条流 —— 前一页的压缩摘要不会丢")
    void rebuildingFromAPageStillSeesEarlierCompaction() {
        append(new UserMessage("第一句"));
        append(new AssistantMessage("答一", null));
        long cutoff = append(new AssistantMessage("答二", null));
        append(new ContextCompacted(cutoff, "前面聊过这些"));
        append(new UserMessage("第二句"));

        // 生产里的 supplier 每次返回最新整条流；这里模拟分页递进来的第二片
        ContextAssembler.Projection projection = assembler.projection(SYSTEM, () -> events);
        List<LlmMessage> sink = new ArrayList<>();
        projection.advance(events.subList(0, 4), sink);
        assertThat(sink).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("前面聊过这些"));

        append(new ToolResultsCleared(List.of("c9")));
        projection.advance(events.subList(4, 6), sink);

        // 压缩摘要在第一页，重建时只拿第二页会把它丢掉；必须重新扫整条流
        assertThat(sink).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("前面聊过这些"));
    }

    @Test
    @DisplayName("【推进】中途清了一次旧工具结果 → 推倒重来（正文要**回头**换掉）")
    void clearingToolResultsForcesARebuild() {
        append(new UserMessage("读一下"));
        append(new ToolCallRequested("c1", "read_file", "{}"));
        append(new ToolResult("c1", true, "很长的一大段文件内容", false, null, 2));
        append(new AssistantMessage("读完了", null));

        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        projection.advance(events.subList(0, 3), sink);
        append(new ToolResultsCleared(List.of("c1")));
        projection.advance(events, sink);

        assertThat(viewOf(projection, sink)).isEqualTo(fedInOneGo());
        assertThat(sink).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("很长的一大段文件内容"));
    }

    @Test
    @DisplayName("【推进】中途回滚了一次 → 推倒重来（尾部要砍掉）")
    void aRewindForcesARebuild() {
        long base = append(new CheckpointCreated("base", 0));
        append(new UserMessage("第一句"));
        append(new AssistantMessage("答一", null));
        append(new UserMessage("第二句"));

        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        projection.advance(events.subList(0, 3), sink);
        append(new SessionRewound("base", base, UserId.of("u-li")));
        projection.advance(events, sink);

        assertThat(viewOf(projection, sink)).isEqualTo(fedInOneGo());
        assertThat(sink).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("第二句"));
    }

    @Test
    @DisplayName("【读过的文件·回滚】切点之后才读的**作废**，之前读的照算")
    void aRewindForgetsTheFilesReadAfterTheCheckpoint() {
        append(new CheckpointCreated("sha0", 0));
        append(new UserMessage("先读 A"));
        append(new ToolCallRequested("c1", "read_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("c1", true, "class A {}", false, 0, 5));
        long cut = append(new CheckpointCreated("sha1", 1));
        append(new UserMessage("再读 B"));
        append(new ToolCallRequested("c2", "read_file", "{\"path\":\"B.java\"}"));
        append(new ToolResult("c2", true, "class B {}", false, 0, 5));

        // 先推进到回滚之前，两次读取都已进入投影。
        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        projection.advance(events.subList(0, 8), sink);
        assertThat(projection.readPaths()).contains("A.java", "B.java");

        append(new SessionRewound("sha0", cut, UserId.of("u-li")));
        projection.advance(events, sink);

        // 回滚同时退回代码：切点前的读取仍成立，切点后的内容已不存在，必须作废。
        assertThat(projection.readPaths()).contains("A.java").doesNotContain("B.java");
    }

    @Test
    @DisplayName("【清单·热路径】先推进一段、再回滚 → 清单照样退回去（重折不会把旧账再记一遍）")
    void aRewindOnTheHotPathAlsoTakesTheTodoListWithIt() {
        long base = append(new CheckpointCreated("sha0", 0));
        append(new UserMessage("做点事"));
        append(todo("跑测试"));
        append(new AssistantMessage("写完了", null));

        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        // 先推进到回滚之前，清单已经投出一次。
        projection.advance(events, sink);
        assertThat(sink).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("跑测试"));

        append(new SessionRewound("sha0", base, UserId.of("u-li")));
        projection.advance(events, sink);

        // 生产使用推进路径；回滚时投影已折过此前事件，会走清空重建。
        assertThat(sink).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("当前任务清单"));
    }

    @Test
    @DisplayName("【回滚·孤儿】请求已经被砍掉的调用，不该再补一条「没有结果」")
    void aRewindLeavesNoOrphanToolMessage() {
        long base = append(new CheckpointCreated("sha0", 0));
        append(new UserMessage("跑一下"));
        append(new ToolCallRequested("c1", "run_command", "{\"command\":\"pwd\"}"));
        append(new ToolApprovalRequested("c1", "这条要问你"));
        append(new SessionRewound("sha0", base, UserId.of("u-li")));

        // 请求已被回滚砍掉，不能再补结果；否则会出现没有 tool_calls 前置的 tool 消息。
        assertThat(assemble()).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("没有留下结果"));
    }

    @Test
    @DisplayName("【回滚·下标】回滚之后再答复同一个调用 id，不会戳到被砍掉的那条消息上")
    void aRewindInvalidatesThePositionsItCutAway() {
        append(new CheckpointCreated("sha0", 0));
        append(new UserMessage("先跑一次"));
        append(new ToolCallRequested("call_1", "run_command", "{\"command\":\"ls\"}"));
        append(new ToolResult("call_1", true, "a.java", false, 0, 4));
        append(new SessionRewound("sha0", 1L, UserId.of("u-li")));
        // 新的时间线上，模型又用了同一个 id（它是模型生成的，重复是可能的）
        append(new UserMessage("再跑一次"));
        append(new ToolCallRequested("call_1", "run_command", "{\"command\":\"pwd\"}"));
        append(new ToolResult("call_1", true, "C:/x", false, 0, 5));

        // 记录的位置若不作废，答复会覆盖已不在对话里的消息或无关内容。
        assertThat(assemble()).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("C:/x"))
                .noneMatch(content -> content.contains("a.java"));
    }

    @Test
    @DisplayName("【挂起】正在等人批的那条调用，兜底说的是**真话** —— 它压根没跑过")
    void anAwaitingApprovalCallIsNotToldItWasInterrupted() {
        append(new CheckpointCreated("sha0", 0));
        append(new ToolCallRequested("c1", "run_command", "{\"command\":\"rm -rf build\"}"));
        append(new ToolApprovalRequested("c1", "这条要问你"));

        // 配对要求的兜底不能省，但必须说明它尚未执行，不能套用中断文案。
        assertThat(assemble()).extracting(LlmMessage::content)
                .anyMatch(text -> text != null && text.contains("还在等你批准"))
                .noneMatch(text -> text != null && text.contains("中断了"));
    }

    @Test
    @DisplayName("【挂起】批准之后它真的跑了，那句占位被**真结果**换掉 —— 一次调用只留一条 tool 消息")
    void theAwaitingApprovalPlaceholderIsReplacedOnceItRuns() {
        append(new CheckpointCreated("sha0", 0));
        append(new ToolCallRequested("c1", "run_command", "{\"command\":\"rm -rf build\"}"));
        append(new ToolApprovalRequested("c1", "这条要问你"));
        append(new ToolResult("c1", true, "删掉了", false, 0, 12L));

        List<LlmMessage> seen = assemble();

        assertThat(seen).extracting(LlmMessage::content)
                .anyMatch(text -> text != null && text.contains("删掉了"))
                .noneMatch(text -> text != null && text.contains("等你批准"));
        // 一个 tool_call_id 只能有一条 tool 消息。
        assertThat(seen).filteredOn(message -> message.role() == LlmRole.TOOL).hasSize(1);
    }

    @Test
    @DisplayName("【推进】清单快照只追加、不改写：分两次推进和一次推进结果相同")
    void theTodoSnapshotsAreAppendOnly() {
        append(todo("跑测试"));
        append(new UserMessage("继续"));
        append(new TodoListUpdated(List.of(
                new TodoListUpdated.Item("跑测试", TodoListUpdated.State.IN_PROGRESS))));

        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        projection.advance(events.subList(0, 1), sink);
        projection.advance(events, sink);

        // 旧快照留在前缀中，新变化追加一份。
        assertThat(sink).filteredOn(m -> m.content().contains("当前任务清单")).hasSize(2);
        assertThat(sink.getLast().content())
                .contains("当前任务清单")
                .contains("跑测试");
        assertThat(sink).isEqualTo(assemble());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【缓存前提①】确定性：同一份事件流两次投影结果完全相同")
    void projectionIsDeterministic() {
        seedSmallConversation();

        assertThat(assemble()).isEqualTo(assembler.assemble(events, SYSTEM));
    }

    @Test
    @DisplayName("【缓存前提②】只追加不改前缀：新增事件后，原有消息逐条不变")
    void appendingEventsKeepsThePrefixStable() {
        seedSmallConversation();
        List<LlmMessage> before = assemble();

        append(new UserMessage("再说一句"));
        append(new AssistantMessage("好的", null));
        List<LlmMessage> after = assemble();

        // 前缀缓存按前缀匹配；前缀改动会导致整段重新计费。
        assertThat(after.subList(0, before.size())).isEqualTo(before);
        assertThat(after).hasSize(before.size() + 2);
    }

    private void seedSmallConversation() {
        append(new UserMessage("你好"));
        append(new AssistantMessage("你好，有什么可以帮你的", null));
        append(new UserMessage("看一下 A.java"));
        append(new ToolCallRequested("c1", "read_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("c1", true, "class A {}", false, 0, 4));
        append(new AssistantMessage("它只有一个空类", null));
    }
}
