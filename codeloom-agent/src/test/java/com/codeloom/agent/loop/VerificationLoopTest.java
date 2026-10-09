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
 * 自动验证 + 自修循环：平台自己跑验证、把结论落成事件、失败让模型自修但有次数上限、用尽就如实报"没通过"。
 *
 * <p>面试里问"AI 写的代码你怎么敢信"，答案全在这个类的断言里：
 * 平台**自己**跑验证、把结论落成事件、失败了让模型自修但**次数有上限**、
 * 用尽了就诚实地说"没通过"而不是假装成功。
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
        // 这条会话在更早的时候读过 A.java。**这一笔是必须的**：edit_file 要求
        // "改之前观测过"（见 ReadLedger）—— 真实会话里模型也是读了才改。
        //
        // 用历史（而不是让脚本先读一次）是有意的：投影里只有路径、**没有版本**，
        // 于是账上记的是"版本未知"那一种，正好也把那一条路走了一遍。
        // 这几条测试验的是**验证循环**，不该被"改之前要读过"那条规则搅进来
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
        // 平台发起的验证用可辨认的 id，和模型的 call_xxx 区分开
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

        // 失败被注入成一条【事件】（不是临时拼接的提示词）—— 不落库的话，
        // 崩溃重放时组装出的上下文和当时不一致，证据链就对不上了。
        //
        // 而且它必须是 PlatformInstruction【不是 UserMessage】：
        // 混用会让审计流里分不清"用户说的"和"平台说的"，
        // 而回滚恰恰是按 turn 对齐代码与对话的。
        assertThat(outcome.newEvents()).noneMatch(e -> e instanceof UserMessage);
        assertThat(outcome.newEvents())
                .filteredOn(e -> e instanceof PlatformInstruction)
                .map(e -> ((PlatformInstruction) e).text())
                .anySatisfy(text -> assertThat(text)
                        .contains("平台自动跑了一次验证")
                        .contains("mvn -q test")
                        .contains("Failures: 1")
                        .contains("不要只是重跑一次验证"));

        // 模型确实看到了失败内容。
        // 请求 4 次：①改文件 ②说改好了(验证#1失败) ③再改 ④再说改好了(验证#2通过)
        assertThat(client.requests()).hasSize(4);
        assertThat(client.requests().get(2).messages())
                .anySatisfy(m -> assertThat(m.content()).contains("Failures: 1"));
    }

    @Test
    @DisplayName("【上限】连续 3 次自修都不过 → 停在 VERIFICATION_FAILED，不假装成功")
    void givesUpAfterThreeAttempts() {
        // 先改一次文件（让 mutated 为真），之后就一直是"改好了" —— 验证永远失败
        ScriptedLlm client = ScriptedLlm.repeating(
                ScriptedLlm.toolCall("c1", "edit_file",
                        "{\"path\":\"A.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}"),
                ScriptedLlm.answer("改好了"));
        ScriptedExecutor executor = ScriptedExecutor.alwaysFail("Tests run: 5, Failures: 1");

        TurnOutcome outcome = new AgentTurn().run(input(client, executor));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.VERIFICATION_FAILED);
        // 3 次，不多不少
        assertThat(executor.calls()).isEqualTo(3);
        assertThat(allVerifications(outcome)).hasSize(3)
                .allSatisfy(v -> assertThat(v.passed()).isFalse());
    }

    @Test
    @DisplayName("验证命令本身跑不起来时，如实说是命令的问题，别让模型去修不存在的 bug")
    void executorFailureIsNotReportedAsACodeProblem() {
        // 验证命令一直跑不起来（这台机器上没有 shell），所以会走到自修上限，
        // 模型脚本要能一直供得上
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
        // exitCode 是 null —— 它压根没跑起来，这是"验证没跑成"和"验证没过"的分界
        assertThat(evidence.exitCode()).isNull();
        assertThat(evidence.summary())
                .contains("验证命令未能执行")
                .contains("shell");
    }

    @Test
    @DisplayName("【漏洞回归】命令【失败】但可能已改文件 → 仍然要触发强制验证")
    void failedCommandStillTriggersVerification() {
        // 退出码非零的命令完全可能改了文件（格式化器修完报错退出、编译中途产物）。
        // 早期实现用 `success() && mutatesWorkspace()` 判断，这些改动会逃过验证。
        ScriptedLlm client = ScriptedLlm.repeating(
                ScriptedLlm.toolCall("c1", "run_command", "{\"command\":\"mvn -q fmt:apply\"}"),
                ScriptedLlm.answer("好了"));
        ScriptedExecutor executor = ScriptedExecutor.alwaysFail("命令失败但文件已改");

        TurnOutcome outcome = new AgentTurn().run(input(client, executor));

        // 执行器被调用 4 次 = 模型那 1 次 run_command + 平台 3 次验证。
        // 关键是 > 1：证明即便工具**失败**，强制验证照样跑了。
        assertThat(executor.calls()).isEqualTo(4);
        assertThat(allVerifications(outcome)).isNotEmpty();
        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.VERIFICATION_FAILED);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 这条会话的投影：从测试那份事件流建一条活的（生产里它跨轮活着）。 */
    private ContextAssembler.Projection projection() {
        ContextAssembler.Projection projection =
                new ContextAssembler().projection(MODEL.systemPrompt());
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

    /** 按脚本依次返回验证结果的执行器。 */
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
