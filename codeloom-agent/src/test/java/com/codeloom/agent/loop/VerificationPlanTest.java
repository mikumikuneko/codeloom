package com.codeloom.agent.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证命令的探测。
 *
 * <p>不需要中间件，也不需要容器 —— 它只认几个文件是否存在。
 */
class VerificationPlanTest {

    /** 探测会认的构建文件，以及各自该用哪个可执行文件。 */
    private static final List<String> MARKERS =
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "package.json",
                    "pyproject.toml", "pytest.ini");

    @TempDir
    Path worktree;

    @Test
    @DisplayName("四种主流构建工具都认得出，且给的是各自那条命令")
    void recognisesTheMainBuildTools() throws IOException {
        assertDetects("pom.xml", List.of("mvn", "-q", "test"));
        assertDetects("build.gradle", List.of("gradle", "test"));
        assertDetects("build.gradle.kts", List.of("gradle", "test"));
        assertDetects("package.json", List.of("npm", "test", "--silent"));
        assertDetects("pyproject.toml", List.of("pytest", "-q"));
        assertDetects("pytest.ini", List.of("pytest", "-q"));
    }

    @Test
    @DisplayName("【跨模块不变量】探测出来的可执行文件，一个不落全在 REQUIRED_EXECUTABLES 里")
    void everyDetectedCommandUsesADeclaredExecutable() throws IOException {
        // 这条守的是"探测出来的命令"和"命令白名单"之间的耦合：两边住在不同模块、
        // 各自演化，而少放行一个的表现是**自动验证永远以"命令被拒"告终**。
        // 宣言在 VerificationPlan.REQUIRED_EXECUTABLES，白名单那一侧由
        // codeloom-app 的 VerificationWhitelistTest 断言
        for (String marker : MARKERS) {
            Files.writeString(worktree.resolve(marker), "");
            String executable = VerificationPlan.detect(worktree).orElseThrow().command().getFirst();

            assertThat(VerificationPlan.REQUIRED_EXECUTABLES)
                    .as("有 %s 时会探测出 %s，但它不在宣言里", marker, executable)
                    .contains(executable);

            Files.delete(worktree.resolve(marker));
        }
    }

    @Test
    @DisplayName("认不出来就返回空 —— 宁可「没验证」，也不要跑一条错的命令让模型去修不存在的问题")
    void unknownProjectsAreLeftAlone() {
        assertThat(VerificationPlan.detect(worktree)).isEmpty();
    }

    private void assertDetects(String marker, List<String> expectedCommand) throws IOException {
        Files.writeString(worktree.resolve(marker), "");

        assertThat(VerificationPlan.detect(worktree))
                .as("有 %s 时应当认出构建工具", marker)
                .isPresent()
                .get()
                .extracting(VerificationPlan::command)
                .isEqualTo(expectedCommand);

        Files.delete(worktree.resolve(marker));
    }
}
