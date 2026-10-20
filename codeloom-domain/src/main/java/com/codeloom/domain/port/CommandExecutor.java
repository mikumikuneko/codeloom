package com.codeloom.domain.port;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 执行 agent 请求的命令，是参数由 agent 构造的不可信路径；不能与 {@link WorkspaceManager}
 * 的参数可信路径合并成通用执行器。收的是模型写的一整行文本，原样交给 shell 解释、不改写
 * （改写了，人批准的和实际跑的不是同一条）；管道、重定向、{@code &&} 因此可用，代价是
 * 第一个词带路径分隔符的一律先问人。超时强杀进程树、输出截断、取消和「先问人」都还在；
 * 白名单只管要不要先问人，不是准入，也没有「禁止联网」。找不到 shell 时明确报错，不退化
 * （装 Git for Windows 或配置 {@code codeloom.command-shell}）；开发用进程实现（Windows
 * 无 cgroup 限额、隔离与只读挂载），部署用容器（Linux cgroup）。见
 * {@code docs/decisions/architecture/2026-09-24-commands-are-whole-lines-through-bash.md}。
 */
public interface CommandExecutor {

    /**
     * 在指定的目录里执行一行命令，同步等待结束。
     *
     * @param worktree       工作目录，也是相对路径的基准。刻意收 {@code Path} 而不是
     *                       {@link Workspace}：不可信执行路径只需要知道「在哪跑」，而
     *                       {@code Workspace} 是会话聚合的一部分（带 branch / headCommit）。
     *                       收 {@code Workspace} 会逼调用方为了凑签名伪造对象；执行器若要按
     *                       会话做并发限制、审计日志或容器命名，拿到的就是错的身份。
     *                       完整的 {@code Workspace} 留给 {@link WorkspaceManager} 的可信路径。
     * @param commandLine    模型写的那一行命令，原样交给 shell 解释；我们不改写它
     * @param timeout        超时；到点强杀进程树。调用方可以要求更短，但不能更长
     * @param maxOutputChars 输出上限（按字符数，不是字节数），超出部分丢弃并置 truncated
     * @param cancellation   取消信号；观察点必须落在进程等待上
     * @return 命令结果
     * @throws RuntimeException 命令没能跑起来（含「这台机器上没有 shell」）；
     *                          命令自己失败是返回值，不是异常 —— 见 {@link CommandResult}
     */
    CommandResult execute(Path worktree,
                          String commandLine,
                          Duration timeout,
                          int maxOutputChars,
                          CancellationToken cancellation);
}
