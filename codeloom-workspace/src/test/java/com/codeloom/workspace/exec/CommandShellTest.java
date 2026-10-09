package com.codeloom.workspace.exec;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 包 shell：怎么把命令交给它。
 *
 * <p>现在是**落一份脚本文件，把文件路径交给 shell** —— 若直接把那一行交给
 * {@code bash -c}，引号、反斜杠、多字节字符都会在"JVM → MSYS"那一段路上被吃掉
 *（见 {@link CommandShell#argvFor}）。
 *
 * <p>测试**真的起一个 shell 跑一遍**：这一层的成败只有真执行才看得出来，
 * 光断言"拼出来的 argv 长什么样"验不出脚本文件这条路的价值。
 */
class CommandShellTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("没有 shell 时直接抛 —— 一行文本没有 shell 就没人能解释它")
    void withoutAShellThereIsNothingToRun() {
        assertThat(CommandShell.none().present()).isFalse();

        assertThatThrownBy(() -> CommandShell.none().argvFor(tempDir.resolve("cmd.sh")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shell");
    }

    @Test
    @DisplayName("argv 就是 [shell, 脚本路径]，路径里的反斜杠换成斜杠")
    void argvIsTheShellPlusTheScriptPath() {
        Path script = tempDir.resolve("cmd.sh");

        List<String> argv = CommandShell.of("bash").argvFor(script);

        assertThat(argv).hasSize(2);
        assertThat(argv.getFirst()).isEqualTo("bash");
        assertThat(argv.get(1)).doesNotContain("\\");
        assertThat(argv.get(1)).endsWith("cmd.sh");
    }

    @Test
    @DisplayName("真跑一遍：引号、反斜杠、多字节字符原样到达 —— 这才是走脚本文件的理由")
    void trickyLinesArriveIntactThroughARealShell() throws IOException, InterruptedException {
        CommandShell shell = CommandShell.detect(null);
        // 没装 shell 的机器上跳过 —— 这不是"测试通过"，是"这台机器上没这件事可测"
        Assumptions.assumeTrue(shell.present(), "这台机器上找不到 shell，跳过一次真执行");

        // 这三种写法在 `bash -c` 那条路上**都会坏**（实测）：双引号被吃掉、
        // 单引号和反斜杠被吃掉、中文吃掉紧挨着它的引号（shell 报引号没配平）
        String script = String.join("\n",
                "node -e \"console.log(1+1)\"",
                "printf '%s\\n' \"x y\"",
                "echo \"构建完成\"");
        String printed = run(shell, script);

        assertThat(printed).contains("2").contains("x y").contains("构建完成");
    }

    @Test
    @DisplayName("找得到 shell 的话，它是 PATH 上真实存在的那个文件（不是写死的路径）")
    void detectedShellActuallyExists() {
        CommandShell shell = CommandShell.detect(null);
        Assumptions.assumeTrue(shell.present(), "这台机器上找不到 shell");

        // Git for Windows 可以装在任意盘符 —— 写死默认安装位置会失灵，
        // 所以探测只能靠 PATH 和"文件真的在"这两条
        assertThat(Files.isRegularFile(Path.of(shell.executablePath()))).isTrue();
    }

    @Test
    @DisplayName("配的路径不存在时**不采信**：跳过它继续找，而不是拿一个跑不起来的路径当真")
    void aConfiguredPathThatIsNotThereIsSkipped() {
        // 采信它的症状是延后的：要等到某条命令跑起来才报"无法启动进程"，
        // 那时已经看不出是配置里少了一个字母
        CommandShell shell = CommandShell.detect("/definitely/not/here/bash");

        assertThat(shell.executablePath()).isNotEqualTo("/definitely/not/here/bash");
    }

    @Test
    @DisplayName("裸名字不算路径 —— 那是交给 PATH 解析的，不查文件在不在")
    void aBareNameIsTrustedAndResolvedByPath() {
        assertThat(CommandShell.detect("bash").executablePath()).isEqualTo("bash");
    }

    @Test
    @DisplayName("空的 shell 造不出来 —— 「没有 shell」只有一个写法：none()")
    void anEmptyShellCannotBeBuilt() {
        assertThatThrownBy(() -> CommandShell.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandShell.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandShell.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String run(CommandShell shell, String commandLine) throws IOException, InterruptedException {
        Path script = tempDir.resolve("cmd.sh");
        Files.writeString(script, commandLine, StandardCharsets.UTF_8);
        try {
            Process process = new ProcessBuilder(shell.argvFor(script)).start();
            String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return printed;
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
