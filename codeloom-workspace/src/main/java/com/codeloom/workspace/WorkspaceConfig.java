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
 * 工作区能力的装配：git 客户端、工作区管理器、命令执行器。
 *
 * <p>被装配的类都是 {@code final} 普通类而不是 {@code @Component}：它们的构造参数是
 * 配置，由这里显式决定。所有配置项都有默认值，见 {@code application.yml} 的
 * {@code codeloom} 段，不配置也能启动。
 */
@Configuration(proxyBeanMethods = false)
public class WorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConfig.class);

    /**
     * 工作区管理器；目录布局见 {@link LocalWorkspaceManager}。
     *
     * @param git git 客户端
     * @param workspacesRoot 工作区根目录；空表示从项目根自动推导
     * @return 本地工作区管理器
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
     * <p>{@code sandboxDir} 放空 hooks 目录和空全局配置：hooks 目录每次调用用
     * {@code -c core.hooksPath} 指过去，空全局配置通过 {@code GIT_CONFIG_GLOBAL} 和
     * {@code GIT_CONFIG_NOSYSTEM} 生效。默认跟随工作区根，避免两处推导结果不一致。
     *
     * @param gitExecutable git 可执行文件名或路径
     * @param workspacesRoot 工作区根目录；空表示从项目根自动推导
     * @param sandboxDir 沙箱目录；空表示 {@code workspacesRoot/.git-sandbox}
     * @return git 客户端
     */
    @Bean
    public GitClient gitClient(
            @Value("${codeloom.git.executable:git}") String gitExecutable,
            @Value("${codeloom.workspaces-root:}") String workspacesRoot,
            @Value("${codeloom.git.sandbox-dir:}") String sandboxDir) {
        // 沙箱目录默认跟随工作区根，不另行推导；显式配置优先
        Path sandbox = sandboxDir == null || sandboxDir.isBlank()
                ? workspacesRoot(workspacesRoot).resolve(".git-sandbox")
                : Path.of(sandboxDir).toAbsolutePath().normalize();
        return new GitClient(gitExecutable, sandbox);
    }

    /** 工作区根：显式配置优先，否则从项目根推导，见 {@link DataPaths}。 */
    private static Path workspacesRoot(String configured) {
        return DataPaths.locate(configured, "workspaces");
    }

    /**
     * 免审批命令白名单。
     *
     * <p>它只决定"程序名要不要问人"，不决定"能不能跑"；参数是否越出工作区由
     * {@link com.codeloom.workspace.exec.CommandPathScope} 判断，整行是否看得懂由
     * {@code CommandLine} 判断。做成独立 bean 是为了让"探测出的命令 ⊆ 白名单"
     * 这条跨模块不变量可以被断言。理由见
     * {@code docs/decisions/architecture/2026-09-30-approval-is-scoped-to-the-project.md}。
     *
     * @param commandWhitelist 逗号分隔的可执行文件名；故意没有默认值，权威在
     *                         {@code application.yml}
     * @return 不可变白名单
     */
    @Bean
    public CommandPolicy commandPolicy(
            // 不在这里写默认值：默认值只在 application.yml 里，避免两处各自演化
            @Value("${codeloom.command-whitelist}") String commandWhitelist) {
        return CommandPolicy.allowList(commandWhitelist.split("\\s*,\\s*"));
    }

    /**
     * 命令执行器。
     *
     * <p>不接收 {@link CommandPolicy}：执行器不判断一条命令能不能跑。shell 留空时自动
     * 探测；探测结果写入启动日志，以便区分"没找到 shell"和"运行的是旧代码"。
     *
     * @param commandShell 显式指定的 shell 路径或名字；空表示自动探测
     * @return 本地进程执行器
     */
    @Bean
    public CommandExecutor commandExecutor(
            @Value("${codeloom.command-shell:}") String commandShell) {
        // 执行器不决定"这条命令能不能跑"，白名单只用于免审批；见 LocalCommandExecutor
        CommandShell shell = CommandShell.detect(commandShell);
        if (!commandShell.isBlank() && !commandShell.strip().equals(shell.executablePath())) {
            // 配置了却被跳过，必须说出来：否则就是一次静默回退
            log.warn("配的 shell 用不了（那个路径上没有文件？），已跳过，换成自己找到的：{} → {}",
                    commandShell.strip(),
                    shell.present() ? shell.executablePath() : "一个都没找到");
        }
        // 启动时说明使用的 shell，便于分辨"没找到 shell"与"运行的是旧代码"
        if (shell.present()) {
            log.info("跑命令会用 {}：模型写的那一行整串交给它解释（管道、重定向、rm/mv/ls…）",
                    shell.executablePath());
        } else {
            // 措辞是"这条命令跑不了"，不是"工具不可用" —— 工具仍在工具表里
            log.warn("没找到 bash，run_command 在这台机器上跑不了 —— "
                    + "命令收的是整行文本，没有 shell 就没人能解释它。"
                    + "装个 Git for Windows，或者设 codeloom.command-shell");
        }
        return new LocalCommandExecutor(ProcessRunner.nativeCharset(), shell);
    }
}
