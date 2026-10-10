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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    private static final String SYSTEM = "你是一个协作开发助手。";
    private static final SessionId SESSION = SessionId.of("s1");

    /**
     * 装配器。**"按 id 查用户名"那个办法由这里给** —— 投影里只有一处用它：
     * 别人捎来的留言要标明来源（见 {@code ContextAssembler}）。
     *
     * <p>名字是**读的时候查**的，事件里只有 id：用户名会变，所以它不能当身份
     *（见 {@code AgentNoteDelivered}）。
     */
    private final ContextAssembler assembler =
            new ContextAssembler(id -> id.equals(UserId.of("u-li")) ? "小李" : "某人");
    private final List<StoredEvent> events = new ArrayList<>();

    /** 追加一条事件，**返回它的 seq** —— 回滚要靠序号指认"退到哪一条"，见 {@link SessionRewound}。 */
    private long append(Event event) {
        long seq = events.size() + 1L;
        events.add(new StoredEvent(SESSION, seq, Instant.parse("2026-09-25T10:00:00Z"), event));
        return seq;
    }

    private List<LlmMessage> assemble() {
        return assembler.assemble(events, SYSTEM);
    }

    /**
     * 投影**对外看得见**的全部东西。
     *
     * <p>为什么不止消息：那几份派生事实也是投影的产出。只比消息的话，"推倒重来时漏清了一份"
     * 这种错**不会红** —— 而它正是这套代码里最容易出、也最难发现的一种（漏掉不报错，
     * 那份事实只是带着被退掉的那段时间的痕迹活下来）。
     */
    private record ProjectionView(List<LlmMessage> messages, Set<String> readPaths,
                                  Optional<ToolCall> pendingApprovedCall) {
    }

    /** 一条投影现在对外是什么样。 */
    private static ProjectionView viewOf(ContextAssembler.Projection projection,
                                         List<LlmMessage> sink) {
        return new ProjectionView(List.copyOf(sink), projection.readPaths(),
                projection.pendingApprovedCall());
    }

    /** 「整条流一次喂完」那一份 —— 等价断言的对照。 */
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
        // 这一轮是 flash 说的，它想了点什么
        append(new AssistantMessage("看完了", "deepseek-flash", "先读哪个文件"));
        append(new UserMessage("换个模型接着说"));
        // 换到 pro 之后这一轮
        append(new AssistantMessage("接着说", "deepseek-pro", "这次换个路子"));

        List<LlmMessage> messages = assemble();

        // 两份思考各自标着**谁产的**。少了这个，wire 那一层就没有判据 ——
        // 它只能看到"有思考"，看不见"这不是这个模型的思考"，于是会原样发出去
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
        // 模型只调工具、一个字不说，而它的思考是有的 —— 这是推理模型在思维链模式下的常态。
        // 装配时若把思考丢了，而协议要求这条消息必须把它带回去，请求就是 400。
        append(new UserMessage("读一下 A"));
        // 三参那个（text, model, reasoning）—— 两参的是 (text, model)，别写错
        append(new AssistantMessage("", "deepseek-reasoner", "我在想先看哪个文件"));
        append(new ToolCallRequested("call_1", "read_file", "{\"path\":\"A.java\"}"));
        append(new ToolResult("call_1", true, "class A {}", false, 0, 5));
        append(new AssistantMessage("看完了", "deepseek-reasoner", "没什么特别的"));

        List<LlmMessage> messages = assemble();

        LlmMessage toolTurn = messages.get(2);
        assertThat(toolTurn.role()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(toolTurn.toolCalls()).hasSize(1);
        assertThat(toolTurn.reasoning()).isEqualTo("我在想先看哪个文件");

        // 结论那条也带着 —— 它虽然不带工具调用，但下一轮同样要发出去
        assertThat(messages.get(4).reasoning()).isEqualTo("没什么特别的");
    }

    @Test
    @DisplayName("【思考回传·分批发工具】一次回复被投影成两条 assistant 消息，两条都要带思考")
    void oneResponseSplitIntoTwoMessagesKeepsReasoningOnBoth() {
        // 真实事故的形状（照着 event 表里的顺序写的）：
        // 模型**一次回复**里发了两个工具调用，而执行时按批切开 ——
        // 只读的 glob 一批、要执行的 run_command 一批。
        // 于是两条 ToolCallRequested 在事件流里**不挨着**：
        // 中间隔着 glob 的结果，而那一步会 flush 掉第一条 assistant 消息。
        append(new UserMessage("写个 helloworld"));
        append(new AssistantMessage("", "deepseek-reasoner", "先看看目录里有什么"));
        append(new ToolCallRequested("call_A", "glob", "{\"pattern\":\"**/*.class\"}"));
        append(new ToolResult("call_A", true, "没有匹配", false, 0, 1));
        // ★ 同一回复的第二个批次。这一步落库时，上面那条已经 flush 过了
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

        // 两条都带同一个思考。第二条恰好是带 tool_calls 的那条 —— 它一旦缺了思考，
        // 下一次请求就被 DeepSeek 打回 400：
        // 「The `reasoning_content` in the thinking mode must be passed back to the API.」
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
        // ★ 用户批准之后，那次调用真的被执行了，结果落下来
        append(new ToolResult("call_1", true, "javac 21.0.1", false, 0, 5));

        List<LlmMessage> messages = assemble();

        // system, user, assistant([call_1]), tool(call_1) —— **只有一条 tool**
        assertThat(messages).hasSize(4);
        LlmMessage answered = messages.get(3);
        assertThat(answered.role()).isEqualTo(LlmRole.TOOL);
        assertThat(answered.toolCallId()).isEqualTo("call_1");
        assertThat(answered.content()).contains("javac 21.0.1");
        assertThat(answered.content()).doesNotContain("批准");

        // 两条 tool 消息回答同一个 tool_call_id 的话，下一次请求会当场 400：
        // 「Messages with role 'tool' must be a response to a preceding message with 'tool_calls'」
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
        // 用户只点了"拒绝"，一个字都没留 —— 界面上本来也没有留字的地方
        append(new ToolApprovalResolved("call_1", false, UserId.of("root"), null));

        List<LlmMessage> messages = assemble();

        assertThat(messages).hasSize(4);
        assertThat(messages.get(3).content())
                .contains("用户拒绝了这次调用")
                .contains("停下");
        // ★ reason 为 null 时不能把**字面量 null**拼进句子（"…：null。请换一种做法……"）。
        //   其他用例都老老实实给了理由，只有应用里不给 —— 所以这条得盯住它
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
     * 每个 {@code tool_call_id} 被回答了几次 —— 同一个 id 出现两次就是那份请求会 400 的形状。
     * 只看 {@code role="tool"} 的消息：别的消息上这个字段是 null，混进来会得到一堆假重复。
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

        // null 和空串在 wire 那层的下场完全不同：null → 不发那个字段；
        // 空串 → 发出去，而服务商拒收空串
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

        // system, user, assistant(c1), tool, assistant(c2), tool, assistant
        // 合并不了：OpenAI 的格式要求 tool 结果必须紧跟带 tool_calls 的那条 assistant 消息，
        // 中间隔着别人的结果就没法并成一条
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
        // 这个工具跑了很久，而这一轮在它执行期间就中断了（回调抛异常、进程被杀），
        // 结果永远不会来。事件流里于是只剩「请求了」—— 直接投影出去，
        // 就是一条没有配对 tool 消息的 assistant(tool_calls)，API 会拒

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
        // c2 执行到一半这一轮就断了，没有结果

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

        // 结构一个字没变：清的是正文，不是那条消息。变了的话配对就断了，
        // 而配对断了 API 直接拒
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
        // 它是**展示**用途。各家对"回不回传"的要求是相反的（OpenAI 兼容阵营说不要回传，
        // Anthropic 的 thinking block 带签名、必须回传），所以"回不回传"由 provider
        // 适配层决定，而这一层——投影——**统一不带**
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

        // system, user(摘要), user(第三句) —— 前三件事全被那条摘要代表了
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).role()).isEqualTo(LlmRole.USER);
        assertThat(messages.get(1).content()).contains("摘要").contains("我答了一句");
        assertThat(messages.get(2).content()).isEqualTo("第三句");
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
        // 用第一条水位线的话，中期那句话会漏出来 —— 而它早该被后一次压缩覆盖掉
        assertThat(messages.get(1).content()).doesNotContain("第一次的摘要内容");
        assertThat(messages.get(2).content()).isEqualTo("最新的话");
    }

    @Test
    @DisplayName("【回滚】退到第 1 轮结束：第 0～1 轮**全部留住**，只丢第 2 轮之后")
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

        // 系统 + 第一轮的两条。**第二、三轮没了**——它们的代码确实退回去了。
        //
        // 这一条是这个投影最要紧的性质：若整段清空，第 0 轮聊过什么模型也一并忘了，
        // 而那一轮的代码还留在盘上 —— 模型面对自己刚写的东西却没有上下文。
        // Claude Code 的 /rewind 就是把这些留住的
        assertThat(messages).extracting(LlmMessage::content)
                .containsExactly(SYSTEM, "第一轮", "我改了 A.java");
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

        // base 那条记在第 0 条消息之前（那时 messages 里只有系统提示词），
        // 所以它是"撤销全部改动"，退到那儿对话一条不剩
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
        // 第一轮只说了话、一个字都没改文件 —— 于是 commit 返回的还是上一个 HEAD，
        // 两条 checkpoint 的 sha 一模一样
        long afterFirst = append(new CheckpointCreated("base", 1));
        append(new UserMessage("第二轮"));
        append(new AssistantMessage("答二", null));
        append(new CheckpointCreated("changed", 2));

        // 用户选的是"退到第二轮之前" = 第一轮结束那个点。它和会话开始那条同 sha
        append(new SessionRewound("base", afterFirst, UserId.of("u-li")));

        // 按 sha 取**最早**那条匹配会退到"会话刚开始"，把用户选的第一轮之后的
        // 东西**也一起清掉**。认序号才退得到他指的那一条：第一轮的两条都留着
        assertThat(assemble()).extracting(LlmMessage::content)
                .containsExactly(SYSTEM, "第一轮", "答一");
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

        // toCheckpointSeq = null
        append(new SessionRewound("base", null, UserId.of("u-li")));

        // 拿 sha 去猜的话这里会退到"会话刚开始"那条 —— 而这个 sha 在这条会话里出现过两次，
        // 猜哪条都是猜。既然事件自己没记下退到哪儿，就退到最保守的位置
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
        // 这条 checkpoint 落在水位线之前 —— 它那条 CheckpointCreated 我们压根没遍历到，
        // 所以查不出"该退到第几条消息"。真实路径：先压缩、再回滚到压缩之前的某个点。
        // 这里用一个流里不存在的序号表示那种"查不到"（和 sha 在不在手上无关了）
        append(new SessionRewound("sha0", 999L, UserId.of("u-li")));

        List<LlmMessage> messages = assemble();

        // 查不到边界就退到 conversationStart —— 连摘要一起清掉。
        // 摘要描述的那些改动确实已经退回去了，留着它模型就会对着一个不存在的现状说话；
        // 而"退得比该退的多"只是少记一段，不会让它说错话
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

        // 角色上它确实占用户位（模型要按指令行动），但正文带来源标记 ——
        // 而事件流里它是独立类型，审计时不会跟真实的用户发言混起来
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

        // 只剩系统提示词 + 回滚之后那一句
        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst().role()).isEqualTo(LlmRole.SYSTEM);
        // **系统提示词要原样留着**：它不属于对话，一起清掉会让后续每一次调用都缓存未命中
        assertThat(messages.getFirst().content()).isEqualTo(SYSTEM);
        assertThat(messages.get(1).content()).isEqualTo("这次换个做法");
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

        // 状态用**模型自己的词**（和工具参数里那三个字符串一模一样）：它写进去、它读回来，
        // 中间不该经过一次翻译
        assertThat(messages.getLast().role()).isEqualTo(LlmRole.USER);
        assertThat(messages.getLast().content())
                .startsWith("[当前任务清单")
                .contains("1. [in_progress] 改三个文件")
                .contains("2. [pending] 跑测试");
    }

    @Test
    @DisplayName("【清单】再写一次是**覆盖**：模型只看见一份，历史里不会堆一摞旧清单")
    void aSecondWriteReplacesTheFirst() {
        append(todo("第一步"));
        append(new UserMessage("继续"));
        append(new TodoListUpdated(List.of(
                new TodoListUpdated.Item("第二步", TodoListUpdated.State.IN_PROGRESS))));

        List<LlmMessage> messages = assemble();

        // 每一份都留在历史里的话，几十轮之后上下文里全是过期清单 ——
        // 白烧 token，而且破坏了"前缀不变"（每一轮的旧清单区都在变）
        assertThat(messages).filteredOn(m -> m.content().contains("当前任务清单")).hasSize(1);
        assertThat(messages.getLast().content()).contains("第二步").doesNotContain("第一步");
    }

    @Test
    @DisplayName("【清单·关键】压缩之后它**还在** —— 这是它写成事件而不是工具结果的全部理由")
    void theTodoListSurvivesCompaction() {
        append(new UserMessage("把这几件事做了"));
        append(todo("跑测试"));
        // 水位线落在这条上：它**之前**的一切（含上面那份清单）都不再投影给模型
        long cutoff = append(new AssistantMessage("好，我开始了", null));
        append(new ContextCompacted(cutoff, "前面聊过要做这几件事"));
        append(new UserMessage("继续"));

        List<LlmMessage> messages = assemble();

        // 水位线之前的东西由摘要代表……
        assertThat(messages).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("前面聊过要做这几件事"));
        assertThat(messages).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("好，我开始了"));
        // ……但清单**不在**被替换的那部分里：它照旧摆在最后。
        // 工具结果就做不到这一点（它会被 ToolResultsCleared 清掉），而计划丢了，
        // 模型就会忘了自己做到第几步 —— 这是长任务里最贵的一种失忆
        assertThat(messages.getLast().content())
                .startsWith("[当前任务清单")
                .contains("跑测试");
    }

    @Test
    @DisplayName("【清单】回滚退到写清单之前 → 清单跟着退回去")
    void aRewindTakesTheTodoListWithIt() {
        long beforeAnything = append(new CheckpointCreated("sha0", 0));
        append(new UserMessage("做点事"));
        append(todo("跑测试"));
        append(new AssistantMessage("改好了", null));

        append(new SessionRewound("sha0", beforeAnything, UserId.of("u-li")));

        // 退到"还没写清单"那一刻，模型不该看见一个**还没发生**的计划 ——
        // 判据和对话那边一样：序号大于切点的那几份作废
        assertThat(assemble()).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("当前任务清单"));
    }

    @Test
    @DisplayName("【清单】空清单 = 清空，不再注入那一块")
    void anEmptyListInjectsNothing() {
        append(todo("第一步"));
        // 模型把整件事做完了，于是把清单清空（全做完时写一份空清单，
        // 而不是留着一份全打勾的）
        append(new TodoListUpdated(List.of()));

        assertThat(assemble()).extracting(LlmMessage::content)
                .noneMatch(content -> content.contains("当前任务清单"));
    }

    // ------------------------------------------------------------------
    // 可推进的投影
    // ------------------------------------------------------------------

    /** 分几次喂给**同一条**投影；最后一次的结果必须和"一次喂完"逐字符相同。 */
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

        // 切在"请求已落库、结果还没回来"的中间：那一步的投影会**自己合成**一条
        // "这次调用没有留下结果"，而真结果到了之后要**原地换掉**它 ——
        // 增量投影最容易在这里走样（合成的那条落在哪、换的是哪一条）
        assertThat(inSteps(2, 4, 6)).isEqualTo(assemble());
        // 而且合成的那条最后**不在了**：真结果把它换掉了
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
        // 前两条先推进一遍（这时还没有压缩）
        projection.advance(events.subList(0, 2), sink);
        // 压缩是**后来**才到的：它把前面一整段换成摘要 —— 已经投出去的前缀整段作废，
        // 增量折不出来，只能从头再来
        append(new ContextCompacted(cutoff, "前面聊过这些"));
        projection.advance(events, sink);

        assertThat(viewOf(projection, sink)).isEqualTo(fedInOneGo());
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

        // 热路径：先推进到回滚**之前**（两次读都进去了），回滚是后来才到的
        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        projection.advance(events.subList(0, 8), sink);
        assertThat(projection.readPaths()).contains("A.java", "B.java");

        append(new SessionRewound("sha0", cut, UserId.of("u-li")));
        projection.advance(events, sink);

        // 回滚把**代码**也退回去了（同一棵树）：切点之前读到的那份内容今天依然成立，
        // 切点之后看到的那份代码已经不存在了 —— 该作废的正是那一段。
        // 留下来的话，"读过才许覆盖"那道门会让模型覆盖一个它这条时间线上没读过的文件
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
        // 先推进到回滚之前 —— 清单这时候已经投出去一次了
        projection.advance(events, sink);
        assertThat(sink).extracting(LlmMessage::content)
                .anyMatch(content -> content.contains("跑测试"));

        append(new SessionRewound("sha0", base, UserId.of("u-li")));
        projection.advance(events, sink);

        // 为什么这一条要单独有（上面那条"回滚退到写清单之前"是一次喂完的）：
        // 生产上跑的是**推进**这条（见 SessionProjections），而回滚进来时这条投影
        // 已经把前面那些事件折过一遍了 —— 于是它走的是"清了重建"那条路，而不是从头折
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

        // 那条调用请求了、一直没有结局，收尾时本来会给它补一条"没有结果"。可它的请求
        // 已经被这次回滚砍掉了 —— 补出来就是一条**前面没有 tool_calls 的 tool 消息**，
        // 下一次发给模型直接 400（"必须是对前面那条 tool_calls 的回应"）
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

        // 记着的位置要是不作废，这一次答复会原地替换到一条**已经不在对话里**的消息上：
        // 越界就抛出去，下标还在范围内就是**静默覆盖别人的内容**
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

        // 那条 tool_calls 必须配一条 tool 消息（配对是 API 的硬要求），所以兜底**不能省**；
        // 但说的得是真话：它没执行过，所以"不要直接重试、先确认文件现状"那句是反的
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
        // 一个 tool_call_id 只能配一条 tool 消息，两条就是下一次请求 400
        assertThat(seen).filteredOn(message -> message.role() == LlmRole.TOOL).hasSize(1);
    }

    @Test
    @DisplayName("【推进】清单在末尾只出现一份 —— 推进几次都不会越堆越多")
    void theTodoBlockIsNotAccumulated() {
        append(todo("跑测试"));
        append(new UserMessage("继续"));
        append(new TodoListUpdated(List.of(
                new TodoListUpdated.Item("跑测试", TodoListUpdated.State.IN_PROGRESS))));

        ContextAssembler.Projection projection = assembler.projection(SYSTEM);
        List<LlmMessage> sink = new ArrayList<>();
        projection.advance(events.subList(0, 1), sink);
        projection.advance(events, sink);

        // 清单是"当前状态"，不是"历史里的一段话"：它每一轮都重新摆在末尾，
        // 而不是每推进一次就多压一条
        assertThat(sink).filteredOn(m -> m.content().contains("当前任务清单")).hasSize(1);
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

        // 这是 prompt 缓存能命中的前提：模型服务商按前缀匹配，
        // 前缀一旦被改动，整个前缀都要按未命中价重算
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
