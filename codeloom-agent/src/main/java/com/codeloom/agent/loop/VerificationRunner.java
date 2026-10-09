package com.codeloom.agent.loop;

import com.codeloom.domain.event.VerificationResult;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.CommandResult;
import com.codeloom.domain.port.CommandTermination;
import java.nio.file.Path;

/**
 * 跑一次"平台自动验证"，产出结论。
 *
 * <h2>为什么单独一个类</h2>
 * 「验证结论怎么产生、失败怎么措辞、证据留多长」是同一件事，而它有两条调用路径：
 * {@code AgentTurn}（一轮结束之后）和 {@code MergeService}（合并到主干之后）。
 * 收在一个类里，两条路径的输出上限、截断方式、"命令跑不起来"的文案才不会各写一遍
 *（否则改一处阈值就会漏掉另一处，**同一套验证在两条路径上结论看起来不一样**）。
 *
 * <p>刻意**不负责落库**：两个调用方的事务和"往哪儿写"完全不同
 * （一个是循环里的事件缓冲区，一个是带着 fencing token 直接追加）。
 * 这个类只回答"跑了什么、结果如何、证据是哪一段"。
 */
public final class VerificationRunner {

    /** 落进事件的输出摘要长度。事件表要长期保存，不能把整份构建日志塞进去。 */
    public static final int SUMMARY_CHARS = 2_000;

    /** 注入回模型的失败输出长度。这里可以宽一些 —— 模型需要足够上下文才能改对。 */
    public static final int INJECT_CHARS = 6_000;

    /** 交给执行器的上限：先拿到足够多，再截成上面那两份。 */
    private static final int CAPTURE_CHARS = INJECT_CHARS;

    /** 「命令自己没跑起来」的文案。两条路径共用，免得同一件事有两种说法。 */
    private static final String NOT_RUN_PREFIX = "验证命令未能执行：";

    /**
     * 跑一次。
     *
     * <p>命令**根本跑不起来**时（没有 shell、超时、进程起不来）不会抛异常，而是产出
     * 一条 {@code passed = false} 的结论 —— 因为这两件事必须分清楚：一个是"代码有问题"，
     * 一个是"验证本身没跑成"。混在一起的话模型会去修一个根本不存在的问题。
     *
     * <p>**命令自己失败**（退出码非零）走的是另一条路：那是正常的返回值，
     * 结论就是"验证未通过"。这才是模型该看的东西。
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

        // **被我们杀掉的验证，不算"验证未通过"。**
        //
        // 这两件事对模型的含义正相反：一个是"代码有问题"，另一个是"这次没验成"。
        // 混成前者的话，它会去修一个根本不存在的问题 —— 而它越改越远，因为
        // 真相只是这次构建跑得比时限久。
        //
        // 从前这条区分是靠 ProcessRunner 抛异常做到的（异常 → 上面那个 catch）。
        // 现在超时和取消是**返回值**了（为的是留住半截输出），所以得在这里显式判一次。
        if (result.termination() != CommandTermination.COMPLETED) {
            String why = switch (result.termination()) {
                case TIMED_OUT -> "超过 " + plan.timeout().toSeconds() + " 秒还没结束，进程树已被终止";
                case CANCELLED -> "被用户取消了";
                case COMPLETED -> throw new IllegalStateException("上面刚判过不是 COMPLETED");
            };
            // 半截输出照样带上：它常常直接说明为什么没跑完（依赖下载卡住、测试挂死……）
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
     * 取输出的**末尾**。
     *
     * <p>测试失败信息、编译错误都在输出的最后，前面全是下载依赖之类的噪音 ——
     * 取头部会把最该看的那几行截掉。这个取舍对两条路径是同一个。
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
     * @param evidence       落进事件流的那条结论
     * @param outputForModel 要注入回模型的输出。**比 evidence 里那份宽** ——
     *                       模型需要足够上下文才能改对，而事件表要长期保存
     */
    public record VerificationRun(VerificationResult evidence, String outputForModel) {
    }

    private VerificationRunner() {
    }
}
