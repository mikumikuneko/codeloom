package com.codeloom.workspace.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 名单比对规则：去掉目录和扩展名；自带目录的词一律不算。 */
class CommandPolicyTest {

    @Test
    @DisplayName("baseName 只去目录，保留扩展名 —— 它是裸名比对的第一步")
    void baseNameDropsOnlyTheDirectory() {
        assertThat(CommandPolicy.baseName("C:\\tools\\mvn.cmd")).isEqualTo("mvn.cmd");
        assertThat(CommandPolicy.baseName("/usr/bin/mvn")).isEqualTo("mvn");
        assertThat(CommandPolicy.baseName("C:/x/npm.CMD")).isEqualTo("npm.CMD");
        assertThat(CommandPolicy.baseName("  mvn.cmd  ")).isEqualTo("mvn.cmd");
        assertThat(CommandPolicy.baseName(null)).isEmpty();
        assertThat(CommandPolicy.baseName("   ")).isEmpty();
    }

    @Test
    @DisplayName("bareExecutableName 再去扩展名 —— 白名单比对用它，mvn.cmd 和 mvn.exe 是同一个程序")
    void bareExecutableNameAlsoDropsTheExtension() {
        assertThat(CommandPolicy.bareExecutableName("C:\\tools\\mvn.cmd")).isEqualTo("mvn");
        assertThat(CommandPolicy.bareExecutableName("mvn.exe")).isEqualTo("mvn");
        assertThat(CommandPolicy.bareExecutableName("run.bat")).isEqualTo("run");
        assertThat(CommandPolicy.bareExecutableName("node")).isEqualTo("node");
        // 识别后缀不区分大小写，但返回的名字保持原样（Linux 上大小写有意义）
        assertThat(CommandPolicy.bareExecutableName("NPM.CMD")).isEqualTo("NPM");
    }

    @Test
    @DisplayName("白名单按裸名匹配：mvn 和 mvn.cmd 都放行")
    void allowListMatchesByBareName() {
        CommandPolicy policy = CommandPolicy.allowList("mvn", "node");

        assertThat(policy.isAllowed("mvn")).isTrue();
        assertThat(policy.isAllowed("mvn.cmd")).isTrue();
        assertThat(policy.isAllowed("node")).isTrue();
        assertThat(policy.isAllowed("gradle")).isFalse();
    }

    @Test
    @DisplayName("【收整串之后跑的】带了目录的词一律不算 —— /tmp/evil/mvn 不是名单里那个 mvn")
    void aWordCarryingADirectoryIsNeverAllowed() {
        // 那一行原样交给 shell，带路径就会真的执行那个文件；它不在"PATH 上的程序"
        // 这个语义里，因此需要人看着路径批准
        CommandPolicy policy = CommandPolicy.allowList("mvn", "node");

        assertThat(policy.isAllowed("C:\\tools\\mvn.exe")).isFalse();
        assertThat(policy.isAllowed("/usr/bin/mvn")).isFalse();
        assertThat(policy.isAllowed("./gradlew")).isFalse();
        assertThat(policy.isAllowed("bin/mvn")).isFalse();
        // 连"看起来就是我们自己那个"也不行 —— 判据看的是这一行文本，不是意图
        assertThat(policy.isAllowed("C:/Program Files/Git/bin/mvn")).isFalse();
    }

    @Test
    @DisplayName("空词、纯空白的词都不算放行 —— 名单里不会有它们")
    void blankWordsAreNotAllowed() {
        CommandPolicy policy = CommandPolicy.allowList("mvn");

        assertThat(policy.isAllowed("")).isFalse();
        assertThat(policy.isAllowed("   ")).isFalse();
        assertThat(policy.isAllowed(null)).isFalse();
    }
}
