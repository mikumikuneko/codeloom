package com.codeloom.agent.loop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 改动之后由平台强制执行的验证命令，不询问模型。一旦本轮动过文件，模型声称完成后
 * 平台自动运行它，并把命令、退出码和输出落成 {@code VerificationResult}。
 *
 * @param command 验证命令，按词分段（参数由平台构造，可信）；交给执行器前拼成一行
 * @param timeout 验证超时时间
 */
public record VerificationPlan(List<String> command, Duration timeout) {

    /**
     * 拼成一行后会被 shell 重新解释的字符。平台自己写的命令不含这些字符；
     * 出现即表示配置写错，构造时直接失败。
     */
    private static final Pattern UNSAFE_IN_A_LINE = Pattern.compile("[\\s'\"\\\\$`~|&;<>(){}]");

    /**
     * {@link #detect} 可能产出的全部可执行文件。它们都应出现在命令白名单里：
     * 验证本身不经过审批，但平台会跑的名字也不该要求用户先点一次批准。
     * “探测 ⊆ 白名单”由跨模块测试断言。
     */
    public static final java.util.Set<String> REQUIRED_EXECUTABLES =
            java.util.Set.of("mvn", "gradle", "npm", "pytest");

    /** 不做验证；纯问答、纯读代码场景使用。 */
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

    /**
     * 用默认超时构造验证计划。
     *
     * @param command 分段命令
     * @return 验证计划
     */
    public static VerificationPlan of(List<String> command) {
        return new VerificationPlan(command, DEFAULT_TIMEOUT);
    }

    /**
     * @return 是否配置了验证命令
     */
    public boolean isEnabled() {
        return !command.isEmpty();
    }

    /**
     * 拼成一行的命令；执行器、事件和注入给模型的文本都使用这同一份字符串。
     *
     * @return 命令行
     */
    public String commandLine() {
        return String.join(" ", command);
    }

    /**
     * 按工作区里的构建文件推断验证命令；项目级配置优先。只识别主流构建文件，
     * 认不出来就返回空，宁可不验证也不跑错命令。可执行文件须在
     * {@link #REQUIRED_EXECUTABLES} 中。
     *
     * @param worktree 工作区
     * @return 推断出的验证计划；无法识别时为空
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
