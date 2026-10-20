package com.codeloom.agent.tool;

import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.port.CommandTermination;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;

/**
 * 在工作区里执行一行 shell 命令（构建、测试、清理等）。
 *
 * <p>接收整行字符串并交给 shell 解释，因此管道、重定向和 {@code &&} 可用；取舍见
 * {@code docs/decisions/architecture/2026-09-24-commands-are-whole-lines-through-bash.md}。
 *
 * <p>需要审批不等于被拒绝：描述必须说清哪些情况会先询问，以及询问后仍会执行，
 * 否则模型会自我审查。审批判据由 {@link ToolContext} 注入，不在本类或 agent 模块。
 */
public final class RunCommandTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** 截断提示贴在开头：保留的是最后一段，必须先说明前面有内容被丢弃。 */
    private static final String HEAD_DROPPED_NOTICE =
            "（这条命令的输出太长，**开头**已被丢掉，下面是保留下来的最后一段）\n";

    @Override
    public String name() {
        return ToolNames.RUN_COMMAND;
    }

    @Override
    public String description() {
        return "在工作区里执行一行 shell 命令（构建、跑测试、看目录、清理产物都用它）。"
                + "用法和终端里一样：管道、重定向都能用；几条命令可以用 &&、|| 或 ; 串起来。"
                + "工作目录是项目根；要换目录就写 cd 子目录 && 命令"
                + "（每条命令都从项目根重新开始）。"
                + "串联会**逐段检查**；有几种情况会先请用户确认一次：不在免审批清单里的程序；"
                + "含变量、命令替换、管道、重定向、波浪号、或引号不配对的写法；"
                + "以及用路径写出来的可执行文件（如 ./gradlew）。"
                + "那是问一句，不是被拒绝 —— 用户确认之后命令就会执行。"
                + "输出过长只保留尾部；超过 timeout_seconds 会被强杀整棵进程树，"
                + "**被杀之前已经打出来的输出照样给你** —— 那正是判断它卡在哪儿的依据。";
    }

    @Override
    public String parametersJsonSchema() {
        // JSON 文本里的双引号必须转义，否则文本块生成非法 JSON。
        return """
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "一整行命令，例如 mvn -q test、git status --short、rm -rf dist"
                    },
                    "timeout_seconds": {
                      "type": "integer",
                      "description": "超时秒数，默认 300"
                    }
                  },
                  "required": ["command"]
                }
                """;
    }

    @Override
    public ToolSurface surface() {
        // 主语是命令行；审批与补跑都依据这份声明取命令。
        return ToolSurface.of(ToolSurface.Shape.EXECUTE, "运行", "command");
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        JsonNode commandNode = arguments.path("command");
        if (!commandNode.isTextual()) {
            // 数组形式是常见错误，明确说明正确形状。
            return ToolOutcome.failed("command 必须是【字符串】—— 一整行命令，"
                    + "例如 \"mvn -q test\"，不是数组也不是对象。");
        }
        String commandLine = commandNode.asText();
        if (commandLine.isBlank()) {
            return ToolOutcome.failed("command 不能是空的。");
        }

        // 审批判据由调用方注入并返回理由；理由会进入审批事件供用户确认。
        // 挂起不是拒绝：命令尚未执行，整轮等待答复。
        Optional<String> whyAsk = context.requiresApproval().reasonToAsk(commandLine);
        if (whyAsk.isPresent()) {
            return ToolOutcome.approvalRequired(whyAsk.get());
        }

        int timeoutSeconds = arguments.path("timeout_seconds").asInt(DEFAULT_TIMEOUT_SECONDS);
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));

        CommandResult result;
        try {
            result = context.commandExecutor()
                    .execute(context.worktree(), commandLine, timeout, ToolLimits.MAX_OUTPUT_CHARS,
                            context.cancellation());
        } catch (RuntimeException e) {
            // 到这里只剩“无法启动”或“等待时被中断”；超时与取消是返回值（见 CommandTermination）。
            // 取消优先：用户在同一瞬间按下 Esc 时，应记录用户意图而非启动失败。
            if (context.cancellation().isCancelled()) {
                return new ToolOutcome(false, "命令被用户取消，进程树已被终止", false, null, 0,
                        ToolOutcome.Failure.CANCELLED, true);
            }
            return ToolOutcome.failed("命令未能执行: " + e.getMessage());
        }

        // 结束方式决定失败性质，三种情形对模型含义不同。
        ToolOutcome outcome = switch (result.termination()) {
            case COMPLETED -> completed(result);
            case TIMED_OUT -> timedOut(timeout, result);
            case CANCELLED -> cancelled(result);
        };
        // 截断必须说明丢弃的是开头；命令输出保尾（见
        // docs/decisions/architecture/2026-10-15-tool-output-keeps-the-tail.md）。
        return result.truncated()
                ? outcome.withOutput(HEAD_DROPPED_NOTICE + outcome.output())
                : outcome;
    }

    /** 命令自己跑完了。退出码是什么由调用方解释，这里原样交给模型。 */
    private static ToolOutcome completed(CommandResult result) {
        String body = "退出码 " + result.exitCode() + "，耗时 " + result.durationMs() + " ms\n"
                + (result.output().isBlank() ? "(没有输出)" : result.output());

        // mutated 恒为 true，包括失败：非零退出码的命令也可能已改文件，按 success 判断会漏掉验证。
        return new ToolOutcome(result.success(), body, result.truncated(),
                result.exitCode(), result.durationMs(),
                result.success() ? ToolOutcome.Failure.NONE : ToolOutcome.Failure.INTERNAL, true);
    }

    /**
     * 超时被杀的结果。必须说明不是命令自身失败、超时多久、下一步可以调大
     * {@code timeout_seconds} 或换跑法，并带回终止前的输出。见 {@link CommandTermination}。
     *
     * @param timeout 超时时长
     * @param result  命令结果
     * @return 超时结果
     */
    private static ToolOutcome timedOut(Duration timeout, CommandResult result) {
        String body = "命令超过 " + timeout.toSeconds() + " 秒还没结束，进程树已被终止"
                + "（**不是它自己失败的**）。要跑更久就把 timeout_seconds 调大，"
                + "或者让它把结果写进文件、下一轮用 read_file 看。\n"
                + (result.output().isBlank()
                        ? "(被终止前它没有输出)"
                        : "被终止前它已经输出的部分：\n" + result.output());

        // 被杀前可能已经改过文件，mutated 仍为 true。
        return new ToolOutcome(false, body, result.truncated(), null, result.durationMs(),
                ToolOutcome.Failure.TIMEOUT, true);
    }

    /** 用户取消；循环会落成 ToolCancelled 事件。 */
    private static ToolOutcome cancelled(CommandResult result) {
        return new ToolOutcome(false, "命令被用户取消，进程树已被终止", false, null,
                result.durationMs(), ToolOutcome.Failure.CANCELLED, true);
    }
}
