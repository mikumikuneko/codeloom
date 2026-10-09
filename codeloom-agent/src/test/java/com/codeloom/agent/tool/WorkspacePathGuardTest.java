package com.codeloom.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 这是安全边界的测试。
 *
 * <p>工具参数全部来自模型输出 —— 也就是不可信输入。没有这道守卫，
 * {@code read_file} 就是任意文件读取，{@code edit_file} 就是任意文件写入。
 */
class WorkspacePathGuardTest {

    @TempDir
    Path worktree;

    @ParameterizedTest
    @ValueSource(strings = {
            "../outside.txt",
            "../../etc/passwd",
            "src/../../outside.txt",
            "src/main/../../../outside.txt",
            "..",
            "./../outside.txt",
    })
    @DisplayName("各种 ../ 组合一律拒绝 —— 注意 normalize 必须在检查【之前】做")
    void rejectsParentTraversal(String attempt) {
        assertThatThrownBy(() -> WorkspacePathGuard.resolve(worktree, attempt))
                .isInstanceOf(WorkspacePathGuard.PathEscapeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/etc/passwd",
            "/tmp/x",
            "C:\\Windows\\System32\\drivers\\etc\\hosts",
            "D:/secrets.txt",
    })
    @DisplayName("绝对路径一律拒绝：它没有『相对工作区』的语义")
    void rejectsAbsolutePaths(String attempt) {
        assertThatThrownBy(() -> WorkspacePathGuard.resolve(worktree, attempt))
                .isInstanceOf(WorkspacePathGuard.PathEscapeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("空路径拒绝")
    void rejectsBlank(String attempt) {
        assertThatThrownBy(() -> WorkspacePathGuard.resolve(worktree, attempt))
                .isInstanceOf(WorkspacePathGuard.PathEscapeException.class);
    }

    @Test
    @DisplayName("正常相对路径解析到工作区内部")
    void resolvesNormalRelativePaths() throws IOException {
        Files.createDirectories(worktree.resolve("src/main/java"));

        assertThat(WorkspacePathGuard.resolve(worktree, "src/main/java/Foo.java"))
                .isEqualTo(worktree.toAbsolutePath().normalize().resolve("src/main/java/Foo.java"));

        // ./ 前缀与重复分隔符都会被 normalize 掉
        assertThat(WorkspacePathGuard.resolve(worktree, "./src//main/java/Foo.java"))
                .isEqualTo(WorkspacePathGuard.resolve(worktree, "src/main/java/Foo.java"));
    }

    @Test
    @DisplayName("工作区自身（.）也允许 —— 用于 grep/glob 的默认搜索范围")
    void allowsWorktreeRootItself() {
        assertThat(WorkspacePathGuard.resolve(worktree, "."))
                .isEqualTo(worktree.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("名字里带 .. 但【不是】路径穿越的，不能误伤")
    void doesNotOverBlockLegitimateNames() {
        // "..foo" 是合法文件名，不是上级目录
        assertThat(WorkspacePathGuard.resolve(worktree, "..foo/bar.txt"))
                .isEqualTo(worktree.toAbsolutePath().normalize().resolve("..foo/bar.txt"));

        assertThat(WorkspacePathGuard.resolve(worktree, "a..b.txt"))
                .isEqualTo(worktree.toAbsolutePath().normalize().resolve("a..b.txt"));
    }

    @Test
    @DisplayName("relativize 把绝对路径收敛回工作区内的相对形式（避免输出里泄漏环境信息）")
    void relativizeHidesAbsolutePaths() {
        Path inside = worktree.resolve("src/Foo.java");

        assertThat(WorkspacePathGuard.relativize(worktree, inside)).isEqualTo("src/Foo.java");
    }
}
