package com.codeloom.agent.loop;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.model.ModelCapabilities;
import com.codeloom.agent.tool.CommandApproval;
import com.codeloom.agent.tool.ToolRegistry;
import com.codeloom.agent.web.WebAccess;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.SessionId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 跑一轮所需的全部输入。这里没有事件存储、仓库或租约：循环只拿投影、产出事件，
 * 落库和状态更新由调用方负责，因此可以用假客户端和临时目录完整测试。
 *
 * <p>投影跨轮存活，循环从中取得已投出的上下文和派生事实，每轮只补读新增部分。
 *
 * @param sessionId       会话 id
 * @param projection      这条会话活的投影：上下文与派生事实
 * @param worktree        这条会话的隔离工作区，必须是绝对路径
 * @param systemPrompt    会话级系统提示词
 * @param client          模型客户端
 * @param model           模型配置，不含密钥
 * @param capabilities    模型能力；不支持工具调用的模型配工具时会在开始前失败
 * @param tools           可用工具
 * @param commandExecutor 命令执行器
 * @param budget          单轮 token 预算
 * @param verification    改动后强制执行的验证；{@link VerificationPlan#NONE} 表示不验证
 * @param cancellation    取消信号
 * @param requiresApproval 命令审批判据，返回需要审批的理由；null 表示一切都不需要批
 * @param web             联网能力；没有能力的会话给 {@link WebAccess#none()}
 */
public record TurnInput(SessionId sessionId,
                        ContextAssembler.Projection projection,
                        Path worktree,
                        String systemPrompt,
                        LlmClient client,
                        ModelConfig model,
                        ModelCapabilities capabilities,
                        ToolRegistry tools,
                        CommandExecutor commandExecutor,
                        TokenBudget budget,
                        VerificationPlan verification,
                        CancellationToken cancellation,
                        CommandApproval requiresApproval,
                        WebAccess web) {

    public TurnInput {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(model, "model");
        capabilities = capabilities == null ? ModelCapabilities.UNKNOWN : capabilities;
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(commandExecutor, "commandExecutor");
        budget = budget == null ? TokenBudget.DEFAULT : budget;
        verification = verification == null ? VerificationPlan.NONE : verification;
        cancellation = cancellation == null ? CancellationToken.none() : cancellation;
        // 没给判据表示没有审批概念（测试、纯读流程），不是一律拦住。
        requiresApproval = requiresApproval == null ? commandLine -> Optional.empty() : requiresApproval;
        if (!worktree.isAbsolute()) {
            throw new IllegalArgumentException("工作区必须是绝对路径: " + worktree);
        }
    }

    /**
     * 没有审批概念、也没有联网能力的场合：一切都不需要批，联网为 {@link WebAccess#none()}。
     *
     * @param sessionId       会话 id
     * @param projection      活的投影
     * @param worktree        隔离工作区
     * @param systemPrompt    会话级系统提示词
     * @param client          模型客户端
     * @param model           模型配置
     * @param capabilities    模型能力
     * @param tools           可用工具
     * @param commandExecutor 命令执行器
     * @param budget          单轮预算
     * @param verification    验证计划
     * @param cancellation    取消信号
     */
    public TurnInput(SessionId sessionId,
                     ContextAssembler.Projection projection,
                     Path worktree,
                     String systemPrompt,
                     LlmClient client,
                     ModelConfig model,
                     ModelCapabilities capabilities,
                     ToolRegistry tools,
                     CommandExecutor commandExecutor,
                     TokenBudget budget,
                     VerificationPlan verification,
                     CancellationToken cancellation) {
        this(sessionId, projection, worktree, systemPrompt, client, model, capabilities, tools,
                commandExecutor, budget, verification, cancellation, null, WebAccess.none());
    }

    /**
     * 返回同一个输入的副本，但指定命令行不再需要审批，用于执行已批准的命令。
     *
     * <p>只按整行文本精确放行，不放开其他命令；否则已批准但不在白名单里的命令会被再次拦下，
     * 形成反复挂起。
     *
     * @param command 已批准的命令行；null 表示没有命令参数的调用
     * @return 放行该命令的输入
     */
    public TurnInput withApprovalGrantedFor(String command) {
        return new TurnInput(sessionId, projection, worktree, systemPrompt, client, model, capabilities,
                tools, commandExecutor, budget, verification, cancellation,
                // null 表示这次调用没有命令参数，不是需要审批的命令。
                candidate -> candidate == null || command.equals(candidate)
                        ? Optional.empty()
                        : requiresApproval.reasonToAsk(candidate),
                web);
    }
}
