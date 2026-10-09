package com.codeloom.agent.tool;

import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.port.CommandTermination;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;

/**
 * 在工作区里执行一行 shell 命令（构建、跑测试、清理产物等）。
 *
 * <h2>收的是整行命令</h2>
 * 模型最自然的写法就是一行文本，所以这里就收一行：
 * {@code "mvn -q test"}、{@code "grep -rn foo src | head"}。它原样交给一层 shell
 * 解释（见 {@code CommandShell}），于是管道、重定向、{@code &&} 都能用 ——
 * 两家参考实现都是这个形态（Claude Code 的 Bash 工具、deepseek-harness 的命令执行接口）。
 * 完整的取舍写在 {@code CommandExecutor} 的类注释里。
 *
 * <p>收字符串数组那个形状下"我们不走 shell"，注入问题天然不存在，代价是模型写不了
 * 管道、重定向，而且在 Windows 上第一个词必须是 PATH 上真实存在的可执行文件 ——
 * {@code rm}、{@code mv}、{@code ls} 全不是，于是模型最自然的那些词一个都跑不起来。
 *
 * <h2>要审批 ≠ 被拒绝</h2>
 * 描述里必须说清这一点：写成"可执行文件必须在白名单里，否则会被拒绝"的话，模型会
 * **自我审查** —— 它不去试 {@code curl}、{@code javac}，因为它以为自己一用就会被拒。
 * 真话是"会挂起来问人一次"。
 *
 * <p>所以描述里说清两件事：**什么情况会先问一次**，以及**问不是拒**。
 *
 * <p>命令最终能不能跑由人决定（审批那一层把命令摆出来），判据本身不在这里、
 * 也不在 agent 模块里 —— 见 {@link ToolContext}。
 */
public final class RunCommandTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_CHARS = 100_000;

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "在工作区里执行一行 shell 命令（构建、跑测试、看目录、清理产物都用它）。"
                + "用法和终端里一样：管道、重定向、&& 都能用。"
                + "工作目录是项目根；要换目录就写 cd 子目录 && 命令"
                + "（每条命令都从项目根重新开始）。"
                + "有几种情况会先请用户确认一次：不在免审批清单里的程序；"
                + "含变量、命令替换、分号、管道、重定向、波浪号、或引号不配对的写法；"
                + "以及用路径写出来的可执行文件（如 ./gradlew）。"
                + "那是问一句，不是被拒绝 —— 用户确认之后命令就会执行。"
                + "输出过长只保留尾部；超过 timeout_seconds 会被强杀整棵进程树，"
                + "**被杀之前已经打出来的输出照样给你** —— 那正是判断它卡在哪儿的依据。";
    }

    @Override
    public String parametersJsonSchema() {
        // 这段是【JSON 文本】，所以描述里不能再出现裸的双引号：文本块里的 \" 会先被
        // Java 解析成 "，拼出来的就是非法 JSON。
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
        // 主语是那行命令 —— 后端审批那条路也是靠这个声明取命令的，
        // 不再由 AgentTurn 自己猜"只有 run_command 的参数里有 command"
        return ToolSurface.of(ToolSurface.Shape.EXECUTE, "运行", "command");
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        JsonNode commandNode = arguments.path("command");
        if (!commandNode.isTextual()) {
            // 写成数组是这里最容易犯的错。说清楚，别让它去猜
            return ToolOutcome.failed("command 必须是【字符串】—— 一整行命令，"
                    + "例如 \"mvn -q test\"，不是数组也不是对象。");
        }
        String commandLine = commandNode.asText();
        if (commandLine.isBlank()) {
            return ToolOutcome.failed("command 不能是空的。");
        }

        // 需不需要人来批，由调用方注入的判据说了算 —— 判据住在 workspace 模块
        // （名单 + 路径范围 + 这行命令看不看得懂），而 agent 刻意不依赖它，
        // 所以判据只能从上面传进来。
        //
        // 判据回来的是**理由**，不是布尔：那句话会跟着 ToolApprovalRequested 落进事件流，
        // 人要看着它点同意（见 CommandApproval 的类注释）。
        // 注意这**不是**"被拒绝"：那条命令还没执行，整轮会就此挂起等人答复
        Optional<String> whyAsk = context.requiresApproval().reasonToAsk(commandLine);
        if (whyAsk.isPresent()) {
            return ToolOutcome.approvalRequired(whyAsk.get());
        }

        int timeoutSeconds = arguments.path("timeout_seconds").asInt(DEFAULT_TIMEOUT_SECONDS);
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));

        CommandResult result;
        try {
            // 只传工作目录就够了 —— 端口收 {@code Path}，不需要现场伪造一个 Workspace。
            result = context.commandExecutor()
                    .execute(context.worktree(), commandLine, timeout, MAX_OUTPUT_CHARS,
                            context.cancellation());
        } catch (RuntimeException e) {
            // 走到这里只剩"起不来"和"等待时被线程中断"—— 超时和取消现在是**返回值**了
            //（它们有半截输出要带回来，见 CommandTermination）
            //
            // 取消仍然要排在前面：用户按了 Esc、同一个瞬间进程又起不来时，
            // "用户要停"是他表达出来的意图，比"这条命令没跑起来"更该被记下来
            if (context.cancellation().isCancelled()) {
                return new ToolOutcome(false, "命令被用户取消，进程树已被终止", false, null, 0,
                        ToolOutcome.Failure.CANCELLED, true);
            }
            return ToolOutcome.failed("命令未能执行: " + e.getMessage());
        }

        // **谁结束了它**，决定这次调用报什么失败 —— 三种情形对模型的含义完全不同
        return switch (result.termination()) {
            case COMPLETED -> completed(result);
            case TIMED_OUT -> timedOut(timeout, result);
            case CANCELLED -> cancelled(result);
        };
    }

    /** 命令自己跑完了。退出码是什么由调用方解释，这里原样交给模型。 */
    private static ToolOutcome completed(CommandResult result) {
        String body = "退出码 " + result.exitCode() + "，耗时 " + result.durationMs() + " ms\n"
                + (result.output().isBlank() ? "(没有输出)" : result.output());

        // mutated 恒为 true，**包括失败的情况**：退出码非零的命令完全可能已经改了文件
        // （格式化器修完报错退出、编译中途产出部分产物）。如果按 success 判断，
        // 这些改动就会逃过平台的强制验证 —— 那是最坏方向的错误。
        return new ToolOutcome(result.success(), body, result.truncated(),
                result.exitCode(), result.durationMs(),
                result.success() ? ToolOutcome.Failure.NONE : ToolOutcome.Failure.INTERNAL, true);
    }

    /**
     * 超时被杀。
     *
     * <h2>三件事都得说</h2>
     * <ol>
     *   <li><b>不是它自己失败的</b> —— 不说的话，模型会以为命令写错了，
     *       于是原样再跑一遍，再超时一次；</li>
     *   <li><b>停了多久</b> —— 它参数里写的就是秒数，得让它对得上；</li>
     *   <li><b>下次怎么办</b> —— 调大 {@code timeout_seconds}，或者换个跑法。</li>
     * </ol>
     *
     * <p>而且**把被杀之前已经打出来的输出一并给它**：跑到哪一步卡住的，
     * 全在那半截里（这条路从前抛异常，那段输出就跟着丢了 ——
     * 见 {@link CommandTermination} 的类注释）。
     */
    private static ToolOutcome timedOut(Duration timeout, CommandResult result) {
        String body = "命令超过 " + timeout.toSeconds() + " 秒还没结束，进程树已被终止"
                + "（**不是它自己失败的**）。要跑更久就把 timeout_seconds 调大，"
                + "或者让它把结果写进文件、下一轮用 read_file 看。\n"
                + (result.output().isBlank()
                        ? "(被终止前它没有输出)"
                        : "被终止前它已经输出的部分：\n" + result.output());

        // mutated 同样是 true：被杀掉之前它已经跑了一会儿，完全可能已经改过文件
        return new ToolOutcome(false, body, result.truncated(), null, result.durationMs(),
                ToolOutcome.Failure.TIMEOUT, true);
    }

    /** 用户按了 Esc。落成 ToolCancelled 事件（见 {@code AgentTurn.recordOutcome}）。 */
    private static ToolOutcome cancelled(CommandResult result) {
        return new ToolOutcome(false, "命令被用户取消，进程树已被终止", false, null,
                result.durationMs(), ToolOutcome.Failure.CANCELLED, true);
    }
}
