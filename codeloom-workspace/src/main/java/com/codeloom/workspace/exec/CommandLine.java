package com.codeloom.workspace.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把模型写的那一行命令**保守地**切成词 —— 只为了"要不要先问人"那一道判据看得懂它。
 *
 * <h2>它只在"真能看懂"的时候给答案</h2>
 * 命令的最终解释权在 shell 手里（{@link CommandShell}），而 shell 会做一大堆我们
 * 肉眼看不见的事：展开变量、跑命令替换、按 glob 拼路径、认 {@code ~}。所以这里的
 * 规矩是：**只要这行命令里出现任何"解释起来会变样"的东西，就说看不懂**
 * （{@link Optional#empty()}），由调用方按"要问"处理。
 *
 * <p>看不懂的那些字符，以及各自的理由：
 * <table>
 *   <tr><td>{@code $}</td><td>变量展开 —— {@code $HOME} 会变成你看不见的路径</td></tr>
 *   <tr><td>{@code `}</td><td>命令替换，藏第二条命令最经典的写法</td></tr>
 *   <tr><td>{@code \}</td><td>转义：它能让下一个字符换个意思（含 Windows 路径里的分隔符）</td></tr>
 *   <tr><td>{@code ( ) { }}</td><td>子 shell 与花括号展开</td></tr>
 *   <tr><td>{@code ; | & < >}</td><td>一条变两条、前后接管道、读写别的文件</td></tr>
 *   <tr><td>换行、回车</td><td>长得像一行，其实是两行</td></tr>
 *   <tr><td>{@code ~}（只在词首）</td><td>展开成用户目录 —— 一字之差，删的是家目录</td></tr>
 * </table>
 *
 * <p>{@code ~} 刻意**只看词首**：引号里的 {@code "~"} 不展开，
 * {@code grep -n "~" file} 里那个也不是路径。全都拦掉会让一条完全正常的搜索
 * 每次都问人，而多问一次正是这套东西最烦人的失败方式。
 *
 * <h2>引号是唯一被理解的东西 —— 而且两种引号挡的东西不一样</h2>
 * 成对的引号让 {@code "a b"} 算一个词（不然切出来的词和 shell 看到的不是一回事）。
 * 引号没配平就说不懂 —— 那行命令在 shell 里的样子我们猜不出来。
 *
 * <p>引号里的东西按 shell 的规矩重新判一遍，两种引号**不是同一张表**：
 * <ul>
 *   <li><b>单引号</b>里什么都不解释 —— {@code '$HOME'} 就是那五个字符。
 *       所以引号里只剩"这行其实不止一行"要防。</li>
 *   <li><b>双引号</b>里 {@code $}、反引号、反斜杠照样生效，所以那三个还是不懂。</li>
 *   <li>而 {@code ; | & < > ( ) { } ~} 在两种引号里都是**字面量** —— 于是
 *       {@code node -e "setTimeout(()=>{},1200)"}、{@code grep -n 'a|b' f}
 *       都是看得懂的，不必为它们每次点一次批准。</li>
 * </ul>
 *
 * <h2>它**不是**安全边界</h2>
 * 通配符（{@code *}、{@code ?}）没被拦：它们只按当前目录展开，而当前目录正是项目根，
 * 所以 {@code rm -rf *} 落在项目里。**残留的洞**老老实实列在这里：{@code rm -rf .*}
 * 展开出来的 {@code ..} 我们看不见（只有 GNU rm 自己会拒绝删它）；
 * 软链接指向外面；以及把删除藏在程序内部（{@code node -e "…rmSync('/')"}）。
 * 这些拦不住，也不该假装拦住 —— 这道判据的作用是"少问几次"，不是"挡住攻击"。
 *
 * <p>参考实现把这件事做到了另一个量级：Claude Code 用 tree-sitter 解析 bash。
 * 一个简化版解析器的失败方式恰好是"我切错了但我说没问题"，那比"多说一句看不懂"
 * 坏得多 —— 所以这里选择简单、保守，并且**承认自己不懂**。
 */
public final class CommandLine {

    /**
     * 不在引号里时，会改变解释结果的字符。
     *
     * <p>{@code ~} 不在这里 —— 它只在词首才展开，单独判，见类注释。
     */
    private static final String UNREADABLE_PLAIN = "$`\\(){};|&<>\n\r";

    /**
     * 在**双引号**里仍然会被解释的字符。
     *
     * <p>双引号挡得住 {@code ; | & < > ( ) { } ~}（它们在引号里是字面量），
     * 挡不住 {@code $}、反引号和反斜杠 —— 那三个在双引号里照样生效。
     */
    private static final String UNREADABLE_IN_DOUBLE_QUOTES = "$`\\\n\r";

    /**
     * 在**单引号**里会被解释的字符：只有换行。
     *
     * <p>单引号是唯一一个不做任何解释的引法，{@code '$HOME'} 就是那五个字符本身。
     * 所以引号里只剩"这行其实不止一行"这一件事要防。
     */
    private static final String UNREADABLE_IN_SINGLE_QUOTES = "\n\r";

    private CommandLine() {
    }

    /**
     * 这行命令能安全切开时给出它的词；看不懂时给空。
     *
     * @param commandLine 模型写的那一行
     * @return 词表（引号内的空格不当分隔），或者 {@link Optional#empty()} 表示
     *         "这行命令我们看不懂，请按需要批准处理"
     */
    public static Optional<List<String>> simpleWords(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return Optional.empty();
        }

        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;              // 0 = 不在引号里
        boolean wordStarted = false; // 引号里的空串也算"开过一个词"
        boolean atWordStart = true;  // 下一个字符是不是词的第一个字符（判 ~ 用）

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);

            if (quote == '\'' || quote == '"') {
                if (c == quote) {
                    quote = 0;
                    continue;
                }
                // 引号挡住的东西不一样：单引号里什么都不解释，双引号里 $、反引号、
                // 反斜杠照样生效 —— 所以两边查的不是同一张表
                String unreadable = quote == '"' ? UNREADABLE_IN_DOUBLE_QUOTES : UNREADABLE_IN_SINGLE_QUOTES;
                if (unreadable.indexOf(c) >= 0) {
                    return Optional.empty();
                }
                word.append(c);
                continue;
            }

            if (c == '\'' || c == '"') {
                quote = c;
                wordStarted = true;
                atWordStart = false;
                continue;
            }
            if (UNREADABLE_PLAIN.indexOf(c) >= 0) {
                return Optional.empty();
            }
            if (c == '~' && atWordStart) {
                return Optional.empty();
            }
            if (Character.isWhitespace(c)) {
                if (wordStarted) {
                    words.add(word.toString());
                    word.setLength(0);
                    wordStarted = false;
                }
                atWordStart = true;
                continue;
            }
            word.append(c);
            wordStarted = true;
            atWordStart = false;
        }

        if (quote != 0) {
            // 引号没收口：这行命令在 shell 里会被当成"还没写完"，我们更不该猜
            return Optional.empty();
        }
        if (wordStarted) {
            words.add(word.toString());
        }
        return Optional.of(words);
    }
}
