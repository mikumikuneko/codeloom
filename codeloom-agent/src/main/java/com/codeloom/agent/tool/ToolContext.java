package com.codeloom.agent.tool;

import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 工具执行时需要的一切。
 *
 * <p>刻意把所有工具都要用的东西捆在一个对象里：以后要加新能力（比如"当前会话的
 * token 预算还剩多少"），只需要在这里加字段，不用改每个工具的签名。
 *
 * <h2>只有这一个构造器</h2>
 * 不提供"省掉判据 / 省掉读账本"的重载：审批判据和读账本在真实的调用路径上
 * **永远都有值**，为测试图方便而生的入口只会让人去猜"这个字段是不是可以不填"，
 * 而答案是不行。
 *
 * @param worktree      这条会话的隔离工作区。**所有文件操作都被限制在它里面**
 * @param commandExecutor 跑命令用的执行器（不可信路径，收的是模型写的那一行）
 * @param cancellation  取消信号
 * @param requiresApproval 这条命令需不需要人来批，**以及为什么要问**。收的是**整行命令**，
 *                         因为判据要看第一个词（名单）也要看后面的路径（树内树外），
 *                         还得先判断这行命令看不看得懂。**由调用方注入**：那个判据住在
 *                         workspace 模块，而 agent 模块刻意不依赖它 —— 判据只能从上面传下来，
 *                         不能让工具反向去拿。见 {@link CommandApproval}（为什么是理由而不是布尔）
 * @param reads            这一轮对话里读过哪些文件。见 {@link ReadLedger}。
 *                         **它是有状态的**（工具往里记一笔），而 {@code ToolContext} 每次
 *                         工具调用都新造一个 —— 所以账本身挂在调用方那里，这里只拿一个引用
 */
public record ToolContext(Path worktree,
                          CommandExecutor commandExecutor,
                          CancellationToken cancellation,
                          CommandApproval requiresApproval,
                          ReadLedger reads) {

    public ToolContext {
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(commandExecutor, "commandExecutor");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(requiresApproval, "requiresApproval");
        Objects.requireNonNull(reads, "reads");
        if (!worktree.isAbsolute()) {
            throw new IllegalArgumentException("工作区路径必须是绝对路径: " + worktree);
        }
    }
}
