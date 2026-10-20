package com.codeloom.agent.tool;

import com.codeloom.agent.web.WebAccess;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 工具执行所需的上下文。所有字段在真实调用路径上都有值，不提供省略审批判据或读账本的重载。
 *
 * @param worktree         隔离工作区；文件类工具把路径限制在其中，run_command 交给执行器，不受路径守卫约束
 * @param commandExecutor  命令执行器，接收模型写的整行命令
 * @param cancellation     取消信号
 * @param requiresApproval 整行命令的审批判据，返回需要批准的理由；由调用方注入
 * @param reads            这条会话读过的文件；有状态，由调用方持有
 * @param web              联网能力；无联网能力时为 {@link WebAccess#none()}
 */
public record ToolContext(Path worktree,
                          CommandExecutor commandExecutor,
                          CancellationToken cancellation,
                          CommandApproval requiresApproval,
                          ReadLedger reads,
                          WebAccess web) {

    public ToolContext {
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(commandExecutor, "commandExecutor");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(requiresApproval, "requiresApproval");
        Objects.requireNonNull(reads, "reads");
        Objects.requireNonNull(web, "web");
        if (!worktree.isAbsolute()) {
            throw new IllegalArgumentException("工作区路径必须是绝对路径: " + worktree);
        }
    }
}
