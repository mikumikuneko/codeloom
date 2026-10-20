package com.codeloom.app.turn;

import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.agent.loop.TokenBudget;
import com.codeloom.agent.loop.TurnInput;
import com.codeloom.agent.loop.VerificationPlan;
import com.codeloom.agent.model.ModelCapabilitiesResolver;
import com.codeloom.agent.web.WebAccess;
import com.codeloom.app.llm.ApiKeyError;
import com.codeloom.app.tool.ToolCatalog;
import com.codeloom.app.web.ApiKeyWebAccessProvider;
import com.codeloom.domain.llm.Providers;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
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
 * 把一条会话组装成一轮的输入：历史、客户端、工具、验证命令。
 *
 * <p>它不碰租约、不推状态机、不提交，与执行器其余部分没有共享状态；而"这一轮喂什么给模型"
 * 是最常被单独讨论的一段（换模型、改系统提示词、调工具集、动验证策略都会落在这里），
 * 因此独立出来便于阅读和测试。
 */
@Component
public class TurnInputFactory {

    private final LlmClientProvider clients;
    private final CommandExecutor commands;

    /** 会话的活状态，见 {@link SessionProjections}。 */
    private final SessionProjections projections;

    /**
     * 命令白名单。循环拿不到它（agent 不依赖 workspace），因此在这里翻译成判据函数传入。
     */
    private final CommandPolicy policy;

    /** 进程内唯一的工具集，见 {@link ToolCatalog}。 */
    private final ToolCatalog catalog;

    /** 会话能否联网；判据在它自己那里（供应商加密钥），这里只取结果。 */
    private final ApiKeyWebAccessProvider webAccess;

    public TurnInputFactory(LlmClientProvider clients,
                            CommandExecutor commands,
                            SessionProjections projections,
                            CommandPolicy policy,
                            ToolCatalog catalog,
                            ApiKeyWebAccessProvider webAccess) {
        this.clients = clients;
        this.commands = commands;
        this.projections = projections;
        this.policy = policy;
        this.catalog = catalog;
        this.webAccess = webAccess;
    }

    /**
     * 组装这一轮的输入。
     *
     * <p>历史不在这里读：投影是活的（见 {@link SessionProjections}），每次交出前会补读到库里
     * 最新，所以"上一轮刚写的事件立刻可见"成立，又不必重读整条流。
     */
    TurnInput create(Session session, Workspace workspace, CancellationToken cancellation) {
        ModelConfig model = session.model();
        // 联网能力按会话判（供应商 + 密钥，见 ApiKeyWebAccessProvider）。有才放入工具列表：
        // 摆出来再让每次调用失败，只会白烧一次调用
        Optional<WebAccess> web = webAccess.find(session.ownerId(), model);
        return new TurnInput(
                session.id(),
                projections.acquire(session),
                // 给 agent 的是项目根而不是工作区根：相对路径和 run_command 工作目录都以它为准，
                // "项目在哪儿"因此是确定的话，不用猜
                ProjectLayout.rootOf(workspace),
                model.systemPrompt(),
                // 建会话时已拦过一次，这里取不到客户端只可能是本轮开始后密钥被删了。
                // 不必再给配置引导，说清事实即可；它会被记成一次失败轮次，不是 500
                clients.findClient(session.ownerId(), model).orElseThrow(() ->
                        new IllegalStateException(ApiKeyError.goneMidTurn(
                                Providers.displayNameOf(model.provider())))),
                model,
                ModelCapabilitiesResolver.resolve(model.modelId()),
                web.isPresent() ? catalog.withWeb() : catalog.registry(),
                commands,
                TokenBudget.DEFAULT,
                // 验证命令按工作区的构建文件猜，认不出就不验证；
                // 猜错的代价比不验证大，模型会去修一个不存在的问题
                VerificationPlan.detect(ProjectLayout.rootOf(workspace)).orElse(VerificationPlan.NONE),
                cancellation,
                // 何时先问人，三个条件任一命中：第一个词不在免审批名单（含带路径的）；
                // 命令会动到工作区外的路径；或整行包含变量、命令替换、管道、重定向、
                // 波浪号等解释起来会变样、我们看不懂的语法（见 CommandLine）。
                // 名单只看程序名，所以 rm -rf node_modules 与 rm -rf 项目外路径同等对待，
                // 路径边界由 CommandPathScope 判断。
                // 这里只决定"要不要问"，不决定"能不能跑"：答案永远是能，只是可能要先批准
                commandLine -> whyAsk(workspace, commandLine),
                web.orElseGet(WebAccess::none));
    }

    /**
     * 这条命令为什么需要先问人；空表示不用问。
     *
     * <p>返回理由而不是布尔，因为理由要摆在人面前供其判断：不认识程序、动到工作区外的路径、
     * 命令本身看不懂，这三种需要人看的东西完全不同，只说"需要确认"会让确认变成走过场。
     */
    private Optional<String> whyAsk(Workspace workspace, String commandLine) {
        // &&、||、; 串起来的是依次执行的几条命令，每条各查一遍即可。若把整行按单个 &
        // 或 | 判成"看不懂"，模型每写一次 javac X.java && java X 都要人点批准
        Optional<List<List<String>>> commands = CommandLine.simpleCommands(commandLine);
        if (commands.isEmpty()) {
            return Optional.of("这行命令里有变量、命令替换、管道、重定向，或者引号没配对 ——"
                    + "它真正会做什么我们看不准，先请你过一眼。");
        }
        for (List<String> parts : commands.get()) {
            String program = parts.getFirst();
            if (!policy.isAllowed(program)) {
                return Optional.of("`" + program + "` 不在免审批的程序名单里，先请你过一眼。");
            }
            if (!CommandPathScope.staysInside(ProjectLayout.rootOf(workspace), parts)) {
                return Optional.of("这条命令会动到工作区外面的路径，先请你过一眼。");
            }
        }
        return Optional.empty();
    }
}
