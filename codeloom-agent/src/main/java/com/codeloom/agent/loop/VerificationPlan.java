package com.codeloom.agent.loop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 改动之后**平台强制**跑的那条验证命令。
 *
 * <h2>为什么是"强制"而不是"问模型要不要跑"</h2>
 * 模型经常觉得自己改对了就收工。让它自己决定要不要验证，等于把"AI 写的代码可不可信"
 * 这件事交给 AI 自己判断 —— 那是循环论证。
 *
 * <p>所以：一旦这轮**动过文件**，平台就在它说"做完了"之后自动跑一次构建/测试。
 * 结果连同命令、退出码一起落成 {@code VerificationResult} 事件，构成证据链。
 *
 * @param command 验证命令，**分段**写（{@code ["mvn", "-q", "test"]}）。它的参数由我们
 *                构造（可信），所以这里保持分段；交给执行器之前会拼成一行，见
 *                {@link #commandLine()}
 * @param timeout 验证可能很慢（跑全套测试），给足时间
 */
public record VerificationPlan(List<String> command, Duration timeout) {

    /**
     * 拼进一行之后会**改变意思**的字符。
     *
     * <p>执行器收的是整行命令（见 {@code CommandExecutor}），所以这一行会被 shell
     * **重新解释一遍**：一个带空格的词拼进去就成了两个词，一个带引号的词会打乱整行的
     * 引号配平，一个 {@code $} 会让它变成别的东西。我们自己写的那几条
     * （{@code mvn -q test}、{@code npm test --silent}）一个都不沾 ——
     * 沾上就是有人写错了，那时候当场炸掉最省事。
     */
    private static final Pattern UNSAFE_IN_A_LINE = Pattern.compile("[\\s'\"\\\\$`~|&;<>(){}]");

    /**
     * {@link #detect} 可能产出的**全部可执行文件**。它们必须都在命令白名单里
     * （默认值配在 {@code application.yml} 的 {@code codeloom.command-whitelist}）。
     *
     * <h2>这条约束是**谁**在守</h2>
     * 不是运行时拦的：验证由平台直接调执行器（见 {@code VerificationRunner}），
     * 根本不经过审批那一道，所以白名单里有没有 {@code mvn} 对"自动验证跑不跑得起来"
     * 没有影响。**它守的是另一半**：平台自己会跑的那些名字，也应当是不必问人的那一类 ——
     * 名单里少了 {@code mvn}，模型自己想跑一次 {@code mvn} 就得先等人点一下。
     *
     * <p>守住它的是"探测 ⊆ 白名单"这条**可执行的断言**
     * （见 {@code VerificationWhitelistTest}），而不是注释里的一句提醒 ——
     * 两边住在两个模块里，靠人记得保持一致是靠不住的。
     */
    public static final java.util.Set<String> REQUIRED_EXECUTABLES =
            java.util.Set.of("mvn", "gradle", "npm", "pytest");

    /** 不做验证。纯问答、纯读代码的场景用这个，免得白白跑一遍构建。 */
    public static final VerificationPlan NONE = new VerificationPlan(List.of(), Duration.ZERO);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    public VerificationPlan {
        command = List.copyOf(command == null ? List.of() : command);
        for (String word : command) {
            if (word.isBlank() || UNSAFE_IN_A_LINE.matcher(word).find()) {
                throw new IllegalArgumentException(
                        "验证命令的每个词都必须能安全地拼进一行，收到：" + word
                                + "（整条：" + String.join(" ", command) + "）");
            }
        }
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    public static VerificationPlan of(List<String> command) {
        return new VerificationPlan(command, DEFAULT_TIMEOUT);
    }

    public boolean isEnabled() {
        return !command.isEmpty();
    }

    /**
     * 这行命令 —— 交给执行器的那一份，也是落进事件、注入给模型的那一份。
     *
     * <h2>为什么只有一个名字</h2>
     * 执行器、事件、提示词用的是**同一个字符串**，只有这一个名字 ——
     * 分成两个名字只会让人以为它们不一样。
     */
    public String commandLine() {
        return String.join(" ", command);
    }

    /**
     * 按工作区里的构建文件猜一条验证命令。
     *
     * <p>这是**兜底**：项目级配置优先。猜的时候只认最主流的几种构建文件，
     * 认不出来就返回空 —— 宁可"没验证"，也不要跑一条错的命令然后让模型
     * 去修一个根本不存在的问题。
     *
     * <p>返回的这几个可执行文件都必须在白名单里放行，见 {@link #REQUIRED_EXECUTABLES}。
     * （注意那不是"能不能跑"的问题 —— 见那个常量的注释。）
     */
    public static Optional<VerificationPlan> detect(Path worktree) {
        if (Files.exists(worktree.resolve("pom.xml"))) {
            return Optional.of(of(List.of("mvn", "-q", "test")));
        }
        if (Files.exists(worktree.resolve("build.gradle"))
                || Files.exists(worktree.resolve("build.gradle.kts"))) {
            return Optional.of(of(List.of("gradle", "test")));
        }
        if (Files.exists(worktree.resolve("package.json"))) {
            return Optional.of(of(List.of("npm", "test", "--silent")));
        }
        if (Files.exists(worktree.resolve("pyproject.toml"))
                || Files.exists(worktree.resolve("pytest.ini"))) {
            return Optional.of(of(List.of("pytest", "-q")));
        }
        return Optional.empty();
    }
}
