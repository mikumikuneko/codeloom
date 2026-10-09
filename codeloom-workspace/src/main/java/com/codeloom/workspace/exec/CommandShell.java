package com.codeloom.workspace.exec;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 跑模型给的那行命令时用的 shell —— 也就是 {@code [<shell>, <脚本文件>]} 里前面那一截。
 *
 * <h2>为什么命令一定要过一层 shell</h2>
 * 模型写的是一行文本（管道、重定向、{@code &&} 都是这行文本的一部分），
 * 而一行文本**只有 shell 能解释**。两个参考实现都过一层 shell：Claude Code 用
 * {@code bash -c "<整串>"}，deepseek-harness 的 bash 执行器把
 * {@code ['bash', '-c', command]} 写死在里面。
 *
 * <h2>两边都只认 bash —— 这一条我们跟它们一样</h2>
 * 找不到 bash 时它们都不降级去用别的 shell：
 * <ul>
 *   <li>Claude Code 在 Windows 上只走 Git Bash，找不到就报一句
 *       "Claude Code on Windows requires git-bash ..."（原文）然后直接退出整个进程。
 *       它的 PowerShell 是**另一个工具**，由 {@code CLAUDE_CODE_USE_POWERSHELL_TOOL}
 *       单独开，默认只用于输入框里带 {@code !} 的命令。</li>
 *   <li>deepseek-harness 的 bash 执行器里 {@code 'bash'} 是硬编码的，README 的原话是
 *       "POSIX-only — the bash binary is hardcoded … Windows is unsupported"；
 *       Windows 上它换的是**另一个执行器**（PowerShell 版），不是让 bash 去兼容。</li>
 * </ul>
 *
 * <p>结论：只认 bash 不是我们偷懒，是两边共同的选择。代价是这台机器上必须有 bash。
 *
 * <h2>只要 bash，不要 sh</h2>
 * {@code sh} 不进来：dash 那一族是 bash 的子集，{@code [[ ]]}、数组、
 * {@code set -o pipefail} 在它下面会**静默**变形，而模型写的是 bash 风格。
 * 两边都排除它 —— Claude Code 的校验是"路径里含 {@code bash} 或 {@code zsh}"，
 * deepseek-harness 干脆硬编码 {@code bash}。（它收 zsh 是因为 macOS 默认的
 * {@code SHELL} 就是 zsh；我们这边没有这个情况，所以只收 bash。）
 *
 * <h2>命令里的元字符由 {@link CommandLine} 判，不由执行器判</h2>
 * 那些元字符是"模型的正常写法"；判断"这行命令简不简单到能免审批"的活儿在
 * {@link CommandLine}，不是执行器的事。
 *
 * <h2>找不到就明确失败</h2>
 * {@link #argvFor(Path)} 在没 shell 时会直接抛 —— 没有"退回直接拉起可执行文件"那条路，
 * 那条路对一行文本**不成立**。
 *
 * <p>但这不等于"工具不见了"：{@code RunCommandTool} 照常在工具表里，模型调它的时候
 * 会拿到那句抛出的话，那一轮照常结束（{@code RunCommandTool.execute} 把
 * {@code RuntimeException} 收成一次失败的工具结果）。
 *
 * <p>路径**不写死**：Git for Windows 允许装在任意盘符，默认安装位置只是兜底候选之一
 * —— 只认默认位置的写法，在非默认安装的机器上就是失灵的。
 */
public final class CommandShell {

    /** 覆盖它的环境变量。不写进 application.yml 也行 —— 它是一台机器一个值的东西。 */
    public static final String ENV = "CODELOOM_COMMAND_SHELL";

    /** 没有 shell 时的那个值。它表示**命令跑不了**，不是"换个方式跑"。 */
    private final String executable;

    private CommandShell(String executable) {
        this.executable = executable;
    }

    /** 没有可用的 shell。 */
    public static CommandShell none() {
        return new CommandShell(null);
    }

    /**
     * 用一个已知的路径（或裸名字）造一个 shell。
     *
     * @throws IllegalArgumentException 空值。"没有 shell"那件事由 {@link #none()} 表示；
     *                                  两者混起来会造出一个"存在、但一跑就报错"的东西
     */
    public static CommandShell of(String executable) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("shell 不能为空 —— 没有 shell 请用 none()");
        }
        return new CommandShell(executable.strip());
    }

    /**
     * 按顺序找：配置 → 环境变量 → PATH 上的 {@code bash} → 顺着 git.exe 找 → 几个常见位置。
     *
     * <p>PATH 那一步是**主要**的一条 —— 它在 Unix 上等同于 {@code /bin/bash}，
     * 在 Windows 上认的是 Git for Windows 装完加到 PATH 里的那个 {@code bash.exe}。
     * 后面几个写死的位置只是兜底，**且不假设盘符**。
     *
     * <p>配置和环境变量给的是一个**路径**时（含分隔符），要求那个文件真的在；
     * 不在就跳过、继续往下找，而不是采信它 —— 采信一个打错的路径，症状会延后到
     * "某条命令起不来"，那时报的是"无法启动进程"，看不出是配置里少了一个字母。
     * 裸名字（{@code bash}）不算路径，交给 PATH 解析。
     *
     * <p>参考实现在这一点上各执一词：Claude Code 会校验
     * {@code CLAUDE_CODE_GIT_BASH_PATH} 指向的文件在不在，不在就报错退出；
     * deepseek-harness 是配了就用、不看。我们取前者的一半：**校验，但回退而不是退出**
     * —— 这台机器上还有别的功能，为一个路径打错就整个起不来不划算。
     * （它还会跑一次 {@code bash --version} 探活；我们只查文件在不在，
     * 因为要挡住的是打错字，而多起一个进程换不来更多信息。）
     */
    public static CommandShell detect(String configured) {
        for (String candidate : List.of(
                configured == null ? "" : configured,
                System.getenv().getOrDefault(ENV, ""))) {
            if (candidate.isBlank()) {
                continue;
            }
            String shell = candidate.strip();
            if (looksLikePath(shell) && !exists(shell)) {
                continue;
            }
            return new CommandShell(shell);
        }
        for (Optional<CommandShell> found : List.of(onPath(), besideGit(), knownLocations())) {
            if (found.isPresent()) {
                return found.get();
            }
        }
        return none();
    }

    /** 配的是路径还是裸名字。**只看有没有分隔符** —— 有分隔符才要求文件真的在。 */
    private static boolean looksLikePath(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    }

    /** 这个路径上真的有一个文件吗。路径本身非法一律算没有。 */
    private static boolean exists(String path) {
        try {
            return Files.isRegularFile(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * 顺着 PATH 上的 {@code git.exe} 往上找一层。
     *
     * <h2>为什么需要这一条</h2>
     * <b>Git for Windows 默认只把它的 cmd 目录加进 PATH</b> —— 那个目录里有
     * {@code git.exe}，却**没有 {@code bash.exe}**（bash 在同一个安装目录的 bin
     * 和 usr/bin 里）。所以"PATH 上找 bash"这条在**默认安装**的机器上永远找不到，
     * 而它恰恰是最常见的那种安装。
     *
     * <p>{@link #knownLocations} 兜不住它：那份名单只能列默认安装位置，
     * 而 Git 可以装在别的盘符。**顺着 git.exe 找就不用猜盘符**。
     */
    private static Optional<CommandShell> besideGit() {
        for (String directory : pathEntries()) {
            Path git = Path.of(directory, isWindows() ? "git.exe" : "git");
            if (!Files.isRegularFile(git)) {
                continue;
            }
            Path home = Path.of(directory).getParent();          // <Git>\cmd → <Git>
            if (home == null) {
                continue;
            }
            for (String relative : List.of("bin/bash.exe", "usr/bin/bash.exe")) {
                Path candidate = home.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(of(candidate.toString()));
                }
            }
        }
        return Optional.empty();
    }

    /** PATH 上的那些目录。两处都要用，切法只有这一份。 */
    private static List<String> pathEntries() {
        String path = System.getenv("PATH");
        return path == null || path.isBlank() ? List.of() : List.of(path.split(java.io.File.pathSeparator));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 这台机器上有没有 shell。没有的话命令跑不了，见类注释。 */
    public boolean present() {
        return executable != null;
    }

    /**
     * 探测到的那个文件。**给日志用** —— 启动时把它打出来，才能一眼分辨
     * "没找到 shell"和"跑的根本是旧代码"这两种长得一模一样的故障（见 {@code WorkspaceConfig}）。
     */
    public String executablePath() {
        return executable;
    }

    /**
     * 把落在盘上的那份命令拼成 argv：{@code [shell, <脚本文件>]}。
     *
     * <h2>为什么不走 {@code bash -c "<那一行>"}</h2>
     * 因为**那一行送不到 bash 手里**。在 Windows 上，JVM 把参数拼成一条给
     * {@code CreateProcess} 的命令行，而 MSYS 那层再把它拆回 argv —— 这一来一回
     * 会吃掉引号，尤其是**双引号**（实测：{@code printf '%s\n' "x y"} 到 bash 手里成了
     * {@code printf %s\n x y}，引号和反斜杠都没了，输出成了 "x"）。命令行里带中文时更糟：
     * 那些字节会**吃掉紧挨着它的那个引号**，于是 shell 报"引号没配平"。这些都不是
     * "模型写错了"，是我们送命令的方式有损。而双引号在模型写的命令里到处都是：JSON、
     * {@code -e "…"}、带空格的搜索串 —— 所以这条路不是"偶尔出错"，是对**任意文本**
     * 这个输入域不成立，而我们收的正是任意文本。
     *
     * <p>**这条损耗是我们独有的，而且是量出来的**：Claude Code 和 deepseek-harness 都跑在 Node 上，
     * 所以 {@code bash -c} 那条路对它们成立、对 JVM 上的我们不成立 —— 同一个做法在不同
     * runtime 上不等价，不是"照抄错了"。试过的两条绕开也都是坏的：把内容挪进环境变量、
     * {@code -c} 里只留 {@code eval "$CODELOOM_CMD"} —— {@code -c} 那个参数**自己**的
     * 双引号照样被吃掉。
     *
     * <p>**这是 Windows 独有的**：Linux/macOS 上 JVM 把 argv 直接交给内核（{@code execve}），
     * 中间没有"拼成一条字符串、再被别人拆开"这一层，所以 {@code bash -c} 在那边是完好的。
     * 我们仍然只留一条路（脚本文件）：它两边都对，为这个多一个平台分支不划算。
     *
     * <p>改成落一份脚本文件去跑，整条路上就只剩下一个**纯 ASCII 的路径**作为参数，
     * 没有引号、没有反斜杠、没有多字节 —— 上面那些用例全部原样到达。
     *
     * @throws IllegalStateException 这台机器上没有 shell。调用方应当先问
     *                               {@link #present()}，把"没 shell"变成一句人看得懂的报错
     */
    public List<String> argvFor(Path script) {
        if (executable == null) {
            throw new IllegalStateException(
                    "这台机器上没有找到可用的 shell（bash）。命令现在收的是整串命令，"
                            + "没有 shell 就解释不了它 —— 装一个 Git for Windows，"
                            + "或者用 " + ENV + " / codeloom.command-shell 指定路径。");
        }
        // 路径里的反斜杠要换成斜杠：反斜杠在"JVM → MSYS"那条路上会被解释掉
        //（实测 `a\b` 到 bash 手里变成了 `ab`）
        return List.of(executable, script.toString().replace('\\', '/'));
    }

    private static Optional<CommandShell> onPath() {
        // Windows 上要连扩展名一起试：PATH 里可能只有 bash.exe。
        // **只要 bash**：sh 是另一族（见类注释），它下面那些写法会静默变形
        List<String> names = isWindows() ? List.of("bash.exe", "bash") : List.of("bash");
        for (String directory : pathEntries()) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(directory, name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(of(candidate.toString()));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CommandShell> knownLocations() {
        // 不列 /bin/sh：见类注释，那是另一族 shell
        List<String> candidates = isWindows()
                ? List.of(
                        "C:\\Program Files\\Git\\bin\\bash.exe",
                        "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                        "C:\\Program Files (x86)\\Git\\bin\\bash.exe")
                : List.of("/bin/bash", "/usr/bin/bash", "/usr/local/bin/bash");
        for (String candidate : candidates) {
            if (Files.isRegularFile(Path.of(candidate))) {
                return Optional.of(of(candidate));
            }
        }
        return Optional.empty();
    }
}
