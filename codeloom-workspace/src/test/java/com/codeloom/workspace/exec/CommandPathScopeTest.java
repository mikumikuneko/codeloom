package com.codeloom.workspace.exec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 免审批名单缺的那一半：**它碰的东西在工作区里吗。**
 *
 * <p>这一层决定的是"要不要问人"，不是"能不能跑" —— 所以下面的 false
 * 一律该读成"会问你一次"，而不是"会被拒绝"。
 *
 * <p>输入是**已经切好的词**：切词那一步在 {@link CommandLine}，切不开的命令根本
 * 走不到这里（调用方已经按"要问"处理了）。所以下面直接给词表。
 */
class CommandPathScopeTest {

    @TempDir
    Path worktree;

    private Path root;

    @BeforeEach
    void setUp() {
        root = worktree.toAbsolutePath().normalize();
    }

    private boolean inside(String... command) {
        return CommandPathScope.staysInside(root, List.of(command));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【项目树内自由】rm / mv 动自己项目里的东西，不问")
    void mutatingInsideTheTreeIsFree() {
        assertThat(inside("rm", "-rf", "node_modules")).isTrue();
        assertThat(inside("rm", "-rf", "./dist")).isTrue();
        assertThat(inside("rm", "src/old/File.java")).isTrue();
        assertThat(inside("rm", "-rf", "--", "build")).isTrue();
        assertThat(inside("mv", "A.java", "src/main/java/A.java")).isTrue();
        assertThat(inside("cp", "-r", "a", "b")).isTrue();
        assertThat(inside("rmdir", "empty-dir")).isTrue();
    }

    @Test
    @DisplayName("【树外要问】同一个 rm，参数跑到工作区外面就问一次")
    void mutatingOutsideTheTreeAsks() {
        assertThat(inside("rm", "-rf", "/")).isFalse();
        assertThat(inside("rm", "-rf", "..")).isFalse();
        assertThat(inside("rm", "-rf", "../../sibling")).isFalse();
        assertThat(inside("mv", "A.java", "/tmp/elsewhere")).isFalse();
        // 只要**有一个**参数跑出去就算了 —— 前面那几个在不在里面不重要
        assertThat(inside("rm", "-rf", "node_modules", "/etc")).isFalse();
    }

    @Test
    @DisplayName("波浪号是树外 —— shell 会把它展开成用户目录，那是离得很远的一个地方")
    void tildeCountsAsOutside() {
        // 上游（CommandLine）在词首见到 ~ 就已经说"看不懂"了，这里再兜一道：
        // 它确实是一条通往树外的路径，而不是"认不出来"
        assertThat(inside("rm", "-rf", "~")).isFalse();
        assertThat(inside("rm", "-rf", "~/Documents")).isFalse();
        assertThat(inside("mv", "A.java", "~backup/x")).isFalse();
    }

    @Test
    @DisplayName("读命令不看路径：看一眼外面的文件不改任何东西，不该为它点批准")
    void readOnlyCommandsAreNotScoped() {
        assertThat(inside("ls", "/etc")).isTrue();
        assertThat(inside("cat", "C:\\Windows\\win.ini")).isTrue();
        assertThat(inside("grep", "-r", "foo", "/")).isTrue();
        // 构建工具也不看：它们的参数是目标名，不是路径，按路径看只会得到噪声
        assertThat(inside("mvn", "-q", "test")).isTrue();
    }

    @Test
    @DisplayName("没有路径参数、或者认不出来的一律不问 —— 误判成'要问'更烦人")
    void uncertainCasesStayQuiet() {
        assertThat(inside("rm")).isTrue();
        assertThat(inside("rm", "-rf")).isTrue();
        // 选项不是路径
        assertThat(inside("rm", "--recursive", "--force", "dist")).isTrue();
        // 通配符在 Windows 上是非法路径字符 —— 解析不了，交给命令自己去报错
        assertThat(inside("rm", "-rf", "*")).isTrue();
    }
}
