package com.codeloom.agent.loop;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.agent.llm.LlmRole;
import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.model.ModelCapabilities;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.llm.TokenUsage;
import com.codeloom.agent.llm.ToolCall;
import com.codeloom.agent.support.ScriptedLlm;
import com.codeloom.agent.web.WebAccess;
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
import java.time.ZoneId;
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
 * agent 循环使用假模型客户端，工具和文件系统使用真实实现。循环不碰存储，
 * 因此测试不需要数据库、Redis 或 API Key。
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
    @DisplayName("时钟跟着用户消息进投影 —— 「今天」不用跑 date，也不会每次请求重发")
    void sendsTheClockWithTheUserMessage() {
        ScriptedLlm client = new ScriptedLlm(ScriptedLlm.answer("好"));

        new AgentTurn().run(input(client));

        String user = client.requests().getFirst().messages().getLast().content();
        assertThat(user)
                .contains("[平台指令] 现在是 ")
                .contains(ZoneId.systemDefault().getId());
    }

    @Test
    @DisplayName("【缓存】下一次请求是上一次的严格前缀 —— 时钟不会再被重发一遍")
    void theNextRequestIsAPrefixExtension() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("call_1", "read_file", "{\"path\":\"A.java\"}"),
                ScriptedLlm.answer("读到了"));

        new AgentTurn().run(input(client));

        List<LlmMessage> first = client.requests().getFirst().messages();
        List<LlmMessage> second = client.requests().get(1).messages();
        // 第二次请求必须逐条以上一次为前缀，否则前缀缓存失效。
        assertThat(second.subList(0, first.size())).isEqualTo(first);
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
        // 第一轮只调工具、不产出 AssistantMessage：请求、结果、最终回答共 3 条。
        assertThat(outcome.newEvents()).hasSize(3);
        assertThat(outcome.newEvents().get(0)).isInstanceOf(ToolCallRequested.class);

        ToolResult result = (ToolResult) outcome.newEvents().get(1);
        assertThat(result.callId()).isEqualTo("call_1");
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("class A").contains("int x = 1");   // 真读到了文件

        assertThat(outcome.newEvents().get(2)).isInstanceOf(AssistantMessage.class);

        // 第二次调用模型时，工具结果必须已经在上下文里
        List<LlmMessage> secondRequest = client.requests().get(1).messages();
        assertThat(secondRequest).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(LlmRole.TOOL);
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

        // 只调工具的一轮没有正文，但思考必须落库；带 tool_calls 的消息缺思考会导致 400。
        AssistantMessage spoken = outcome.newEvents().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("只调工具的那一轮也要落 AssistantMessage"));
        assertThat(spoken.text()).isEmpty();
        assertThat(spoken.reasoning()).isEqualTo("先看看这个文件里有什么");

        // 还必须真正进入下一次请求；落库与请求组装是两段代码，后者才可能丢失思考。
        List<LlmMessage> secondRequest = client.requests().get(1).messages();
        assertThat(secondRequest).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(LlmRole.ASSISTANT);
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

        // 不产思考的模型该项必须为 null；适配器据此决定不发送 reasoning_content。
        List<LlmMessage> secondRequest = client.requests().get(1).messages();
        assertThat(secondRequest)
                .filteredOn(m -> m.role() == LlmRole.ASSISTANT)
                .allSatisfy(m -> assertThat(m.reasoning()).isNull());
        assertThat(secondRequest)
                .filteredOn(m -> m.role() == LlmRole.ASSISTANT)
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

        // 两条请求先落库、再落两个结果，最后是结论：2 + 2 + 1 = 5。
        // 该顺序既满足崩溃恢复，也让投影把相邻请求合并为一条 assistant 消息。
        assertThat(outcome.newEvents()).hasSize(5);
        assertThat(outcome.newEvents().get(0)).isInstanceOf(ToolCallRequested.class);
        assertThat(outcome.newEvents().get(1)).isInstanceOf(ToolCallRequested.class);
        assertThat(((ToolCallRequested) outcome.newEvents().get(0)).callId()).isEqualTo("c1");
        assertThat(((ToolCallRequested) outcome.newEvents().get(1)).callId()).isEqualTo("c2");

        // 结果并发执行，但回灌顺序必须等于请求顺序，否则结果会配错调用。
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

        // 工具只回报事实，事件由循环在工具结果之后落库。
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

        // 无效参数只产生请求和失败结果，不落残缺的 TodoListUpdated。
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

        // 读、写、读各成一批：3 请求 + 3 结果 + 1 结论 = 7。
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

        // 每次调用返回大段输出以触及每轮总量上限；两头内容不同，便于验证头尾各留一半。
        // 不能用 read_file，它会截断超长行。
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

        // 前两个各 8 万字符，第三个进入时越过 20 万上限。
        assertThat(results).hasSize(3);
        assertThat(results.get(0).output()).hasSizeGreaterThan(50_000);
        assertThat(results.get(1).output()).hasSizeGreaterThan(50_000);

        ToolResult capped = results.get(2);
        assertThat(capped.output()).hasSizeLessThan(10_000);
        // 回灌头尾两段、省略量、落盘路径，而不是只写“已截断”。
        assertThat(capped.output())
                .contains("已达上限")
                .contains("HEAD-")                       // 开头这一半在
                .contains("-TAIL")                       // 结尾那一半也在
                .contains("中间省略")                     // 断开的地方明说断了
                .contains(Workspace.TOOL_OUTPUT_DIR + "/c3.txt");
        assertThat(capped.truncated()).isTrue();

        // 落盘文件是完整输出，模型可用 read_file 取回。
        String persisted = Files.readString(
                worktree.resolve(Workspace.TOOL_OUTPUT_DIR).resolve("c3.txt"),
                StandardCharsets.UTF_8);
        assertThat(persisted).hasSizeGreaterThan(50_000);
        // 完整意味着头尾都在；落盘的是工具结果全文，包含“退出码…耗时…”行。
        assertThat(persisted).contains("HEAD-").endsWith("-TAIL");
    }

    @Test
    @DisplayName("【总预算】超预算改写结果时**不丢领域事实** —— 清单更新照常落事件")
    void cappingKeepsDomainFacts() throws IOException {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCalls(
                        ScriptedLlm.call("c1", "run_command", "{\"command\":\"build\"}"),
                        ScriptedLlm.call("c2", "run_command", "{\"command\":\"build\"}"),
                        ScriptedLlm.call("c3", "run_command", "{\"command\":\"build\"}"),
                        ScriptedLlm.call("c4", "todo_write",
                                "{\"todos\":[{\"content\":\"改代码\",\"state\":\"in_progress\"}]}")),
                ScriptedLlm.answer("好了"));

        // 前三次越过本轮输出预算，第四次（清单）的结果会被改写。
        CommandExecutor chatty = (ws, cmd, timeout, limit, cancel) ->
                new CommandResult(0, "y".repeat(80_000), false, 5);

        TurnOutcome outcome = new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), client, MODEL, ModelCapabilities.UNKNOWN,
                ToolRegistry.standard(), chatty, TokenBudget.DEFAULT,
                VerificationPlan.NONE, CancellationToken.none()));

        // 改写只替换给模型的文本，不能丢掉 todoUpdate 等领域事实。
        assertThat(outcome.newEvents()).anyMatch(TodoListUpdated.class::isInstance);
        TodoListUpdated todos = (TodoListUpdated) outcome.newEvents().stream()
                .filter(TodoListUpdated.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(todos.items()).extracting(TodoListUpdated.Item::content)
                .containsExactly("改代码");
    }

    @Test
    @DisplayName("【总预算】读文件那类**自己有界**的工具不做落盘改写 —— 落盘提示对它是循环指令")
    void selfBoundedToolsSkipTheSpillGate() throws IOException {
        // 文件本身未触及 read_file 上限，但加上前两段输出会越过本轮总量上限。
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

        // 内容整份进入上下文，未被替换为落盘提示；累积占用由压缩处理。
        assertThat(read.output()).contains("FILE-CONTENT-");
        assertThat(read.output()).doesNotContain("已达上限");
        // 不生成落盘文件；落盘提示对读文件会形成循环。
        assertThat(worktree.resolve(Workspace.TOOL_OUTPUT_DIR).resolve("c3.txt")).doesNotExist();
    }

    @Test
    @DisplayName("【重试可见】限流 → 等一下再试，而且**那几秒落进事件流**")
    void transientFailureIsRetriedVisibly() {
        AtomicInteger calls = new AtomicInteger();
        // 第一次限流、重试成功；Retry-After 为 0，避免测试真实等待。
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
                    // 使用稳定分类而非服务商原文，供界面标签使用。
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
        // 服务商要求等待 10 分钟，超过 8 秒上限。
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
        // 第一次装不下，修复后成功。
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
            return true;      // 已修动：确实落了压缩记录
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
            return false;     // 未修动：无可压缩内容，或模型可见状态未变
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

        // 审批事件必须包含提问理由，与记录拒绝理由同等重要。
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

        // 确实发生并发，否则上限没有意义。
        assertThat(probe.peak()).isGreaterThan(1);
        // 并发数不超过平台上限；上限由 AgentTurn.MAX_PARALLEL_TOOLS 定义。
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
                // 等待以制造重叠；否则任务瞬间结束，无法测量并发。
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            live.decrementAndGet();
            return ToolOutcome.ok("ok");
        }
    }

    @Test
    @DisplayName("【落盘目录】越过保留期的旧文件被扫掉，新的留着 —— 否则目录会无限增长")
    void oldSpillsAreSweptAway() throws IOException {
        // 把截止时间作为参数传入，否则要等 30 天才能验证。
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
        // 未过期文件仍可能被引用，必须保留。
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

        // 截断后重来一次并正常结束，而非整轮失败。
        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.newEvents()).anySatisfy(e -> {
            assertThat(e).isInstanceOf(PlatformInstruction.class);
            assertThat(((PlatformInstruction) e).text()).contains("拆成更小的几步");
        });
        // 第二次请求必须已包含该指令。
        assertThat(client.requests().get(1).messages())
                .anySatisfy(m -> assertThat(m.content()).contains("拆成更小的几步"));
    }

    @Test
    @DisplayName("模型要改文件：改动真的落到工作区了（工具与循环是通的）")
    void editsActuallyLandOnDisk() {
        // 先读再改，符合 edit_file 的观测要求，同时验证读操作已记账。
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
        // 持续返回截断结果，验证最终收场；重试行为由另一条用例覆盖。
        ScriptedLlm client = ScriptedLlm.always(
                new LlmResult("deepseek-flash", "length", "我说到一半被砍了",
                        List.of(), new TokenUsage(10, 4096, 4106, 0, 0)));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.TRUNCATED);
        // 初次加两次重试，共 3 次调用。
        assertThat(client.requests()).hasSize(3);
    }

    @Test
    @DisplayName("【取消】开始前就取消 → 一轮直接结束，不调用模型也不落事件")
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
        // 两次调用的输入量不同；单次调用的一轮中两笔用量恰好相等，容易混淆。
        ScriptedLlm client = new ScriptedLlm(
                new LlmResult("deepseek-flash", "tool_calls", "",
                        List.of(new ToolCall("c1", "read_file", "{\"path\":\"A.java\"}")),
                        new TokenUsage(1_000, 50, 1_050, 0, 0)),
                new LlmResult("deepseek-flash", "stop", "读完了", List.of(),
                        new TokenUsage(4_000, 30, 4_030, 0, 0)));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        // 累计用量为两次之和。
        assertThat(outcome.usage().inputTokens()).isEqualTo(5_000);
        // 上下文读数是最后一次调用的输入量。
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

        // 为 0，落库事件据此判断没有上下文读数；不能编造一个估算值。
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

        // 第二次请求必须包含收尾指令，而不是直接中断。
        String lastUserMessage = client.requests().get(1).messages().stream()
                .filter(m -> m.role() == LlmRole.USER)
                .map(LlmMessage::content)
                .reduce((a, b) -> b)
                .orElse("");
        assertThat(lastUserMessage).contains("预算即将用尽").contains("停止调用工具");
    }

    @Test
    @DisplayName("【取消】执行途中被用户取消 → 落 ToolCancelled 事件，不是 ToolResult(失败)")
    void userCancellationIsRecordedAsToolCancelled() {
        CancellationToken token = new CancellationToken();
        CommandExecutor cancelling = (ws, cmd, timeout, limit, cancel) -> {
            token.cancel();   // 模拟命令执行期间用户取消
            throw new IllegalStateException("进程被终止");
        };
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "run_command", "{\"command\":\"mvn test\"}"),
                ScriptedLlm.answer("好的"));

        TurnOutcome outcome = new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), client, MODEL, ModelCapabilities.UNKNOWN, ToolRegistry.standard(), cancelling,
                TokenBudget.DEFAULT, VerificationPlan.NONE,
                token));

        // 取消必须落成独立事件；落成失败结果会让重放后的模型重试用户刚取消的操作。
        assertThat(outcome.newEvents())
                .filteredOn(e -> e instanceof ToolCancelled)
                .singleElement()
                .satisfies(e -> assertThat(((ToolCancelled) e).callId()).isEqualTo("c1"));
        assertThat(outcome.newEvents()).noneMatch(e -> e instanceof ToolResult);
    }

    @Test
    @DisplayName("【能力门控】不支持工具调用的模型配上工具 → 开始前就失败，一次调用都不发")
    void modelWithoutToolSupportFailsFast() {
        // 不门控时，这类模型会静默忽略工具，最后表现为“跑很久但什么也没做”。
        ModelCapabilities noTools = new ModelCapabilities(false, ModelCapabilities.DEFAULT_CONTEXT_WINDOW,
                ModelCapabilities.DEFAULT_MAX_OUTPUT_TOKENS);
        ScriptedLlm client = new ScriptedLlm(ScriptedLlm.answer("不该走到这里"));

        assertThatThrownBy(() -> new AgentTurn().run(new TurnInput(sessionId, projection(), worktree,
                MODEL.systemPrompt(), client, MODEL, noTools, ToolRegistry.standard(),
                (ws, cmd, timeout, limit, cancel) -> new CommandResult(0, "", false, 0),
                TokenBudget.DEFAULT, VerificationPlan.NONE, CancellationToken.none())))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不支持工具调用");

        assertThat(client.requests()).isEmpty();   // 未发出任何请求
    }

    @Test
    @DisplayName("【模型名】结果取自响应回报的模型，不是请求参数里写的那个")
    void recordsTheModelTheServerActuallyUsed() {
        // 响应模型故意与请求里的 deepseek-flash 不同，否则断言分不出结果取自哪一边
        ScriptedLlm client = new ScriptedLlm(
                new LlmResult("deepseek-chat", "stop", "好的", List.of(), TokenUsage.UNKNOWN));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        assertThat(outcome.model()).isEqualTo("deepseek-chat");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【增量回调】工具开始执行之前，那条 ToolCallRequested 已经回调出去了")
    void toolCallIsReportedBeforeTheToolRuns() {
        // 崩溃恢复依赖请求在工具执行前已落库，否则无法补写 ToolInterrupted。
        List<PersistentEvent> reported = new ArrayList<>();
        List<Integer> reportedCountWhenCommandRan = new ArrayList<>();
        CommandExecutor recording = (ws, cmd, timeout, limit, cancel) -> {
            reportedCountWhenCommandRan.add(reported.size());
            return new CommandResult(0, "ok", false, 1);
        };
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "run_command", "{\"command\":\"mvn test\"}"),
                ScriptedLlm.answer("跑完了"));

        // appender 必须返回真实 seq；这里用自增号充当存储层。
        AtomicLong nextSeq = new AtomicLong(history.size());
        TurnOutcome outcome = new AgentTurn().run(input(client, recording), event -> {
            reported.add(event);
            return nextSeq.incrementAndGet();
        });

        // 命令执行时，ToolCallRequested 已经回调。
        assertThat(reportedCountWhenCommandRan).singleElement()
                .satisfies(count -> assertThat(count).isGreaterThanOrEqualTo(1));
        assertThat(reported.getFirst()).isInstanceOf(ToolCallRequested.class);
        // 回调事件与最终产出一致，顺序相同。
        assertThat(reported).isEqualTo(outcome.newEvents());
    }

    @Test
    @DisplayName("【增量回调】回调抛异常（比如 fencing token 已失效）当场中止整轮")
    void callbackFailureAbortsTheTurn() {
        // 回调负责带租约写入；租约失效表示树已被接管，本轮剩余产出不可信，必须立即停止。
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

        // 所有命令都需要审批，模拟不在免审批清单里的命令。
        TurnOutcome suspended = new AgentTurn().run(input(first, executor, command -> true));

        assertThat(suspended.status()).isEqualTo(TurnOutcome.Status.AWAITING_APPROVAL);
        assertThat(suspended.newEvents()).noneMatch(ToolResult.class::isInstance);
        assertThat(ran).isEmpty();          // 挂起时尚未执行

        // 用户批准，事件落库。
        commit(suspended.newEvents());
        commit(List.of(new ToolApprovalResolved("call_1", true, UserId.of("root"), null)));

        ScriptedLlm second = new ScriptedLlm(ScriptedLlm.answer("跑完了"));
        TurnOutcome resumed = new AgentTurn().run(input(second, executor, command -> true));

        // 断言必须落在执行器上；只检查事件会漏掉“事件齐全但命令未执行”。
        assertThat(ran).hasSize(1);
        ToolResult result = resumed.newEvents().stream()
                .filter(ToolResult.class::isInstance)
                .map(ToolResult.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("批准过的那条命令没有产生结果"));
        assertThat(result.callId()).isEqualTo("call_1");
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("total 0");

        // 模型看到真实输出，而不是批准占位文本。
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

        // 批准之后到来的是新用户消息而非 resume；否则旧的 rm -rf 会被重新执行。
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

        // 再跑一次；尾部已是结果和模型回答，不再是批准答复。
        new AgentTurn().run(input(ScriptedLlm.always(ScriptedLlm.answer("好")), executor, command -> true));

        assertThat(ran).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 把本轮事件追加到历史，模拟下一轮开始前的状态。 */
    @Test
    @DisplayName("【读过才许覆盖】账是从**投影**传下来的：上一轮读过才改得动")
    void theReadLedgerComesFromTheProjection() {
        // 上一轮读取了 A.java；历史中的调用记录就是本轮的账。
        commit(List.of(new ToolCallRequested("c1", "read_file", "{\"path\":\"A.java\"}")));
        writeFile("B.java", "class B {}\n");

        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c2", "write_file",
                        "{\"path\":\"A.java\",\"content\":\"class A { int x; }\\n\"}"),
                ScriptedLlm.toolCall("c3", "write_file",
                        "{\"path\":\"B.java\",\"content\":\"class B { int y; }\\n\"}"),
                ScriptedLlm.answer("好了"));

        TurnOutcome outcome = new AgentTurn().run(input(client));

        // 读过的文件可覆盖；账随投影跨轮传递。
        assertThat(readFile("A.java")).contains("int x");
        // 未读文件保持不变，并返回“还没读过”的失败说明。
        assertThat(readFile("B.java")).isEqualTo("class B {}\n");
        assertThat(outcome.newEvents()).filteredOn(ToolResult.class::isInstance)
                .extracting(event -> ((ToolResult) event).output())
                .anyMatch(output -> output.contains("还没读过"));
    }

    private void commit(List<PersistentEvent> events) {
        for (PersistentEvent event : events) {
            history.add(new StoredEvent(sessionId, history.size() + 1L,
                    Instant.parse("2026-09-25T10:00:00Z"), event));
        }
    }

    /** 从测试事件流建立一条投影；生产中投影跨轮存活。 */
    private ContextAssembler.Projection projection() {
        ContextAssembler.Projection projection =
                new ContextAssembler(userId -> null).projection(MODEL.systemPrompt());
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
        // 审批判据返回理由；这里统一收成固定文本，验证理由传递的用例自行提供判据。
        return inputAsking(client, executor, commandLine -> asksApproval.test(commandLine)
                ? Optional.of("测试：这条命令要人批一下")
                : Optional.empty());
    }

    /**
     * 直接接收审批理由的输入构造。不能重载为 {@code input}：两个重载都接收 String 函数，
     * 隐式类型 lambda 会产生歧义。
     *
     * @param client   模型客户端
     * @param executor 命令执行器
     * @param approval 审批判据
     * @return 本轮输入
     */
    private TurnInput inputAsking(LlmClient client, CommandExecutor executor,
                                  CommandApproval approval) {
        return new TurnInput(sessionId, projection(), worktree, MODEL.systemPrompt(), client, MODEL,
                ModelCapabilities.UNKNOWN, ToolRegistry.standard(), executor,
                TokenBudget.DEFAULT, VerificationPlan.NONE,
                CancellationToken.none(), approval, WebAccess.none());
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
