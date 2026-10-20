package com.codeloom.workspace.git;

import com.codeloom.workspace.exec.ProcessOutcome;

import java.util.List;

/**
 * git 命令以非零退出码结束，且该退出码不是 merge 的"有冲突"。
 *
 * <p>git 的退出码不统一：{@code merge} 的 1 表示冲突，是正常业务路径，应返回
 * {@link com.codeloom.domain.port.MergeResult.Status#CONFLICT} 交给用户裁决；128 是
 * 致命错误（例如缺少提交者身份，git 会在冲突检测前退出）。把非零一律当冲突会得到
 * 一份空冲突清单，用户无从判断。
 *
 * <p>命令、退出码与输出已拼进 message，因此异常不提供取值接口；需要这些值的调用方
 * 本就有 {@link ProcessOutcome}。
 */
public class GitCommandException extends RuntimeException {

    /**
     * @param command 执行的命令行
     * @param outcome 失败结果
     * @param message 结论
     */
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
