package com.codeloom.workspace.exec;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 执行命令时用来解释那一行文本的 shell，即 {@code [shell, 脚本文件]} 的前一截。
 *
 * <p>命令是一行文本（管道、重定向、{@code &&} 都属于这一行），只有 shell 能解释。
 * 只认 bash：{@code sh} 会让 bash 风格的语法静默变形，也没有其它降级路径。找不到
 * shell 时只让"跑命令"失败，应用照常启动。理由见
 * {@code docs/decisions/architecture/2026-09-24-commands-are-whole-lines-through-bash.md}。
 *
 * <p>路径不写死：Git for Windows 可以装在任意盘符，默认安装位置只是兜底候选。
 * 配置成路径时要求文件存在，不存在就跳过继续探测，避免把配置打错推迟成"进程起不来"。
 */
public final class CommandShell {

    /** 覆盖自动探测的环境变量。 */
    public static final String ENV = "CODELOOM_COMMAND_SHELL";

    /** 没有 shell 时为 null，表示命令跑不了，不是换一种方式跑。 */
    private final String executable;

    private CommandShell(String executable) {
        this.executable = executable;
    }

    /** 没有可用的 shell。 */
    public static CommandShell none() {
        return new CommandShell(null);
    }

    /**
     * 用一个已知的路径或裸名字造一个 shell。
     *
     * @param executable shell 路径或名字，会去掉首尾空白
     * @return 对应的 shell
     * @throws IllegalArgumentException 空值；"没有 shell"只能用 {@link #none()} 表示
     */
    public static CommandShell of(String executable) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("shell 不能为空 —— 没有 shell 请用 none()");
        }
        return new CommandShell(executable.strip());
    }

    /**
     * 按顺序探测 shell：配置值、{@link #ENV}、PATH 上的 bash、git 安装目录、常见安装位置。
     *
     * <p>配置值像路径时（含分隔符）要求文件存在，不存在就跳过并继续；裸名字交给 PATH。
     * 全部找不到时返回 {@link #none()}。
     *
     * @param configured 配置的 shell 路径或名字；null 或空白表示跳过
     * @return 找到的 shell，或 {@link #none()}
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

    /** 配的是路径还是裸名字：只看有没有分隔符，有分隔符才要求文件存在。 */
    private static boolean looksLikePath(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    }

    /** 这个路径上是否有文件；路径本身非法一律算没有。 */
    private static boolean exists(String path) {
        try {
            return Files.isRegularFile(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * 顺着 PATH 上的 {@code git} 找到安装目录，再到 {@code bin/bash.exe} 与
     * {@code usr/bin/bash.exe} 找 bash。
     *
     * <p>Git for Windows 默认只把含 {@code git.exe} 的 cmd 目录加进 PATH，bash 不在
     * 那里；常见安装位置又写死了盘符，这条路径不需要猜盘符。
     *
     * @return 找到的 shell；没有时为 {@link Optional#empty()}
     */
    private static Optional<CommandShell> besideGit() {
        for (String directory : pathEntries()) {
            Path git = Path.of(directory, isWindows() ? "git.exe" : "git");
            if (!Files.isRegularFile(git)) {
                continue;
            }
            Path home = Path.of(directory).getParent();          // Git/cmd → Git
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

    /** PATH 上的目录；两处共用这一份切法。 */
    private static List<String> pathEntries() {
        String path = System.getenv("PATH");
        return path == null || path.isBlank() ? List.of() : List.of(path.split(java.io.File.pathSeparator));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 这台机器上有没有可用 shell；没有时命令跑不了，见类注释。
     *
     * @return 有可用 shell 时为 true
     */
    public boolean present() {
        return executable != null;
    }

    /**
     * 探测到的 shell。用于启动日志，以便分辨"没找到 shell"和"运行的是旧代码"。
     *
     * @return shell 路径或名字；没有 shell 时为 null
     */
    public String executablePath() {
        return executable;
    }

    /**
     * 把脚本文件拼成 argv：{@code [shell, 脚本路径]}，路径中的反斜杠换成斜杠。
     *
     * <p>不走 {@code bash -c "COMMAND"}：Windows 上 JVM 到 MSYS 的参数往返会吃掉引号、
     * 反斜杠和多字节字符（实测 {@code printf '%s\n' "x y"} 输出成了 {@code x}），
     * 对任意文本都不成立。落成脚本文件后，命令行上只剩一个纯 ASCII 路径。
     * Linux/macOS 也走同一条路径，不为平台多一个分支。理由见
     * {@code docs/decisions/architecture/2026-09-24-commands-are-whole-lines-through-bash.md}。
     *
     * @param script 已写入命令的脚本文件
     * @return 交给 {@link ProcessBuilder} 的 argv
     * @throws IllegalStateException 没有可用的 shell；调用方应先用 {@link #present()} 判断
     */
    public List<String> argvFor(Path script) {
        if (executable == null) {
            throw new IllegalStateException(
                    "这台机器上没有找到可用的 shell（bash）。命令现在收的是整串命令，"
                            + "没有 shell 就解释不了它 —— 装一个 Git for Windows，"
                            + "或者用 " + ENV + " / codeloom.command-shell 指定路径。");
        }
        // 反斜杠在 JVM 到 MSYS 的路径上会被解释掉（实测 a\b 到 bash 手里变成 ab）
        return List.of(executable, script.toString().replace('\\', '/'));
    }

    private static Optional<CommandShell> onPath() {
        // Windows 上连扩展名一起试：PATH 里可能只有 bash.exe。
        // 只要 bash：sh 会让 bash 风格的写法静默变形
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
        // 不列 /bin/sh：那是另一族 shell，见类注释
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
