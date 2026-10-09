package com.codeloom.workspace.git;

import com.codeloom.workspace.exec.ProcessOutcome;

import java.util.List;

/**
 * git 命令以非零退出码结束，**且这个退出码不是"冲突"**。
 *
 * <h2>为什么必须把 1 和 128 分开</h2>
 * git 的退出码不是统一的：
 * <ul>
 *   <li>{@code 1} —— {@code merge} 时的"有冲突"。这是**正常业务路径**，
 *       不该走异常，应当返回 {@code MergeResult.CONFLICT} 交给用户裁决</li>
 *   <li>{@code 128} —— 致命错误。实验里踩到的一种：提交者身份缺失，
 *       git 会在**冲突检测之前**就以 128 退出</li>
 * </ul>
 *
 * <p>如果把非零一律当冲突处理，用户会看到一份**空的冲突清单**，
 * 然后完全不知道发生了什么 —— 这是个很难查的 bug。
 *
 * <h2>为什么没有取信息的接口</h2>
 * 命令、退出码、输出都拼进 message 里了（见构造函数），所以这个异常**只带一句话**，
 * 在任何地方打出来都是完整的，不必再有人去拼。需要那些值的调用方，
 * 现场本来就有 {@link ProcessOutcome}。再挂几个没人用的访问器只会变成
 * "有人靠它做判断"的假信号。
 */
public class GitCommandException extends RuntimeException {

    public GitCommandException(List<String> command, ProcessOutcome outcome, String message) {
        super(message + "\n  命令: " + String.join(" ", command)
                + "\n  退出码: " + outcome.exitCode()
                + "\n  输出: " + abbreviate(outcome.combinedOutput()));
    }

    private static String abbreviate(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= 2000 ? trimmed : trimmed.substring(0, 2000) + "\n  …（已截断）";
    }
}
