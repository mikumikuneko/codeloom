package com.codeloom.domain.port;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 执行 agent 请求的命令 —— 这是**参数由 agent 构造**的那条不可信路径。
 *
 * <h2>与 {@link WorkspaceManager} 的区别</h2>
 * {@code WorkspaceManager} 的参数由我们构造（可信）；本接口的参数来自模型输出
 * （不可信）。两者拿到的命令形态也因此不同，绝不能合并成一个"通用执行器"。
 *
 * <h2>收的是「一行命令」，不是 argv 数组</h2>
 * 模型最自然的写法本来就是一行文本（{@code mvn -q test}、
 * {@code grep -rn foo src | head}），所以这里直接收那一行，交给一层 shell 去解释。
 * 两份参考实现都是这个形态：Claude Code 的 Bash 工具收一行命令；
 * deepseek-harness 也一样，连它自己插件的命令都走同一个形态。
 *
 * <p>收的是**一整行文本**，不是 argv 数组。收 argv 的好处是"不走 shell，于是引号、转义、
 * 重定向那一整套问题都不存在"—— 那个好处是真的，但它换来的是三个模型做不到的事：
 * <ul>
 *   <li>管道、重定向、{@code &&} 全写不了 —— 而这些正是"等同用户在操作 Claude Code"
 *       里最日常的那部分；</li>
 *   <li>Windows 上 {@code rm}、{@code mv}、{@code ls}、{@code cat} 全都不是可执行文件，
 *       第一个词必须恰好是 PATH 上真实存在的程序；</li>
 *   <li>参数要我们自己拼回一行，于是每一处"拼"都可能拼错。</li>
 * </ul>
 *
 * <h2>收整串之后，哪些防线还在、哪些没了</h2>
 * <b>还在</b>：超时（到点强杀整棵进程树）、输出截断（字符数上限）、
 * 取消信号（观察点落在等待上）、以及"要不要先问人"那一道判据
 * （见 {@code CommandPathScope} 与 {@code CommandLine}）。
 *
 * <p><b>没了的</b>：从前 argv[0] 会被剥掉目录再执行，所以模型给一个
 * {@code /tmp/evil/mvn} 也只会跑到系统 PATH 上的 {@code mvn}。整串形态下我们不再改写
 * 模型写的那一行（改写了，人批准的和实际跑的就不是同一条了）。取而代之的是**判据变严**：
 * 第一个词带路径分隔符的一律先问人 —— 人看着那条路径点批准。
 *
 * <h2>它**不是**"准入"</h2>
 * 命令不会因为"不在某个清单里"而被拒绝 —— 白名单只决定**要不要先问人**，
 * 人点了批准就照跑。网络访问同理：没有"禁止联网"这回事，{@code curl} 只是要问一次。
 * 真正会失败的是：命令自己报错、超时、取消、以及这台机器上没有可用的 shell
 * （见下）。
 *
 * <h2>没有 shell 就跑不了 —— 这台机器上必须有</h2>
 * 一行文本没有 argv[0]，没有 shell 就没有任何东西能解释它。所以找不到 shell 时
 * 这里会明确报错，而不是悄悄退化成"直接拉起可执行文件"（那个退化路径整串形态下
 * 根本不存在）。装一个 Git for Windows，或者用 {@code codeloom.command-shell} 指路。
 *
 * <h2>实现的可替换性</h2>
 * 开发期用的是"进程实现"——宿主机是 Windows，**做不到 cgroup 内存限额、用户隔离、
 * 只读挂载**，只有超时 + 截断 + 进程树强杀。部署期换成"容器实现"（Linux，cgroup）。
 */
public interface CommandExecutor {

    /**
     * 在指定的目录里执行一行命令，同步等待结束。
     *
     * @param worktree 工作目录，也是**相对路径的基准**。**刻意收 {@code Path} 而不是
     *                 {@link Workspace}** —— 不可信执行路径只需要知道"在哪跑"，而
     *                 {@code Workspace} 是会话聚合的一部分（带 branch / headCommit）。
     *
     *                 <p>收 {@code Workspace} 会逼每个调用方为了凑签名去伪造一个半真半假的对象 ——
     *                 而执行器一旦要实现按会话的并发限制、审计日志或容器命名，拿到的就是错的身份。
     *                 完整的 {@code Workspace} 留给 {@link WorkspaceManager} 那条**可信**路径。
     * @param commandLine 模型写的那一行命令，原样交给 shell 解释（{@code bash -c}）。
     *                    我们**不改写**它 —— 见类注释"没了的"那一段。
     * @param timeout  超时；到点强杀进程树。调用方可以要求更短，但不能更长
     * @param maxOutputChars 输出上限（按**字符**数，不是字节数），超出部分丢弃并置 truncated
     * @param cancellation 取消信号；观察点必须落在进程等待上
     * @throws RuntimeException 命令没能跑起来（含"这台机器上没有 shell"）；
     *                         **命令自己失败是返回值，不是异常** —— 见 {@link CommandResult}
     */
    CommandResult execute(Path worktree,
                          String commandLine,
                          Duration timeout,
                          int maxOutputChars,
                          CancellationToken cancellation);
}
