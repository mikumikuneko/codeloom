package com.codeloom.agent.loop;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.model.ModelCapabilities;
import com.codeloom.agent.tool.CommandApproval;
import com.codeloom.agent.tool.ToolRegistry;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.SessionId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 跑一轮所需的全部输入。
 *
 * <p>注意这里**没有 EventStore、没有 SessionRepository、没有租约** —— 循环自己不碰存储。
 * 它拿到**投影**、产出新事件，由调用方负责落库、更新会话状态、广播。
 *
 * <p>这么切的好处很实在：**循环变成了一个纯函数**（投影 + 配置 → 新事件），
 * 单测时塞一个假的 {@link LlmClient} 和真的临时目录就能跑完整条路径，
 * 不需要数据库、不需要 Redis、不需要 Spring。最容易出 bug 的那部分代码，
 * 恰好是最好测的那部分。
 *
 * <h2>为什么带的是投影，不是事件流</h2>
 * 循环从历史里要的东西其实只有三样：**已经投出来的上下文**、这条对话读过哪些文件、
 * 有没有一次"批准了但还没跑"的调用。后两样是**派生事实**，它们跟着投影一路走
 *（见 {@link ContextAssembler.Projection}）。
 *
 * <p>投影是**活的**（跨轮活着，见 {@code SessionProjections}），每轮只补读它还没见过的
 * 那一小截；否则最要命的那条路上每轮都要回读整条事件流。
 *
 * @param projection     这条会话**活的**投影：上下文 + 那几个派生事实
 * @param worktree       这条会话的隔离工作区
 * @param systemPrompt   会话级系统提示词
 * @param client         模型客户端
 * @param model          模型配置（不含密钥）
 * @param capabilities   该模型的能力描述。循环据此做门控 —— 比如不支持工具调用的模型
 *                       配上工具会在一开始就失败，而不是跑到运行时才炸
 * @param tools          可用工具
 * @param commandExecutor 跑命令用的执行器
 * @param budget         token 预算（单轮，见 {@link TokenBudget}）
 * @param verification   动过文件之后强制跑的验证命令；{@link VerificationPlan#NONE} 表示不验证
 * @param cancellation   取消信号
 * @param requiresApproval 这行命令需不需要人来批，**以及为什么要问**（收整行，见
 *                         {@link com.codeloom.agent.tool.CommandApproval}）。
 *                         为空表示**一切都不需要批** —— 判据（命令白名单 + 路径范围）
 *                         在 workspace 模块，循环只能被喂进来
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
                        CommandApproval requiresApproval) {

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
        // 没给判据就是"什么都不用批" —— 那是"没有审批这个概念"的场合
        // （测试、纯读流程），而不是"一律拦住"
        requiresApproval = requiresApproval == null ? commandLine -> Optional.empty() : requiresApproval;
        if (!worktree.isAbsolute()) {
            throw new IllegalArgumentException("工作区必须是绝对路径: " + worktree);
        }
    }

    /** 没有审批概念的场合用这个：一切都不需要批。 */
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
                commandExecutor, budget, verification, cancellation, null);
    }

    /**
     * 同一个输入，但**这一行命令不再需要审批** —— 给"已经批准过、现在只是去把它跑掉"用的。
     *
     * <h2>为什么必须放行，而且是按命令放行</h2>
     * 审批的判据是"这行命令在不在免审批清单里、它的路径在不在项目里、它看不看得懂"
     * （见 app 侧那个 {@code CommandLine} + {@code CommandPathScope} 的组合）。
     * 一行不在清单里的命令，**批准之后它仍然不在清单里** —— 拿着原样的判据去执行，
     * 工具会第二次报"需要批准"，于是又挂起、又等人批，永远跑不掉。
     *
     * <p>放行范围**只限那一行命令**，不是"从此都不用批"：万一执行路径上冒出别的命令，
     * 它照样要被拦。多写一个 equals 换掉一整类"以后某次改动放开了全部审批"的可能。
     *
     * <p>按**那一行文本**比，而不是按词表比：现在模型写的就是一行，
     * 而这一行的每个字符都算数 —— 多一个字就是另一条命令。
     */
    public TurnInput withApprovalGrantedFor(String command) {
        return new TurnInput(sessionId, projection, worktree, systemPrompt, client, model, capabilities,
                tools, commandExecutor, budget, verification, cancellation,
                // 没有命令参数的调用（别的工具）不走这条判据：null 表示"这次没有命令"，
                // 而不是"一条需要审批的命令"
                candidate -> candidate == null || command.equals(candidate)
                        ? Optional.empty()
                        : requiresApproval.reasonToAsk(candidate));
    }
}
