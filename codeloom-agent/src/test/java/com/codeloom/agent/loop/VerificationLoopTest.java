package com.codeloom.agent.loop;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.model.ModelCapabilities;
import com.codeloom.agent.support.ScriptedLlm;
import com.codeloom.agent.tool.ToolRegistry;
import com.codeloom.domain.event.PlatformInstruction;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.event.VerificationResult;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.SessionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动验证与自修循环：平台自行运行验证、把结论落成事件、允许有限次数自修，
 * 用尽后如实报告未通过。
 */
class VerificationLoopTest {

    @TempDir
    Path worktree;

    private final SessionId sessionId = SessionId.generate();
    private final List<StoredEvent> history = new ArrayList<>();

    private static final ModelConfig MODEL = new ModelConfig(
            ProviderId.of("deepseek"), "deepseek-flash", "你是协作开发助手。");

    private static final VerificationPlan VERIFY =
            new VerificationPlan(List.of("mvn", "-q", "test"), Duration.ofMinutes(5));

    @BeforeEach
    void seed() {
        history.add(new StoredEvent(sessionId, 1, Instant.parse("2026-09-25T10:00:00Z"),
                new UserMessage("把 x 改成 2")));
        // 预先在历史里读取 A.java，满足 edit_file 的观测要求，让本组测试聚焦验证循环。
        // 走历史而非脚本读取，也使账上记录为“版本未知”，覆盖该路径。
        history.add(new StoredEvent(sessionId, 2, Instant.parse("2026-09-25T10:00:01Z"),
                new ToolCallRequested("c0", "read_file", "{\"path\":\"A.java\"}")));
        history.add(new StoredEvent(sessionId, 3, Instant.parse("2026-09-25T10:00:02Z"),
                new ToolResult("c0", true, "1| class A {\n2|     int x = 1;\n3| }\n", false, null, 5)));
        writeFile("A.java", "class A {\n    int x = 1;\n}\n");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("动过文件 → 模型说完成后平台自动验证；通过则留下一条通过的证据")
    void passingVerificationLeavesEvidence() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "edit_file",
                        "{\"path\":\"A.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}"),
                ScriptedLlm.answer("改好了"));
        ScriptedExecutor executor = ScriptedExecutor.alwaysOk("Tests run: 5, Failures: 0");

        TurnOutcome outcome = new AgentTurn().run(input(client, executor));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);

        VerificationResult evidence = lastVerification(outcome);
        assertThat(evidence.passed()).isTrue();
        assertThat(evidence.command()).isEqualTo("mvn -q test");
        assertThat(evidence.exitCode()).isZero();
        assertThat(evidence.summary()).contains("Failures: 0");
        // 平台验证使用可辨认的 verify- 前缀 id。
        assertThat(evidence.callId()).startsWith("verify-");
    }

    @Test
    @DisplayName("【只读不验证】模型只是读代码，不该白白跑一遍构建")
    void readOnlyTurnDoesNotVerify() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "read_file", "{\"path\":\"A.java\"}"),
                ScriptedLlm.answer("x 现在是 1"));
        ScriptedExecutor executor = ScriptedExecutor.alwaysOk("不该被调用");

        TurnOutcome outcome = new AgentTurn().run(input(client, executor));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(executor.calls()).isZero();
        assertThat(outcome.newEvents()).noneMatch(e -> e instanceof VerificationResult);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【自修】验证失败 → 把失败作为【事件】注入 → 模型再改 → 第二次验证通过")
    void failureIsInjectedAndRepaired() {
        ScriptedLlm client = new ScriptedLlm(
                ScriptedLlm.toolCall("c1", "edit_file",
                        "{\"path\":\"A.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}"),
                ScriptedLlm.answer("改好了"),
                ScriptedLlm.toolCall("c2", "edit_file",
                        "{\"path\":\"A.java\",\"old_string\":\"int x = 2;\",\"new_string\":\"int x = 3;\"}"),
                ScriptedLlm.answer("这次真的好了"));
        ScriptedExecutor executor = new ScriptedExecutor(
                new CommandResult(1, "Tests run: 5, Failures: 1\n  ATest.xTest FAILED", false, 900),
                new CommandResult(0, "Tests run: 5, Failures: 0", false, 800));

        TurnOutcome outcome = new AgentTurn().run(input(client, executor));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(executor.calls()).isEqualTo(2);

        List<VerificationResult> evidence = allVerifications(outcome);
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).passed()).isFalse();
        assertThat(evidence.get(1).passed()).isTrue();

        // 失败必须落成事件，崩溃重放才能复现同一上下文；使用 PlatformInstruction
        // 而不是 UserMessage，以区分平台与用户发言。
        assertThat(outcome.newEvents()).noneMatch(e -> e instanceof UserMessage);
        assertThat(outcome.newEvents())
                .filteredOn(e -> e instanceof PlatformInstruction)
                .map(e -> ((PlatformInstruction) e).text())
                .anySatisfy(text -> assertThat(text)
                        .contains("平台自动跑了一次验证")
                        .contains("mvn -q test")
                        .contains("Failures: 1")
                        .contains("不要只是重跑一次验证"));

        // 模型确实看到了失败内容：共 4 次请求（改、答、再改、再答）。
        assertThat(client.requests()).hasSize(4);
        assertThat(client.requests().get(2).messages())
                .anySatisfy(m -> assertThat(m.content()).contains("Failures: 1"));
    }

    @Test
    @DisplayName("【上限】连续 3 次自修都不过 → 停在 VERIFICATION_FAILED，不假装成功")
    void givesUpAfterThreeAttempts() {
        // 先改一次文件使 mutated 为真，之后一直回答完成，验证持续失败。
        ScriptedLlm client = ScriptedLlm.repeating(
                ScriptedLlm.toolCall("c1", "edit_file",
                        "{\"path\":\"A.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}"),
                ScriptedLlm.answer("改好了"));
        ScriptedExecutor executor = ScriptedExecutor.alwaysFail("Tests run: 5, Failures: 1");

        TurnOutcome outcome = new AgentTurn().run(input(client, executor));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.VERIFICATION_FAILED);
        // 恰好 3 次。
        assertThat(executor.calls()).isEqualTo(3);
        assertThat(allVerifications(outcome)).hasSize(3)
                .allSatisfy(v -> assertThat(v.passed()).isFalse());
    }

    @Test
    @DisplayName("验证命令本身跑不起来时，如实说是命令的问题，别让模型去修不存在的 bug")
    void executorFailureIsNotReportedAsACodeProblem() {
        // 验证命令始终无法启动，会走到自修上限；模型脚本需持续供结果。
        ScriptedLlm client = ScriptedLlm.repeating(
                ScriptedLlm.toolCall("c1", "edit_file",
                        "{\"path\":\"A.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}"),
                ScriptedLlm.answer("改好了"));
        CommandExecutor broken = (ws, cmd, timeout, limit, cancel) -> {
            throw new IllegalStateException("这台机器上没有找到可用的 shell（bash）");
        };

        TurnOutcome outcome = new AgentTurn().run(input(client, broken));

        VerificationResult evidence = lastVerification(outcome);
        assertThat(evidence.passed()).isFalse();
        // exitCode 为 null 表示命令未运行，是“没验成”与“验证未通过”的分界。
        assertThat(evidence.exitCode()).isNull();
        assertThat(evidence.summary())
                .contains("验证命令未能执行")
                .contains("shell");
    }

    @Test
    @DisplayName("【漏洞回归】命令【失败】但可能已改文件 → 仍然要触发强制验证")
    void failedCommandStillTriggersVerification() {
        // 非零退出码的命令也可能已改文件；不能按 success 决定是否验证。
        ScriptedLlm client = ScriptedLlm.repeating(
                ScriptedLlm.toolCall("c1", "run_command", "{\"command\":\"mvn -q fmt:apply\"}"),
                ScriptedLlm.answer("好了"));
        ScriptedExecutor executor = ScriptedExecutor.alwaysFail("命令失败但文件已改");

        TurnOutcome outcome = new AgentTurn().run(input(client, executor));

        // 执行器共 4 次：模型 1 次 run_command 加平台 3 次验证，证明工具失败仍触发验证。
        assertThat(executor.calls()).isEqualTo(4);
        assertThat(allVerifications(outcome)).isNotEmpty();
        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.VERIFICATION_FAILED);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 从测试事件流建立投影；生产中的投影跨轮存活。 */
    private ContextAssembler.Projection projection() {
        ContextAssembler.Projection projection =
                new ContextAssembler(userId -> null).projection(MODEL.systemPrompt());
        projection.fold(history);
        return projection;
    }

    private TurnInput input(LlmClient client, CommandExecutor executor) {
        return new TurnInput(sessionId, projection(), worktree, MODEL.systemPrompt(), client, MODEL,
                ModelCapabilities.UNKNOWN, ToolRegistry.standard(), executor,
                TokenBudget.DEFAULT, VERIFY,
                CancellationToken.none());
    }

    private static VerificationResult lastVerification(TurnOutcome outcome) {
        return allVerifications(outcome).getLast();
    }

    private static List<VerificationResult> allVerifications(TurnOutcome outcome) {
        return outcome.newEvents().stream()
                .filter(e -> e instanceof VerificationResult)
                .map(e -> (VerificationResult) e)
                .toList();
    }

    private void writeFile(String path, String content) {
        try {
            Files.writeString(worktree.resolve(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 按脚本返回验证结果的执行器。 */
    private static final class ScriptedExecutor implements CommandExecutor {

        private final Deque<CommandResult> script;
        private final CommandResult fallback;
        private int calls;

        ScriptedExecutor(CommandResult... results) {
            this.script = new ArrayDeque<>(List.of(results));
            this.fallback = null;
        }

        private ScriptedExecutor(CommandResult fallback, boolean ignored) {
            this.script = new ArrayDeque<>();
            this.fallback = fallback;
        }

        static ScriptedExecutor alwaysOk(String output) {
            return new ScriptedExecutor(new CommandResult(0, output, false, 500), true);
        }

        static ScriptedExecutor alwaysFail(String output) {
            return new ScriptedExecutor(new CommandResult(1, output, false, 500), true);
        }

        int calls() {
            return calls;
        }

        @Override
        public CommandResult execute(Path worktree, String commandLine, Duration timeout,
                                     int maxOutputChars, CancellationToken cancellation) {
            calls++;
            if (fallback != null) {
                return fallback;
            }
            CommandResult next = script.poll();
            if (next == null) {
                throw new IllegalStateException("验证脚本已用完（第 " + calls + " 次调用）");
            }
            return next;
        }
    }

}
