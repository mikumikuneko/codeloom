package com.codeloom.app.turn;

import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.agent.loop.TokenBudget;
import com.codeloom.agent.loop.TurnInput;
import com.codeloom.agent.loop.VerificationPlan;
import com.codeloom.agent.model.ModelCapabilitiesResolver;
import com.codeloom.app.tool.ToolCatalog;
import com.codeloom.domain.llm.Providers;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.app.project.ProjectLayout;
import com.codeloom.workspace.exec.CommandLine;
import com.codeloom.workspace.exec.CommandPathScope;
import com.codeloom.workspace.exec.CommandPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 把一条会话**组装成一轮的输入**：读历史、取客户端、配工具、猜验证命令。
 *
 * <h2>为什么从 TurnExecutor 里拿出来</h2>
 * 这一段和执行器的其余部分**没有共享状态**：它不碰租约、不推状态机、不提交。
 * 而"这一轮拿什么去喂模型"是**最常被单独讨论**的一段（换模型、改系统提示词、
 * 调工具集、动验证策略都会落到这儿），值得一个能被单独读、单独测的地方。
 */
@Component
public class TurnInputFactory {

    private final EventStore events;
    private final LlmClientProvider clients;
    private final CommandExecutor commands;

    /** 会话的活状态（投影）在这儿 —— 见 {@link SessionProjections}。 */
    private final SessionProjections projections;

    /** 判据的来源：命令白名单。**循环拿不到它**（agent 不依赖 workspace），
     *  所以在这里翻译成一个判据函数喂进去，见下面最后那个参数。 */
    private final CommandPolicy policy;

    /** 工具集。进程里唯一那份 —— 见 {@link ToolCatalog}。 */
    private final ToolCatalog catalog;

    public TurnInputFactory(EventStore events,
                            LlmClientProvider clients,
                            CommandExecutor commands,
                            SessionProjections projections,
                            CommandPolicy policy,
                            ToolCatalog catalog) {
        this.events = events;
        this.clients = clients;
        this.commands = commands;
        this.projections = projections;
        this.policy = policy;
        this.catalog = catalog;
    }

    /**
     * 组装这一轮的输入。
     *
     * <p>历史**不在这里读**：会话的投影是活的（见 {@link SessionProjections}），
     * 它每次交出来之前会自己补读到库里最新的一条 —— 所以"上一轮刚写的事件必须立刻可见"
     * 照样成立，而且不用把整条流读回来。
     */
    TurnInput create(Session session, Workspace workspace, CancellationToken cancellation) {
        ModelConfig model = session.model();
        return new TurnInput(
                session.id(),
                projections.acquire(session),
                // **给 agent 的是项目根，不是工作区根。**
                // 它所有的相对路径、以及 run_command 的工作目录，都以项目根为准 ——
                // 于是"这个项目在哪儿"对它是一句确定的话，不用猜
                ProjectLayout.rootOf(workspace),
                model.systemPrompt(),
                // 建会话时已经拦过一次，走到这里还取不到客户端只有一种情况：
                // **这一轮跑起来之后密钥被删了**。所以不必再给一段"怎么去配"的引导，
                // 说清"发生了什么"就够 —— 它会被记成一次失败的轮次，不是 500
                clients.findClient(session.ownerId(), model).orElseThrow(() -> new IllegalStateException(
                        Providers.displayNameOf(model.provider())
                                + " 的 API Key 已经取不到了，这一轮无法开始。"
                                + "它多半是在会话建好之后被删掉或换掉了 —— 重新配置后重新发一条消息即可。")),
                model,
                ModelCapabilitiesResolver.resolve(model.modelId()),
                catalog.registry(),
                commands,
                TokenBudget.DEFAULT,
                // 验证命令按工作区里的构建文件猜（认不出来就不验证）。
                // 猜错的代价比"不验证"大得多：模型会去修一个根本不存在的问题
                VerificationPlan.detect(ProjectLayout.rootOf(workspace)).orElse(VerificationPlan.NONE),
                cancellation,
                // 什么时候才问人。**三个条件，中了任何一个就问**：
                //
                //   ① 这行命令的第一个词不在免审批名单里（带路径的也算不在，见 CommandPolicy）；
                //   ② 或者它**动到了工作区外面**的东西；
                //   ③ 或者这行命令我们**看不懂** —— 里面有变量、命令替换、管道、
                //      重定向、波浪号这些"解释起来会变样"的东西（见 CommandLine）。
                //
                // ① 和 ② 是免审批名单的两半：名单只看程序名，于是
                // `rm -rf node_modules` 和 `rm -rf C:\…\Documents` 在它眼里一样，
                // 而"用户在项目树内是自由的"这句话落到 rm/mv 上说的正是**路径**。
                // 见 CommandPathScope。
                //
                // 整行交给 shell 之后，变量展开、命令替换、glob 全都活了过来 —— 那些
                // 我们看不见的东西一律先问人（③），而不是假装看懂了。
                //
                // 注意这里决定的只是"要不要问"，**不是"能不能跑"** ——
                // 答案永远是"能"，只是可能要先点一下批准
                commandLine -> whyAsk(workspace, commandLine));
    }

    /**
     * 为什么这条命令要先问人 —— 空 = 不用问。
     *
     * <h2>为什么回来的是理由，不是一个布尔</h2>
     * 因为这句话要**摆在人面前**：他要看着它点"批准"。而三种情况要人过目的东西
     * 完全不同 —— 一个是不认识那个程序、一个动的是工作区外面的路径、一个是这行命令
     * 压根看不懂。只说"需要确认"的话，那个确认就是走过场。
     */
    private Optional<String> whyAsk(Workspace workspace, String commandLine) {
        Optional<List<String>> words = CommandLine.simpleWords(commandLine);
        if (words.isEmpty()) {
            return Optional.of("这行命令里有变量、命令替换、管道、重定向，或者引号没配对 ——"
                    + "它真正会做什么我们看不准，先请你过一眼。");
        }
        List<String> parts = words.get();
        String program = parts.getFirst();
        if (!policy.isAllowed(program)) {
            return Optional.of("`" + program + "` 不在免审批的程序名单里，先请你过一眼。");
        }
        if (!CommandPathScope.staysInside(ProjectLayout.rootOf(workspace), parts)) {
            return Optional.of("这条命令会动到工作区外面的路径，先请你过一眼。");
        }
        return Optional.empty();
    }
}
