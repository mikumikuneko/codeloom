package com.codeloom.agent.loop;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.ChatMessage;
import com.codeloom.agent.llm.ChatRole;
import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.model.ModelCapabilities;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.llm.TokenUsage;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.agent.support.ScriptedLlm;
import com.codeloom.agent.tool.CommandApproval;
import com.codeloom.agent.tool.Tool;
import com.codeloom.agent.tool.ToolContext;
import com.codeloom.agent.tool.ToolOutcome;
import com.codeloom.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.LlmRetryScheduled;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.PlatformInstruction;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolApprovalRequested;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.TodoListUpdated;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolCancelled;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.StaleLeaseException;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 整个 agent 循环跑在**假的模型客户端**上，但工具、文件系统都是真的。
 *
 * <p>这就是"循环不碰存储"换来的：不需要数据库、不需要 Redis、不需要 API Key，
 * 就能把最容易长 bug 的那部分代码完整测出来。
 */
class AgentTurnTest {

    @TempDir
    Path worktree;

    private final SessionId sessionId = SessionId.generate();
    private final List<StoredEvent> history = new ArrayList<>();

    private static final ModelConfig MODEL = new ModelConfig(
            ProviderId.of("deepseek"), "deepseek-flash", "你是协作开发助手。");

    @BeforeEach
    void seedHistory() {
        history.add(new StoredEvent(sessionId, 1, Instant.parse("2026-09-25T10:00:00Z"),
                new UserMessage("看一下 A.java")));
        writeFile("A.java", "class A {\n    int x = 1;\n}\n");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("模型直接回答：一轮结束，只产出一条 AssistantMessage")
    void plainAnswerCompletesImmediately() {
        ScriptedLlm client = new ScriptedLlm(ScriptedLlm.answer("A 里只有一个字段 x"));
        AgentTurn turn = new AgentTurn();

        TurnOutcome outcome = turn.run(input(client));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.newEvents()).singleElement()
                .isInstanceOf(AssistantMessage.class)
                .satisfies(e -> assertThat(((AssistantMessage) e).text()).contains("一个字段 x"));
    }

    @Test
    @DisplayName("【主路径】模型要读文件 → 工具真的执行 → 结果回灌 → 模型给出结论")
    void toolCallIsExecutedAndFedBack() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("call_1", "read_file", "{\"path\":\"A.java\"}"),
                ScriptedLlm.answer("读到了，是一个空类"));
        AgentTurn turn = new AgentTurn();

        TurnOutcome outcome = turn.run(input(client));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        // 第一轮模型没说话只调工具，所以【不产出】AssistantMessage：
        // ToolCallRequested + ToolResult + AssistantMessage = 3 条
        assertThat(outcome.newEvents()).hasSize(3);
        assertThat(outcome.newEvents().get(0)).isInstanceOf(ToolCallRequested.class);

        ToolResult result = (ToolResult) outcome.newEvents().get(1);
        assertThat(result.callId()).isEqualTo("call_1");
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("class A").contains("int x = 1");   // 真读到了文件

        assertThat(outcome.newEvents().get(2)).isInstanceOf(AssistantMessage.class);

        // 第二次调用模型时，工具结果必须已经在上下文里
        List<ChatMessage> secondRequest = client.requests().get(1).messages();
        assertThat(secondRequest).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(ChatRole.TOOL);
            assertThat(m.toolCallId()).isEqualTo("call_1");
        });
    }

    @Test
    @DisplayName("【思考回传】只调工具不说话的一轮，思考必须跟着落库、并出现在下一次请求里")
    void reasoningOfAToolOnlyTurnSurvivesIntoTheNextRequest() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCallWithReasoning("先看看这个文件里有什么", "call_1", "read_file",
                        "{\"path\":\"A.java\"}"),
                ScriptedLlm.answer("读到了，是一个空类"));
        AgentTurn turn = new AgentTurn();

        TurnOutcome outcome = turn.run(input(client));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);

        // ★ 这一轮模型一个字都没说，**但它的思考要落库**：带 tool_calls 的那条 assistant
        //   消息若缺了思考，下一次请求发出去就是 400：
        //   「The `reasoning_content` in the thinking mode must be passed back to the API.」
        AssistantMessage spoken = outcome.newEvents().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("只调工具的那一轮也要落 AssistantMessage"));
        assertThat(spoken.text()).isEmpty();
        assertThat(spoken.reasoning()).isEqualTo("先看看这个文件里有什么");

        // ★ 而且它必须**真的走到发出去的请求里**。
        //   只测"落库了"是不够的 —— 事件落库和请求组装是两段代码，
        //   而这次出问题的恰恰是后一段（装配时把思考丢了）。
        List<ChatMessage> secondRequest = client.requests().get(1).messages();
        assertThat(secondRequest).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(ChatRole.ASSISTANT);
            assertThat(m.toolCalls()).hasSize(1);
            assertThat(m.reasoning()).isEqualTo("先看看这个文件里有什么");
        });
    }

    @Test
    @DisplayName("模型没想过的时候，那条 assistant 消息上不带思考（不产这个字段）")
    void turnsWithoutReasoningCarryNone() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("call_1", "read_file", "{\"path\":\"A.java\"}"),
                ScriptedLlm.answer("读到了"));
        AgentTurn turn = new AgentTurn();

        turn.run(input(client));

        // 不产思考的模型（非推理模型、或者思维链关着）这一项必须一直是 null ——
        // wire 那层靠它决定发不发 reasoning_content，发一个空串会被拒
        List<ChatMessage> secondRequest = client.requests().get(1).messages();
        assertThat(secondRequest)
                .filteredOn(m -> m.role() == ChatRole.ASSISTANT)
                .allSatisfy(m -> assertThat(m.reasoning()).isNull());
        assertThat(secondRequest)
                .filteredOn(m -> m.role() == ChatRole.ASSISTANT)
                .allSatisfy(m -> assertThat(m.toolCalls()).isNotEmpty());
    }

    @Test
    @DisplayName("【并发】一批只读调用：请求先一起落库，结果再按原顺序落库")
    void readonlyCallsShareOneBatchAndResultsKeepOrder() {
        writeFile("B.java", "class B {}\n");
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCalls(
                        ScriptedLlm.call("c1", "read_file", "{\"path\":\"A.java\"}"),
                        ScriptedLlm.call("c2", "read_file", "{\"path\":\"B.java\"}")),
                ScriptedLlm.answer("两个都读完了"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        // 两条请求**先**一起落库，然后才是两个结果 —— 而不是"请求-结果-请求-结果"。
        // 这个形状有两个后果：崩溃恢复要的"跑之前请求已经在库里"照样成立；
        // 投影时相邻的请求会被合并成一条带两个 tool_calls 的 assistant 消息。
        // 最后那一条是模型看完结果给的结论：2 请求 + 2 结果 + 1 结论 = 5
        assertThat(outcome.newEvents()).hasSize(5);
        assertThat(outcome.newEvents().get(0)).isInstanceOf(ToolCallRequested.class);
        assertThat(outcome.newEvents().get(1)).isInstanceOf(ToolCallRequested.class);
        assertThat(((ToolCallRequested) outcome.newEvents().get(0)).callId()).isEqualTo("c1");
        assertThat(((ToolCallRequested) outcome.newEvents().get(1)).callId()).isEqualTo("c2");

        // 结果是并发跑出来的，但**回灌顺序必须等于请求顺序** —— 这是并发化唯一
        // 真正危险的地方：拿错了结果，模型就会以为 A.java 里是 B 的内容
        ToolResult first = (ToolResult) outcome.newEvents().get(2);
        ToolResult second = (ToolResult) outcome.newEvents().get(3);
        assertThat(first.callId()).isEqualTo("c1");
        assertThat(first.output()).contains("class A").doesNotContain("class B");
        assertThat(second.callId()).isEqualTo("c2");
        assertThat(second.output()).contains("class B");
    }

    @Test
    @DisplayName("【清单】模型写清单 → 工具结果之后跟一条 TodoListUpdated（事实由这一层落）")
    void writingTheTodoListAlsoRecordsAnEvent() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "todo_write",
                        "{\"todos\":[{\"content\":\"改三个文件\",\"state\":\"in_progress\"},"
                                + "{\"content\":\"跑测试\",\"state\":\"pending\"}]}"),
                ScriptedLlm.answer("好，我开始了"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        // 工具自己**不写**事件流 —— 它只回报事实，落库和广播是这一层的事。
        // 位置在工具结果**之后**：读事件流的人先看到"这次调用跑完了"，
        // 再看到"它把清单改成了什么样"
        assertThat(outcome.newEvents()).satisfiesExactly(
                e -> assertThat(e).isInstanceOf(ToolCallRequested.class),
                e -> assertThat(e).isInstanceOf(ToolResult.class),
                e -> assertThat(e).isInstanceOf(TodoListUpdated.class),
                e -> assertThat(e).isInstanceOf(AssistantMessage.class));

        TodoListUpdated todos = (TodoListUpdated) outcome.newEvents().get(2);
        assertThat(todos.items()).extracting(TodoListUpdated.Item::content)
                .containsExactly("改三个文件", "跑测试");
    }

    @Test
    @DisplayName("【清单】参数不合法时不留半条事实 —— 事件流里不许出现残缺的清单")
    void aMalformedTodoCallRecordsNoEvent() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "todo_write",
                        "{\"todos\":[{\"content\":\"跑测试\",\"state\":\"doing\"}]}"),
                ScriptedLlm.answer("我改一下"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        // 只有请求和结果，没有 TodoListUpdated：模型看到那句报错之后会自己改对再写一次，
        // 而"写坏的那一次"不该在流里留下任何痕迹
        assertThat(outcome.newEvents()).satisfiesExactly(
                e -> assertThat(e).isInstanceOf(ToolCallRequested.class),
                e -> assertThat(e).isInstanceOf(ToolResult.class),
                e -> assertThat(e).isInstanceOf(AssistantMessage.class));
    }

    @Test
    @DisplayName("【并发】写调用会把批切开：读-写-读 是三批，事件仍是请求结果交替")
    void aWriteCallBreaksTheBatch() {
        writeFile("B.java", "class B {}\n");
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCalls(
                        ScriptedLlm.call("c1", "read_file", "{\"path\":\"A.java\"}"),
                        ScriptedLlm.call("c2", "write_file",
                                "{\"path\":\"C.java\",\"content\":\"class C {}\"}"),
                        ScriptedLlm.call("c3", "read_file", "{\"path\":\"B.java\"}")),
                ScriptedLlm.answer("好了"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        // 三个批、每批一个：写调用既不会被并进前面的读批，也不会把后面的读拉进来。
        // 3 请求 + 3 结果 + 模型最后的结论 = 7
        assertThat(outcome.newEvents()).hasSize(7);
        assertThat(outcome.newEvents().subList(0, 6)).satisfiesExactly(
                e -> assertThat(e).isInstanceOf(ToolCallRequested.class),
                e -> assertThat(e).isInstanceOf(ToolResult.class),
                e -> assertThat(e).isInstanceOf(ToolCallRequested.class),
                e -> assertThat(e).isInstanceOf(ToolResult.class),
                e -> assertThat(e).isInstanceOf(ToolCallRequested.class),
                e -> assertThat(e).isInstanceOf(ToolResult.class));
        assertThat(outcome.newEvents().getLast()).isInstanceOf(AssistantMessage.class);
        assertThat(worktree.resolve("C.java")).exists();   // 写真的落地了
    }

    @Test
    @DisplayName("【总预算】一轮里工具输出累计越过上限后，后面的结果落盘并只回灌头尾")
    void toolOutputIsCappedWithinOneTurn() throws IOException {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCalls(
                        ScriptedLlm.call("c1", "run_command", "{\"command\":\"build\"}"),
                        ScriptedLlm.call("c2", "run_command", "{\"command\":\"build\"}"),
                        ScriptedLlm.call("c3", "run_command", "{\"command\":\"build\"}")),
                ScriptedLlm.answer("跑完了"));

        // 每次调用都回一大段 —— 要触及的正是"一轮总量"这个上限。
        // 这里不能用 read_file：它自己会截断超长行，压根吐不出这么大的输出。
        // **两头长得不一样**（HEAD…TAIL），不然"头尾各留一半"这件事验不出来
        CommandExecutor chatty = (ws, cmd, timeout, limit, cancel) ->
                new CommandResult(0, "HEAD-" + "y".repeat(80_000) + "-TAIL", false, 5);

        TurnOutcome outcome = new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), client, MODEL, ModelCapabilities.UNKNOWN,
                ToolRegistry.standard(), chatty, TokenBudget.DEFAULT,
                VerificationPlan.NONE, CancellationToken.none()));

        List<ToolResult> results = outcome.newEvents().stream()
                .filter(ToolResult.class::isInstance)
                .map(ToolResult.class::cast)
                .toList();

        // 前两个各 8 万字符，加起来还没到 20 万；第三个一进来就超了
        assertThat(results).hasSize(3);
        assertThat(results.get(0).output()).hasSizeGreaterThan(50_000);
        assertThat(results.get(1).output()).hasSizeGreaterThan(50_000);

        ToolResult capped = results.get(2);
        assertThat(capped.output()).hasSizeLessThan(10_000);
        // 回灌的是"头尾两段 + 省略了多少 + 去哪找全文"，而不是一句干巴巴的"已截断"
        assertThat(capped.output())
                .contains("已达上限")
                .contains("HEAD-")                       // 开头这一半在
                .contains("-TAIL")                       // 结尾那一半也在
                .contains("中间省略")                     // 断开的地方明说断了
                .contains(Workspace.TOOL_OUTPUT_DIR + "/c3.txt");
        assertThat(capped.truncated()).isTrue();

        // **落盘的那份是完整的** —— 这才是它比"直接截断"强的地方：
        // 模型读一行 read_file 就能把丢掉的那部分拿回来
        String persisted = Files.readString(
                worktree.resolve(Workspace.TOOL_OUTPUT_DIR).resolve("c3.txt"),
                StandardCharsets.UTF_8);
        assertThat(persisted).hasSizeGreaterThan(50_000);
        // 完整的意思是**两头都在**（不是只能读到开头）。注意它前面还有工具自己加的
        // "退出码…耗时…"那行 —— 落盘存的是工具结果的全文，不是命令的原始输出
        assertThat(persisted).contains("HEAD-").endsWith("-TAIL");
    }

    @Test
    @DisplayName("【总预算】读文件那类**自己有界**的工具不进总量闸 —— 落盘提示对它是循环指令")
    void selfBoundedToolsSkipTheSpillGate() throws IOException {
        // 一个够大的文件：单看它自己完全正常（没到 read_file 的任何一条上限），
        // 但加上前面那两段 8 万字符就会顶开这一轮的总量上限
        Files.writeString(worktree.resolve("big.txt"),
                "FILE-CONTENT-" + "z".repeat(50_000), StandardCharsets.UTF_8);

        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCalls(
                        ScriptedLlm.call("c1", "run_command", "{\"command\":\"build\"}"),
                        ScriptedLlm.call("c2", "run_command", "{\"command\":\"build\"}"),
                        ScriptedLlm.call("c3", "read_file", "{\"path\":\"big.txt\"}")),
                ScriptedLlm.answer("看完了"));

        CommandExecutor chatty = (ws, cmd, timeout, limit, cancel) ->
                new CommandResult(0, "y".repeat(80_000), false, 5);

        TurnOutcome outcome = new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), client, MODEL, ModelCapabilities.UNKNOWN,
                ToolRegistry.standard(), chatty, TokenBudget.DEFAULT,
                VerificationPlan.NONE, CancellationToken.none()));

        ToolResult read = outcome.newEvents().stream()
                .filter(ToolResult.class::isInstance)
                .map(ToolResult.class::cast)
                .filter(result -> result.callId().equals("c3"))
                .findFirst().orElseThrow();

        // 内容**整份进来**，没有被换成"已存到某处，去读回来"。
        // 代价是它整份占着上下文 —— 那份累积量归压缩管（read_file 在可清名单里），
        // 而这一层唯一能做的事（落盘 + 叫它读回来）对它恰恰是个死循环
        assertThat(read.output()).contains("FILE-CONTENT-");
        assertThat(read.output()).doesNotContain("已达上限");
        // 也没有多出一份落盘文件 —— 被落盘的正是这条 read 的结果，
        // 读回来照样超、照样落盘、照样叫它读
        assertThat(worktree.resolve(Workspace.TOOL_OUTPUT_DIR).resolve("c3.txt")).doesNotExist();
    }

    @Test
    @DisplayName("【重试可见】限流 → 等一下再试，而且**那几秒落进事件流**")
    void transientFailureIsRetriedVisibly() {
        AtomicInteger calls = new AtomicInteger();
        // 第一次限流，重试就成功。服务商说了"等到 0 毫秒"—— 测试别真睡 500 毫秒
        LlmClient flaky = (request, listener, cancellation) -> {
            if (calls.incrementAndGet() == 1) {
                throw new LlmCallException(LlmCallException.Kind.RATE_LIMITED,
                        "模型调用失败 HTTP 429：rate limited", null, 0L);
            }
            return ScriptedLlm.answer("接着说");
        };

        TurnOutcome outcome = new AgentTurn().run(input(flaky));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(outcome.newEvents())
                .filteredOn(LlmRetryScheduled.class::isInstance)
                .map(LlmRetryScheduled.class::cast)
                .singleElement()
                .satisfies(retry -> {
                    assertThat(retry.attempt()).isEqualTo(1);
                    assertThat(retry.maxAttempts()).isEqualTo(3);
                    // 稳定的分类，不是服务商那句英文原文 —— 界面拿它做标签
                    assertThat(retry.reason()).isEqualTo("限流");
                });
    }

    @Test
    @DisplayName("【重试上限】一直限流 → 试满次数就报出来，不在这里耗着")
    void retriesAreBounded() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient alwaysLimited = (request, listener, cancellation) -> {
            calls.incrementAndGet();
            throw new LlmCallException(LlmCallException.Kind.RATE_LIMITED, "HTTP 429", null, 0L);
        };

        assertThatThrownBy(() -> new AgentTurn().run(input(alwaysLimited)))
                .isInstanceOf(LlmCallException.class);

        assertThat(calls.get()).as("最多三次").isEqualTo(3);
    }

    @Test
    @DisplayName("【服务端说等太久】不提前重试 —— 违反它刚给的指示只会再撞一次")
    void aLongRetryAfterIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        // 它让我们等 10 分钟，而我们最多愿意等 8 秒
        LlmClient toldToWaitTenMinutes = (request, listener, cancellation) -> {
            calls.incrementAndGet();
            throw new LlmCallException(LlmCallException.Kind.RATE_LIMITED,
                    "HTTP 429", null, Duration.ofMinutes(10).toMillis());
        };

        assertThatThrownBy(() -> new AgentTurn().run(input(toldToWaitTenMinutes)))
                .isInstanceOf(LlmCallException.class);

        assertThat(calls.get()).as("一次就够，不试了").isEqualTo(1);
    }

    @Test
    @DisplayName("【上下文超长】provider 说装不下了 → 修一次 → 重发 —— 这一轮还能跑完")
    void contextOverflowIsRepairedAndRetried() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger repairs = new AtomicInteger();
        // 第一次装不下，修完之后就装得下了
        LlmClient overflowing = (request, listener, cancellation) -> {
            if (modelCalls.incrementAndGet() == 1) {
                throw new LlmCallException(LlmCallException.Kind.CONTEXT_EXCEEDED,
                        "模型调用失败 HTTP 400：This model's maximum context length is 65536 tokens",
                        null);
            }
            return ScriptedLlm.answer("压好了，接着说");
        };

        TurnOutcome outcome = new AgentTurn(event -> {
        }, () -> {
            repairs.incrementAndGet();
            return true;      // 修动了：真落了一条压过的记录
        }).run(input(overflowing));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(repairs.get()).as("修了一次").isEqualTo(1);
        assertThat(modelCalls.get()).as("第一次失败 + 重发一次").isEqualTo(2);
    }

    @Test
    @DisplayName("【上下文超长】修不动就把【原始那个错误】报出去 —— 不编一句「压缩失败」")
    void anUnrepairableOverflowReportsTheOriginalError() {
        LlmClient overflowing = (request, listener, cancellation) -> {
            throw new LlmCallException(LlmCallException.Kind.CONTEXT_EXCEEDED,
                    "模型调用失败 HTTP 400：maximum context length is 65536 tokens", null);
        };
        AtomicInteger repairs = new AtomicInteger();

        assertThatThrownBy(() -> new AgentTurn(event -> {
        }, () -> {
            repairs.incrementAndGet();
            return false;     // 没修动（没东西可压、或者压了但模型可见的状态没变）
        }).run(input(overflowing)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("maximum context length");
    }

    @Test
    @DisplayName("【上下文超长】最多修一次 —— 压完还装不下，说明装不下的是尾巴，再压也没用")
    void overflowIsRepairedOnlyOnce() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger repairs = new AtomicInteger();
        LlmClient alwaysOverflows = (request, listener, cancellation) -> {
            modelCalls.incrementAndGet();
            throw new LlmCallException(LlmCallException.Kind.CONTEXT_EXCEEDED, "还是装不下", null);
        };

        assertThatThrownBy(() -> new AgentTurn(event -> {
        }, () -> {
            repairs.incrementAndGet();
            return true;
        }).run(input(alwaysOverflows)))
                .isInstanceOf(LlmCallException.class);

        assertThat(repairs.get()).isEqualTo(1);
        assertThat(modelCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("【上下文超长】别的失败**不许**去叫修 —— 换个状态也救不了认证失败")
    void onlyContextOverflowTriggersRepair() {
        LlmClient authFails = (request, listener, cancellation) -> {
            throw new LlmCallException(LlmCallException.Kind.AUTH, "HTTP 401", null);
        };
        AtomicInteger repairs = new AtomicInteger();

        assertThatThrownBy(() -> new AgentTurn(event -> {
        }, () -> {
            repairs.incrementAndGet();
            return true;
        }).run(input(authFails)))
                .isInstanceOf(LlmCallException.class);

        assertThat(repairs.get()).isZero();
    }

    @Test
    @DisplayName("【审批理由】判据说的话要落进 ToolApprovalRequested —— 人要看着它点同意")
    void theReasonToAskLandsInTheEvent() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCalls(
                        ScriptedLlm.call("c1", "run_command", "{\"command\":\"curl example.com\"}")),
                ScriptedLlm.answer("好"));
        CommandExecutor unused = (ws, cmd, timeout, limit, cancel) ->
                new CommandResult(0, "", false, 1);

        TurnOutcome outcome = new AgentTurn().run(inputAsking(client, unused,
                commandLine -> Optional.of("`curl` 不在免审批的程序名单里，先请你过一眼。")));

        ToolApprovalRequested asked = outcome.newEvents().stream()
                .filter(ToolApprovalRequested.class::isInstance)
                .map(ToolApprovalRequested.class::cast)
                .findFirst().orElseThrow();

        // 这条事件若只有一个 callId，人被问的时候就看不到为什么问 —— 而"人为什么拒绝"
        // 我们是记着的（ToolApprovalResolved.reason），半个决定不能只记一半
        assertThat(asked.reason()).contains("curl").contains("不在免审批");
    }

    @Test
    @DisplayName("【并发上限】一条消息里几十个调用不会一起冲出去 —— 上限由平台定，不是模型定")
    void parallelToolCallsAreCapped() {
        ConcurrencyProbe probe = new ConcurrencyProbe();
        ToolRegistry registry = new ToolRegistry(List.of(probe));

        ToolCall[] calls = new ToolCall[20];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = ScriptedLlm.call("c" + i, "probe", "{}");
        }
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCalls(calls), ScriptedLlm.answer("跑完了"));

        CommandExecutor unused = (ws, cmd, timeout, limit, cancel) ->
                new CommandResult(0, "", false, 1);

        new AgentTurn().run(new TurnInput(sessionId, projection(), worktree, MODEL.systemPrompt(),
                client, MODEL, ModelCapabilities.UNKNOWN, registry, unused, TokenBudget.DEFAULT,
                VerificationPlan.NONE, CancellationToken.none()));

        // 确实是并发的 —— 不然上限这件事根本不需要有
        assertThat(probe.peak()).isGreaterThan(1);
        // 但不会 20 个一起冲：上限由平台定，模型在一条消息里塞多少都不能突破它。
        // 这个数就是 {@link AgentTurn#MAX_PARALLEL_TOOLS}，改了它这条跟着改
        assertThat(probe.peak()).isLessThanOrEqualTo(AgentTurn.MAX_PARALLEL_TOOLS);
    }

    /** 一个只用来量并发的工具：进来自增、出去自减，记下见过的最大值。 */
    private static final class ConcurrencyProbe implements Tool {

        private final AtomicInteger live = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        int peak() {
            return peak.get();
        }

        @Override
        public String name() {
            return "probe";
        }

        @Override
        public String description() {
            return "测试用";
        }

        @Override
        public String parametersJsonSchema() {
            return """
                    {"type":"object","properties":{}}""";
        }

        @Override
        public boolean concurrencySafe() {
            return true;
        }

        @Override
        public ToolOutcome execute(ToolContext context, JsonNode arguments) {
            peak.accumulateAndGet(live.incrementAndGet(), Math::max);
            try {
                // 睡一下，好让重叠真的发生 —— 不睡的话每个都瞬间跑完，量不到并发
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            live.decrementAndGet();
            return ToolOutcome.ok("ok");
        }
    }

    @Test
    @DisplayName("【落盘目录】越过保留期的旧文件被扫掉，新的留着 —— 那个目录只进不出")
    void oldSpillsAreSweptAway() throws IOException {
        // 判据（30 天）和"怎么算过期"分开：真等 30 天才能验的话，这条用例永远不会被跑到
        Path dir = worktree.resolve(Workspace.TOOL_OUTPUT_DIR);
        Files.createDirectories(dir);
        Path ancient = dir.resolve("callancient.txt");
        Files.writeString(ancient, "很久以前那次调用的完整输出", StandardCharsets.UTF_8);
        Path recent = dir.resolve("callrecent.txt");
        Files.writeString(recent, "昨天落的", StandardCharsets.UTF_8);

        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        Files.setLastModifiedTime(ancient, FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        Files.setLastModifiedTime(recent, FileTime.from(Instant.now().minus(Duration.ofDays(1))));

        int removed = AgentTurn.sweepOldSpills(dir, cutoff);

        assertThat(removed).isEqualTo(1);
        assertThat(ancient).doesNotExist();
        // 边界要留着：差一天的文件是"还会被引用"的那一类，不是垃圾
        assertThat(recent).exists();
    }

    @Test
    @DisplayName("【落盘目录】目录压根不存在时扫不炸 —— 它是在「第一次落盘」时才被建出来的")
    void sweepingAMissingDirectoryIsHarmless() {
        assertThat(AgentTurn.sweepOldSpills(worktree.resolve("没有这个目录"),
                Instant.now())).isZero();
    }

    @Test
    @DisplayName("【截断】输出被长度上限砍断时，注入一句「拆小点」再给它一次机会")
    void truncatedOutputGetsAnotherChance() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.truncated("我开始写了，但还没写完就……"),
                ScriptedLlm.answer("这次写完了"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        // 一次截断 + 一次重来，最后正常收场 —— 而不是整轮判死
        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.newEvents()).anySatisfy(e -> {
            assertThat(e).isInstanceOf(PlatformInstruction.class);
            assertThat(((PlatformInstruction) e).text()).contains("拆成更小的几步");
        });
        // 第二次调用模型时那句话必须已经在上下文里，否则等于白说
        assertThat(client.requests().get(1).messages())
                .anySatisfy(m -> assertThat(m.content()).contains("拆成更小的几步"));
    }

    @Test
    @DisplayName("模型要改文件：改动真的落到工作区了（工具与循环是通的）")
    void editsActuallyLandOnDisk() {
        // **先读再改** —— 那才是真实的流：edit_file 要求"改之前观测过"（见 ReadLedger）。
        // 顺带也验了"读这一笔真的记进账本了"，以及紧接着的第二次写没被自己挡住
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "read_file", "{\"path\":\"A.java\"}"),
                ScriptedLlm.toolCall("c2", "edit_file",
                        "{\"path\":\"A.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}"),
                ScriptedLlm.answer("改好了"));

        new AgentTurn().run(input(client));

        assertThat(readFile("A.java")).contains("int x = 2;").doesNotContain("int x = 1;");
    }

    @Test
    @DisplayName("未知工具：返回失败并把可用清单一起给出去，模型能自己纠正")
    void unknownToolListsTheAvailableOnes() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "read_files", "{}"),
                ScriptedLlm.answer("抱歉，我用错了名字"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        ToolResult result = (ToolResult) outcome.newEvents().get(1);
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("没有名为 read_files 的工具")
                .contains("read_file").contains("edit_file");
    }

    @Test
    @DisplayName("参数不是合法 JSON：变成一次可自修的失败，而不是崩掉整个 turn")
    void malformedArgumentsBecomeAFailure() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "read_file", "{这不是JSON"),
                ScriptedLlm.answer("我重新给参数"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        ToolResult result = (ToolResult) outcome.newEvents().get(1);
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("不是合法 JSON");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【防自旋】模型一直要工具不停 → 到轮次上限停下，交给人")
    void spinsUntilMaxRounds() {
        ScriptedLlm client = ScriptedLlm.always(
                ScriptedLlm.toolCall("c", "read_file", "{\"path\":\"A.java\"}"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.MAX_ITERATIONS);
        // 每轮两个事件（请求 + 结果），25 轮
        assertThat(outcome.newEvents()).hasSize(50);
    }

    @Test
    @DisplayName("输出一直截断：重试到次数用尽后当成半成品收场，不接着往下推理")
    void truncatedOutputStopsTheTurn() {
        // 一直给截断的结果 —— 这条盯的是**最终的收场**，重试本身由另一条覆盖
        ScriptedLlm client = ScriptedLlm.always(
                new LlmResult("deepseek-flash", "length", "我说到一半被砍了",
                        List.of(), new TokenUsage(10, 4096, 4106, 0, 0)));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.TRUNCATED);
        // 初次 + 两次重试 = 3 次调用，到此为止，不会无限重来
        assertThat(client.requests()).hasSize(3);
    }

    @Test
    @DisplayName("取消信号在工具边界生效")
    void cancellationStopsAtToolBoundary() {
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();
        ScriptedLlm client = new ScriptedLlm(ScriptedLlm.answer("不该走到这里"));

        TurnOutcome outcome = new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), client, MODEL, ModelCapabilities.UNKNOWN, ToolRegistry.standard(),
                (ws, cmd, timeout, limit, cancel) -> new CommandResult(0, "", false, 0),
                TokenBudget.DEFAULT, VerificationPlan.NONE,
                cancelled));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.CANCELLED);
        assertThat(outcome.newEvents()).isEmpty();
    }

    @Test
    @DisplayName("【上下文读数】记的是**最后一次**调用的输入，不是这一轮的和")
    void lastCallUsageIsTheContextSizeNotTheTurnTotal() {
        // 两次调用、两个明显不同的输入量。**一次调用就完成的一轮里这两个数恰好相等** ——
        // 那正是它们最容易被混为一谈的地方，所以这里必须造一条要来回两次的
        ScriptedLlm client = new ScriptedLlm(
                new LlmResult("deepseek-flash", "tool_calls", "",
                        List.of(new ToolCall("c1", "read_file", "{\"path\":\"A.java\"}")),
                        new TokenUsage(1_000, 50, 1_050, 0, 0)),
                new LlmResult("deepseek-flash", "stop", "读完了", List.of(),
                        new TokenUsage(4_000, 30, 4_030, 0, 0)));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        // 账单是两次的和
        assertThat(outcome.usage().inputTokens()).isEqualTo(5_000);
        // 而上下文读数是最后那一次单独的数 —— 收尾时送进去的上下文有多大
        assertThat(outcome.lastCallUsage().inputTokens()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("【上下文读数】一次模型都没调过时它是 UNKNOWN，不是某个凑出来的数")
    void lastCallUsageIsUnknownWhenNoModelWasCalled() {
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();

        TurnOutcome outcome = new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), new ScriptedLlm(ScriptedLlm.answer("不该走到这里")), MODEL,
                ModelCapabilities.UNKNOWN, ToolRegistry.standard(),
                (ws, cmd, timeout, limit, cancel) -> new CommandResult(0, "", false, 0),
                TokenBudget.DEFAULT, VerificationPlan.NONE, cancelled));

        // 0 —— 而落库那条事件据此判断"没有读数、别画上下文用量环"（见 TurnTokensUsed#hasContext）。
        // 凑一个"就是系统提示词那么大"之类的数才是错的：那是编的
        assertThat(outcome.lastCallUsage().inputTokens()).isZero();
        assertThat(outcome.lastCallUsage().isKnown()).isFalse();
    }

    @Test
    @DisplayName("【token 预算】超限后注入收尾指令，而不是掐断连接")
    void budgetExhaustionInjectsWrapUpInstruction() {
        TokenBudget tiny = new TokenBudget(50);
        ScriptedLlm client = new ScriptedLlm(
                new LlmResult("deepseek-flash", "tool_calls", "",
                        List.of(new ToolCall("c1", "read_file", "{\"path\":\"A.java\"}")),
                        new TokenUsage(40, 40, 80, 0, 0)),        // 第一轮就超预算
                ScriptedLlm.answer("预算用尽，我总结一下：文件已读取"));

        TurnOutcome outcome = new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), client, MODEL, ModelCapabilities.UNKNOWN, ToolRegistry.standard(),
                (ws, cmd, timeout, limit, cancel) -> new CommandResult(0, "", false, 0),
                tiny, VerificationPlan.NONE,
                CancellationToken.none()));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.newEvents().getLast()).isInstanceOf(AssistantMessage.class);

        // 第二次请求里必须带着收尾指令 —— 是"叫它收尾"，不是"把它掐掉"
        String lastUserMessage = client.requests().get(1).messages().stream()
                .filter(m -> m.role() == ChatRole.USER)
                .map(ChatMessage::content)
                .reduce((a, b) -> b)
                .orElse("");
        assertThat(lastUserMessage).contains("预算即将用尽").contains("停止调用工具");
    }

    @Test
    @DisplayName("【取消】执行途中被用户取消 → 落 ToolCancelled 事件，不是 ToolResult(失败)")
    void userCancellationIsRecordedAsToolCancelled() {
        CancellationToken token = new CancellationToken();
        CommandExecutor cancelling = (ws, cmd, timeout, limit, cancel) -> {
            token.cancel();   // 模拟：命令跑到一半，用户按了 Esc
            throw new IllegalStateException("进程被终止");
        };
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "run_command", "{\"command\":\"mvn test\"}"),
                ScriptedLlm.answer("好的"));

        TurnOutcome outcome = new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), client, MODEL, ModelCapabilities.UNKNOWN, ToolRegistry.standard(), cancelling,
                TokenBudget.DEFAULT, VerificationPlan.NONE,
                token));

        // 落的是【独立事件】。如果落成 ToolResult(success=false)，崩溃重放后模型会读成
        // "工具失败了"，然后理直气壮地重试用户刚刚取消掉的操作。
        assertThat(outcome.newEvents())
                .filteredOn(e -> e instanceof ToolCancelled)
                .singleElement()
                .satisfies(e -> assertThat(((ToolCancelled) e).callId()).isEqualTo("c1"));
        assertThat(outcome.newEvents()).noneMatch(e -> e instanceof ToolResult);
    }

    @Test
    @DisplayName("【能力门控】不支持工具调用的模型配上工具 → 开始前就失败，一次调用都不发")
    void modelWithoutToolSupportFailsFast() {
        // 不门控的话，这类模型会在每一次工具往返上安静地失败或忽略工具，
        // 最后表现成"agent 转了半天什么也没干"，排查起来毫无头绪。
        ModelCapabilities noTools = new ModelCapabilities(false, ModelCapabilities.DEFAULT_CONTEXT_WINDOW,
                ModelCapabilities.DEFAULT_MAX_OUTPUT_TOKENS);
        ScriptedLlm client = new ScriptedLlm(ScriptedLlm.answer("不该走到这里"));

        assertThatThrownBy(() -> new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), client, MODEL, noTools, ToolRegistry.standard(),
                (ws, cmd, timeout, limit, cancel) -> new CommandResult(0, "", false, 0),
                TokenBudget.DEFAULT, VerificationPlan.NONE, CancellationToken.none())))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不支持工具调用");

        assertThat(client.requests()).isEmpty();   // 一个请求都没发出去
    }

    @Test
    @DisplayName("模型换成了别的（服务端别名映射）：结果里记的是实际用的那个")
    void recordsTheModelTheServerActuallyUsed() {
        ScriptedLlm client = new ScriptedLlm(
                new LlmResult("deepseek-flash", "stop", "好的", List.of(), TokenUsage.UNKNOWN));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        assertThat(outcome.model()).isEqualTo("deepseek-flash");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【增量回调】工具开始执行之前，那条 ToolCallRequested 已经回调出去了")
    void toolCallIsReportedBeforeTheToolRuns() {
        // 崩溃恢复整条链都压在这一个性质上：进程在工具中途被杀时，事件流里必须有那条调用，
        // 才能补写成 ToolInterrupted 并阻止"重放一个可能已经改过文件的调用"。
        // 攒到一轮结束才交出事件的话，那一步就永远补不上。
        List<PersistentEvent> reported = new ArrayList<>();
        List<Integer> reportedCountWhenCommandRan = new ArrayList<>();
        CommandExecutor recording = (ws, cmd, timeout, limit, cancel) -> {
            reportedCountWhenCommandRan.add(reported.size());
            return new CommandResult(0, "ok", false, 1);
        };
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "run_command", "{\"command\":\"mvn test\"}"),
                ScriptedLlm.answer("跑完了"));

        // appender 要**还回真实 seq**（见 AgentTurn.Workbench.append）——
        // 这里拿一个自增号充当存储层
        AtomicLong nextSeq = new AtomicLong(history.size());
        TurnOutcome outcome = new AgentTurn().run(input(client, recording), event -> {
            reported.add(event);
            return nextSeq.incrementAndGet();
        });

        // 命令跑的那一刻，ToolCallRequested 已经在回调过的事件里了
        assertThat(reportedCountWhenCommandRan).singleElement()
                .satisfies(count -> assertThat(count).isGreaterThanOrEqualTo(1));
        assertThat(reported.getFirst()).isInstanceOf(ToolCallRequested.class);
        // 回调拿到的就是同一批事件：一个不多、一个不少、顺序相同
        assertThat(reported).isEqualTo(outcome.newEvents());
    }

    @Test
    @DisplayName("【增量回调】回调抛异常（比如 fencing token 已失效）当场中止整轮")
    void callbackFailureAbortsTheTurn() {
        // 回调里做的是"带 token 的落库"。token 失效意味着**这棵树**已经被别的执行者接管
        //（可能是另一个实例，也可能是同一个人的另一条会话），那本轮剩下的产出已经不可信了 ——
        // 必须当场停，而不是跑完再一起丢掉。
        ScriptedLlm client = ScriptedLlm.always(
                ScriptedLlm.toolCall("c1", "read_file", "{\"path\":\"A.java\"}"));
        StaleLeaseException stale = new StaleLeaseException(
                WorkspaceId.of(UserId.of("owner-1"), ProjectId.of("project-1")), 7L);

        assertThatThrownBy(() -> new AgentTurn().run(input(client), event -> {
            throw stale;
        })).isSameAs(stale);
    }

    // ------------------------------------------------------------------
    // 批准之后到底跑没跑
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【批准补跑】批过的那条命令，resume 时要**真的执行**，结果要落库")
    void anApprovedCommandIsActuallyExecutedOnResume() {
        ScriptedLlm first = new ScriptedLlm(
                ScriptedLlm.toolCall("call_1", "run_command", "{\"command\":\"ls -la\"}"));
        List<CommandResult> ran = new ArrayList<>();
        CommandExecutor executor = (ws, cmd, timeout, limit, cancel) -> {
            ran.add(new CommandResult(0, "total 0", false, 7));
            return ran.getLast();
        };

        // 一切都要批 —— 模拟"命令不在免审批清单里"
        TurnOutcome suspended = new AgentTurn().run(input(first, executor, command -> true));

        assertThat(suspended.status()).isEqualTo(TurnOutcome.Status.AWAITING_APPROVAL);
        assertThat(suspended.newEvents()).noneMatch(ToolResult.class::isInstance);
        assertThat(ran).isEmpty();          // 挂起时它确实还没跑，这是对的

        // 用户点了批准。落到事件流里的就这么一条（外加状态迁移）
        commit(suspended.newEvents());
        commit(List.of(new ToolApprovalResolved("call_1", true, UserId.of("root"), null)));

        ScriptedLlm second = new ScriptedLlm(ScriptedLlm.answer("跑完了"));
        TurnOutcome resumed = new AgentTurn().run(input(second, executor, command -> true));

        // ★ 断言打在**执行器**上，不是"事件里出现了 ToolResult" ——
        //   这次出问题的正是"事件都对、命令没跑"，只测事件是测不出来的
        assertThat(ran).hasSize(1);
        ToolResult result = resumed.newEvents().stream()
                .filter(ToolResult.class::isInstance)
                .map(ToolResult.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("批准过的那条命令没有产生结果"));
        assertThat(result.callId()).isEqualTo("call_1");
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("total 0");

        // 而且模型看到的是**真的输出**，不是"（用户批准了这次调用，可以执行）"
        assertThat(second.requests().getFirst().messages())
                .anySatisfy(m -> assertThat(m.content()).contains("total 0"));
    }

    @Test
    @DisplayName("【批准补跑】一条新的用户消息**不会**把上一轮批准过的命令再跑一遍")
    void aNewUserMessageDoesNotReplayAnOldApproval() {
        ScriptedLlm first = new ScriptedLlm(
                ScriptedLlm.toolCall("call_1", "run_command", "{\"command\":\"rm -rf A.java\"}"));
        List<CommandResult> ran = new ArrayList<>();
        CommandExecutor executor = (ws, cmd, timeout, limit, cancel) -> {
            ran.add(new CommandResult(0, "ok", false, 1));
            return ran.getLast();
        };

        TurnOutcome suspended = new AgentTurn().run(input(first, executor, command -> true));
        commit(suspended.newEvents());
        commit(List.of(new ToolApprovalResolved("call_1", true, UserId.of("root"), null)));

        // ★ 关键：下面跟着的是**一条新用户消息**，不是 resume。
        //   少了这个判据的话，往一个失败过/挂着过的会话里再发一句话，
        //   就会把上面那条 rm -rf 重新执行一遍 —— 而那是一条不可逆的操作
        commit(List.of(new UserMessage("算了，先别动")));

        TurnOutcome next = new AgentTurn().run(
                input(ScriptedLlm.always(ScriptedLlm.answer("好")), executor, command -> true));

        assertThat(ran).isEmpty();
        assertThat(next.newEvents()).noneMatch(ToolResult.class::isInstance);
    }

    @Test
    @DisplayName("【批准补跑】被**拒绝**的调用不补跑 —— 那条答复本身就充当它的结果")
    void aRejectedCallIsNotExecuted() {
        ScriptedLlm first = new ScriptedLlm(
                ScriptedLlm.toolCall("call_1", "run_command", "{\"command\":\"rm -rf /\"}"));
        List<CommandResult> ran = new ArrayList<>();
        CommandExecutor executor = (ws, cmd, timeout, limit, cancel) -> {
            ran.add(new CommandResult(0, "ok", false, 1));
            return ran.getLast();
        };

        TurnOutcome suspended = new AgentTurn().run(input(first, executor, command -> true));
        commit(suspended.newEvents());
        commit(List.of(new ToolApprovalResolved("call_1", false, UserId.of("root"), "太危险")));

        new AgentTurn().run(input(ScriptedLlm.always(ScriptedLlm.answer("好")), executor, command -> true));

        assertThat(ran).isEmpty();
    }

    @Test
    @DisplayName("【批准补跑】补跑一次之后不会再来一次（幂等）")
    void settlingAnApprovalHappensAtMostOnce() {
        ScriptedLlm first = new ScriptedLlm(
                ScriptedLlm.toolCall("call_1", "run_command", "{\"command\":\"ls\"}"));
        List<CommandResult> ran = new ArrayList<>();
        CommandExecutor executor = (ws, cmd, timeout, limit, cancel) -> {
            ran.add(new CommandResult(0, "ok", false, 1));
            return ran.getLast();
        };

        TurnOutcome suspended = new AgentTurn().run(input(first, executor, command -> true));
        commit(suspended.newEvents());
        commit(List.of(new ToolApprovalResolved("call_1", true, UserId.of("root"), null)));

        TurnOutcome resumed = new AgentTurn().run(
                input(ScriptedLlm.always(ScriptedLlm.answer("好")), executor, command -> true));
        commit(resumed.newEvents());

        // 再跑一次：尾部现在是结果和模型的回答，不再是那条答复
        new AgentTurn().run(input(ScriptedLlm.always(ScriptedLlm.answer("好")), executor, command -> true));

        assertThat(ran).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 把这一轮新产生的事件接进历史 —— 模拟又跑了一轮之后的样子。 */
    @Test
    @DisplayName("【读过才许覆盖】账是从**投影**传下来的：上一轮读过才改得动")
    void theReadLedgerComesFromTheProjection() {
        // 上一轮读了 A.java —— 历史里那次调用就是账（本轮没有读它）
        commit(List.of(new ToolCallRequested("c1", "read_file", "{\"path\":\"A.java\"}")));
        writeFile("B.java", "class B {}\n");

        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c2", "write_file",
                        "{\"path\":\"A.java\",\"content\":\"class A { int x; }\\n\"}"),
                ScriptedLlm.toolCall("c3", "write_file",
                        "{\"path\":\"B.java\",\"content\":\"class B { int y; }\\n\"}"),
                ScriptedLlm.answer("好了"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        // 读过的那份改得动：账跨轮传下来了（它现在跟着投影走，见 TurnInput 的类注释）
        assertThat(readFile("A.java")).contains("int x");
        // 没读过的原样不动，而且回给模型的话说了"先读一遍"
        assertThat(readFile("B.java")).isEqualTo("class B {}\n");
        assertThat(outcome.newEvents()).filteredOn(ToolResult.class::isInstance)
                .extracting(event -> ((ToolResult) event).output())
                .anyMatch(output -> output.contains("先用 read_file"));
    }

    private void commit(List<PersistentEvent> events) {
        for (PersistentEvent event : events) {
            history.add(new StoredEvent(sessionId, history.size() + 1L,
                    Instant.parse("2026-09-25T10:00:00Z"), event));
        }
    }

    /**
     * 这条会话的投影：测试里就是从这份事件流建一条**活的**（生产里它跨轮活着，
     * 见 {@code SessionProjections}）。
     */
    private ContextAssembler.Projection projection() {
        ContextAssembler.Projection projection =
                new ContextAssembler().projection(MODEL.systemPrompt());
        projection.fold(history);
        return projection;
    }

    private TurnInput input(LlmClient client) {
        return input(client, (ws, cmd, timeout, limit, cancel) -> new CommandResult(0, "ok", false, 1));
    }

    private TurnInput input(LlmClient client, CommandExecutor executor) {
        return new TurnInput(sessionId, projection(), worktree, MODEL.systemPrompt(), client, MODEL,
                ModelCapabilities.UNKNOWN, ToolRegistry.standard(), executor,
                TokenBudget.DEFAULT, VerificationPlan.NONE,
                CancellationToken.none());
    }

    private TurnInput input(LlmClient client, CommandExecutor executor,
                            Predicate<String> asksApproval) {
        // 判据现在回来的是**理由**（见 CommandApproval）。这里把它收成一句固定的，
        // 免得十几个调用点都写一遍；要验"理由真的进了事件"的那条测试自己给判据
        return inputAsking(client, executor, commandLine -> asksApproval.test(commandLine)
                ? Optional.of("测试：这条命令要人批一下")
                : Optional.empty());
    }

    /**
     * 判据直接给理由的那种。
     *
     * <p>名字不叫 {@code input} 是因为**它不能被重载**：两个重载都收"一个 String 的函数"，
     * 而 {@code command -> true} 这种隐式类型的 lambda 在它们之间选不出来（编译器报"引用不明确"）。
     */
    private TurnInput inputAsking(LlmClient client, CommandExecutor executor,
                                  CommandApproval approval) {
        return new TurnInput(sessionId, projection(), worktree, MODEL.systemPrompt(), client, MODEL,
                ModelCapabilities.UNKNOWN, ToolRegistry.standard(), executor,
                TokenBudget.DEFAULT, VerificationPlan.NONE,
                CancellationToken.none(), approval);
    }

    private void writeFile(String path, String content) {
        try {
            Files.writeString(worktree.resolve(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readFile(String path) {
        try {
            return Files.readString(worktree.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
