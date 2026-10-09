package com.codeloom.workspace.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保守切词：**看得懂就给词，看不懂就说看不懂。**
 *
 * <p>这些用例盯的不是"能不能切对每一个 shell 语法"，而是那条安全性质：
 * **凡是解释起来会变样的写法，一律走"看不懂"**。多一次"看不懂"的代价是让人
 * 多点一次批准；漏一次"看不懂"的代价是一条我们没看懂的路径被免审批放行。
 */
class CommandLineTest {

    private static Optional<List<String>> words(String line) {
        return CommandLine.simpleWords(line);
    }

    // ------------------------------------------------------------------
    // 看得懂的
    // ------------------------------------------------------------------

    @Test
    @DisplayName("简单命令按空白切词，第一个词就是可执行文件")
    void plainCommandsSplitOnWhitespace() {
        assertThat(words("mvn -q test")).contains(List.of("mvn", "-q", "test"));
        assertThat(words("  git   status  --short ")).contains(List.of("git", "status", "--short"));
        assertThat(words("ls")).contains(List.of("ls"));
    }

    @Test
    @DisplayName("成对引号里的空格不算分隔符 —— 不然我们切出来的词和 shell 看到的不是一回事")
    void quotedSpacesStayInsideTheWord() {
        assertThat(words("grep \"a b\" src")).contains(List.of("grep", "a b", "src"));
        assertThat(words("grep 'a b' src")).contains(List.of("grep", "a b", "src"));
        // 引号本身不进词里 —— shell 会把它们吃掉，我们照 shell 的样子给词
        assertThat(words("echo 'hi'")).contains(List.of("echo", "hi"));
        // 空串是一个**词**，不是"没有这个词"
        assertThat(words("echo ''")).contains(List.of("echo", ""));
    }

    @Test
    @DisplayName("引号里的元字符是**字面量** —— shell 不解释它们，我们也不该为它们点批准")
    void metacharactersInsideQuotesAreLiteral() {
        // 这两种引法都把 ; | & ( ) { } 变成普通字符。一条正常命令里出现它们不算"看不懂"，
        // 否则 node -e "…"、grep 'a|b' 这种天天要用的写法每次都得问人
        assertThat(words("node -e \"setTimeout(()=>{},1200)\""))
                .contains(List.of("node", "-e", "setTimeout(()=>{},1200)"));
        assertThat(words("grep -n 'a|b' f.txt")).contains(List.of("grep", "-n", "a|b", "f.txt"));
        assertThat(words("echo 'a;b&c'")).contains(List.of("echo", "a;b&c"));
    }

    @Test
    @DisplayName("两种引号挡的东西不一样：单引号里 $ 是字面量，双引号里它不是")
    void singleAndDoubleQuotesDifferOnDollar() {
        // 单引号是唯一一个不做任何解释的引法 —— '$HOME' 就是那五个字符
        assertThat(words("grep '$HOME' f.txt")).contains(List.of("grep", "$HOME", "f.txt"));
        // 双引号挡不住变量展开，所以那条路还是看不懂
        assertThat(words("grep \"$HOME\" f.txt")).isEmpty();
        assertThat(words("echo \"`date`\"")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 看不懂的：一条命令里能藏着第二条
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "grep x | wc -l",
            "echo a; rm -rf b",
            "mvn test && echo ok",
            "mvn test || echo fail",
            "echo hi > out.txt",
            "cat < in.txt",
            "echo x & sleep 1",
    })
    @DisplayName("管道、分号、与或、重定向、后台 —— 一条命令能被写成好几件事，一律看不懂")
    void operatorsMakeItUnreadable(String line) {
        assertThat(words(line)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "echo $HOME",
            "echo ${HOME}/x",
            "echo `whoami`",
            "echo $(rm -rf /)",
            "rm -rf ${DIR}",
            "echo a\\ b",
            "echo {a,b}",
            "sh -c (x)",
    })
    @DisplayName("变量、命令替换、转义、花括号、括号 —— 展开之后是什么我们看不见，一律看不懂")
    void expansionsMakeItUnreadable(String line) {
        assertThat(words(line)).isEmpty();
    }

    @Test
    @DisplayName("长得像一行，其实是两行 —— 换行和回车都不算")
    void newlinesAreNotOneLine() {
        assertThat(words("echo a\nrm -rf b")).isEmpty();
        assertThat(words("echo a\rrm -rf b")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 波浪号：只在词首算，因为只有词首才会展开
    // ------------------------------------------------------------------

    @Test
    @DisplayName("词首的波浪号看不懂 —— shell 会把它展开成用户目录，一字之差删的是家目录")
    void tildeAtWordStartIsUnreadable() {
        assertThat(words("rm -rf ~")).isEmpty();
        assertThat(words("rm -rf ~/Documents")).isEmpty();
        assertThat(words("cp -r ~/x .")).isEmpty();
    }

    @Test
    @DisplayName("引号里的波浪号**不是**路径 —— 一条正常的搜索不该为它每次都问人")
    void aQuotedTildeIsJustACharacter() {
        assertThat(words("grep -n \"~\" file.txt")).contains(List.of("grep", "-n", "~", "file.txt"));
    }

    // ------------------------------------------------------------------
    // 别的看不懂
    // ------------------------------------------------------------------

    @Test
    @DisplayName("引号没收口看不懂 —— 这行命令在 shell 里是「还没写完」")
    void unbalancedQuotesAreUnreadable() {
        assertThat(words("echo 'unclosed")).isEmpty();
        assertThat(words("echo \"unclosed")).isEmpty();
        assertThat(words("echo 'a\"b'")).contains(List.of("echo", "a\"b"));
    }

    @Test
    @DisplayName("空命令看不懂 —— 没有可看的东西")
    void blankLinesAreUnreadable() {
        assertThat(words("")).isEmpty();
        assertThat(words("   ")).isEmpty();
        assertThat(words(null)).isEmpty();
    }
}
