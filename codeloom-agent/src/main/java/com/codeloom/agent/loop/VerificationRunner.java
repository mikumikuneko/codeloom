package com.codeloom.agent.loop;

import com.codeloom.domain.event.VerificationResult;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.port.CommandTermination;
import java.nio.file.Path;

/**
 * 跑一次平台自动验证并产出结论。{@code AgentTurn} 与 {@code MergeService} 共用，
 * 保证输出上限、截断方式和失败措辞一致。
 *
 * <p>刻意不负责落库：两个调用方的写入方式不同。本类只回答“跑了什么、结果如何、
 * 证据是哪一段”。
 */
public final class VerificationRunner {

    /** 落进事件的输出摘要长度。 */
    public static final int SUMMARY_CHARS = 2_000;

    /** 注入回模型的失败输出长度；比事件摘要宽，供模型定位问题。 */
    public static final int INJECT_CHARS = 6_000;

    /** 交给执行器的采集上限，之后再截成事件与注入两份。 */
    private static final int CAPTURE_CHARS = INJECT_CHARS;

    /** “命令没跑起来”的固定前缀，两条调用路径共用。 */
    private static final String NOT_RUN_PREFIX = "验证命令未能执行：";

    /**
     * 跑一次验证。
     *
     * <p>命令根本跑不起来（没有 shell、超时、进程起不来）时不抛异常，而产出
     * {@code passed = false} 且 exitCode 为 null 的结论，与“命令执行了但失败”区分开。
     * 后者是正常返回值，结论就是验证未通过。
     *
     * @param plan         验证计划
     * @param callId       证据的调用 id
     * @param worktree     工作区
     * @param commands     命令执行器
     * @param cancellation 取消信号
     * @return 证据与注入模型的输出
     */
    public static VerificationRun run(VerificationPlan plan, String callId, Path worktree,
                                      CommandExecutor commands, CancellationToken cancellation) {
        CommandResult result;
        try {
            result = commands.execute(worktree, plan.commandLine(), plan.timeout(),
                    CAPTURE_CHARS, cancellation);
        } catch (RuntimeException e) {
            String notRun = NOT_RUN_PREFIX + e.getMessage();
            return new VerificationRun(
                    new VerificationResult(callId, plan.commandLine(), false, null, notRun), notRun);
        }

        // 超时或取消表示“这次没验成”，不是“代码有问题”；必须与失败区分，否则模型会修错方向。
        if (result.termination() != CommandTermination.COMPLETED) {
            String why = switch (result.termination()) {
                case TIMED_OUT -> "超过 " + plan.timeout().toSeconds() + " 秒还没结束，进程树已被终止";
                case CANCELLED -> "被用户取消了";
                case COMPLETED -> throw new IllegalStateException("上面刚判过不是 COMPLETED");
            };
            // 半截输出常能说明为什么没跑完。
            String notRun = NOT_RUN_PREFIX + why + "\n" + tail(result.output(), SUMMARY_CHARS);
            return new VerificationRun(
                    new VerificationResult(callId, plan.commandLine(), false, null, notRun), notRun);
        }

        return new VerificationRun(
                new VerificationResult(callId, plan.commandLine(), result.success(),
                        result.exitCode(), tail(result.output(), SUMMARY_CHARS)),
                tail(result.output(), INJECT_CHARS));
    }

    /**
     * 取输出的末尾：测试失败与编译错误在尾部，前面多为依赖下载噪音。见
     * {@code docs/decisions/architecture/2026-10-15-tool-output-keeps-the-tail.md}。
     *
     * @param text     原始输出
     * @param maxChars 最大字符数
     * @return 末尾文本；为 null 时返回空串
     */
    public static String tail(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars
                ? text
                : "…（前文省略）\n" + text.substring(text.length() - maxChars);
    }

    /**
     * @param evidence       落进事件流的结论
     * @param outputForModel 注入回模型的输出，比事件摘要宽
     */
    public record VerificationRun(VerificationResult evidence, String outputForModel) {
    }

    private VerificationRunner() {
    }
}
