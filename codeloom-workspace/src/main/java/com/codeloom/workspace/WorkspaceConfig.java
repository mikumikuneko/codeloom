package com.codeloom.workspace;

import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.workspace.exec.CommandPolicy;
import com.codeloom.workspace.exec.CommandShell;
import com.codeloom.workspace.exec.LocalCommandExecutor;
import com.codeloom.workspace.exec.ProcessRunner;
import com.codeloom.workspace.git.GitClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 工作区能力的装配：git、worktree、命令执行。
 *
 * <p>和 {@code WorkspacePersistenceConfig} 一样由模块自己声明 —— 这三个类都是
 * {@code final} 的普通类（刻意不是 {@code @Component}：它们要的构造参数是**配置**，
 * 让 Spring 去猜路径和可执行文件在哪里，不如在这里显式写出来）。
 *
 * <p>所有参数都有默认值，见 {@code application.yml} 里的 {@code codeloom} 段，
 * 所以**不配任何东西也能跑起来**。
 */
@Configuration(proxyBeanMethods = false)
public class WorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConfig.class);

    /**
     * worktree 的根目录：一棵树一个 {@code <root>/<ownerId>/<projectId>}。
     *
     * <p>布局本身（为什么分两层、为什么不能拼成一个目录名、为什么是主仓库的兄弟目录）
     * 见 {@link LocalWorkspaceManager} 的类注释。
     */
    @Bean
    public WorkspaceManager workspaceManager(
            GitClient git,
            @Value("${codeloom.workspaces-root:}") String workspacesRoot) {
        return new LocalWorkspaceManager(git, workspacesRoot(workspacesRoot));
    }

    /**
     * git 客户端。
     *
     * <p>{@code sandboxDir} 是「中和 git 那些能执行任意命令的配置项」用的目录：
     * 里面放一个空 hooks 目录和一个空全局配置，每次调用 git 都用 {@code -c} 指过去。
     * 见 {@link GitClient} 的类注释。
     */
    @Bean
    public GitClient gitClient(
            @Value("${codeloom.git.executable:git}") String gitExecutable,
            @Value("${codeloom.workspaces-root:}") String workspacesRoot,
            @Value("${codeloom.git.sandbox-dir:}") String sandboxDir) {
        // 加固目录**跟着工作区根走**，而不是自己再推一遍 —— 独立推一遍的话，改了
        // workspaces-root 之后它会仍然按"当前工作目录"另建一份，两份互不知情。
        //
        // 默认放在工作区旁边（而不是另起一个锚点）是因为它们本来就是一件事的两半：
        // 每次调 git 都指过去的就是这个目录。配了就照配的来
        Path sandbox = sandboxDir == null || sandboxDir.isBlank()
                ? workspacesRoot(workspacesRoot).resolve(".git-sandbox")
                : Path.of(sandboxDir).toAbsolutePath().normalize();
        return new GitClient(gitExecutable, sandbox);
    }

    /** 工作区根：配了就用配的，没配就从项目根推导。见 {@link DataPaths}。 */
    private static Path workspacesRoot(String configured) {
        return DataPaths.locate(configured, "workspaces");
    }

    /**
     * agent 能跑的命令的白名单 —— 也就是**免审批线**。
     *
     * <p>默认放行的是构建与测试工具 —— 它们正是"改完代码要验证"所需要的。
     *
     * <p>它只决定「**程序名要不要问人**」，决定不了「这个程序拿参数去干了什么」——
     * 参数那一半是 {@link com.codeloom.workspace.exec.CommandPathScope CommandPathScope} 的事（动到工作区外头要问），
     * 而这行命令整体看不看得懂是 {@code CommandLine} 的事。
     * 详见 {@link CommandPolicy} 的类注释，那里写清了为什么它是"降低误伤面"
     * 而不是"安全边界"。
     *
     * <p>做成独立的 bean 而不是在 {@link #commandExecutor} 里内联构造，
     * 是为了让"探测出来的命令 ⊆ 白名单"这条跨模块不变量**可以被断言**
     * （见 {@code VerificationWhitelistTest}）—— 藏在方法里就没有东西能问它。
     */
    @Bean
    public CommandPolicy commandPolicy(
            // **刻意不给默认值**：默认值写在 application.yml 里，而那是这个项目声称
            // "所有可调项在一个地方看全"的那一处。在这里再写一份，就多了一个
            // 两边会各自演化、而且没人发现的地方 —— 少放行一个可执行文件的表现是
            // "自动验证永远跑不起来"，反过来会把模型引去改代码。
            // 什么时候都不会缺这个属性：application.yml 里定义了它
            @Value("${codeloom.command-whitelist}") String commandWhitelist) {
        return CommandPolicy.allowList(commandWhitelist.split("\\s*,\\s*"));
    }

    @Bean
    public CommandExecutor commandExecutor(
            @Value("${codeloom.command-shell:}") String commandShell) {
        // 注意：**不把 policy 传进去**。执行器不决定"这条命令能不能跑" ——
        // 那是审批那一层（人看着命令和理由）和工作区（文件工具被关在里面）的事。
        // 白名单只有一个身份：**免审批线**。见 LocalCommandExecutor 的类注释
        //
        // shell：留空就自己找（PATH 上的 bash，再兜几个常见位置）。**它是硬前提** ——
        // 命令收的是整行文本（管道、重定向都是这一行的一部分），只有 shell 能解释它。
        // Claude Code 和 deepseek-harness 也都只认 bash、找不到就不跑，见 CommandShell ——
        // **不写死路径** —— Git for Windows 可以装在任意盘符，默认安装位置只是兜底候选
        CommandShell shell = CommandShell.detect(commandShell);
        if (!commandShell.isBlank() && !commandShell.strip().equals(shell.executablePath())) {
            // 配了却被跳过了 —— 因为那个路径上没文件（见 CommandShell.detect）。
            // **必须说出来**：不说的话它就是一次静默回退，而"配置写错了"和
            // "这台机器上就是没有 bash"最后报的是同一句话，分不出来
            log.warn("配的 shell 用不了（那个路径上没有文件？），已跳过，换成自己找到的：{} → {}",
                    commandShell.strip(),
                    shell.present() ? shell.executablePath() : "一个都没找到");
        }
        // 启动时说一句。**不为了好看，为了能一眼分辨两种故障**：
        // "命令起不来"既可能是没找到 shell，也可能是跑的根本是旧代码。
        // 没有这一行的话这两种长得一模一样，只能靠猜
        if (shell.present()) {
            log.info("跑命令会用 {}：模型写的那一行整串交给它解释（管道、重定向、rm/mv/ls…）",
                    shell.executablePath());
        } else {
            // 注意措辞：不是"工具不可用"，是"这条命令跑不了" —— 工具还在表里，
            // 模型调它的时候会收到这句话（见 CommandShell 的类注释）
            log.warn("没找到 bash，run_command 在这台机器上跑不了 —— "
                    + "命令收的是整行文本，没有 shell 就没人能解释它。"
                    + "装个 Git for Windows，或者设 codeloom.command-shell");
        }
        return new LocalCommandExecutor(ProcessRunner.nativeCharset(), shell);
    }
}
