package com.codeloom.workspace.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把模型写的一行命令保守地切成词，供"要不要先问人"的判据使用。
 *
 * <p>只要出现任何解释起来会改变结果的内容，就返回 {@link Optional#empty()}，调用方
 * 按"要问"处理：{@code $}、反引号、反斜杠、子 shell 与花括号、管道与重定向、单个
 * {@code &}、换行与回车，以及词首的 {@code ~}。{@code &&}、{@code ||}、{@code ;}
 * 是串联，按段切开后每段各自校验，见 {@link #simpleCommands}。
 *
 * <p>成对引号内的空格不切词，引号未配平则看不懂。单引号内只有换行和回车不可接受；双引号内
 * {@code $}、反引号和反斜杠仍然生效；{@code ; | & < > ( ) { } ~} 在两种引号里都是
 * 字面量。{@code ~} 只在词首判为看不懂，因为只有词首才会展开。
 *
 * <p>它不是安全边界：通配符按工作目录展开，软链接、程序内部的删除、通配符展开出的
 * {@code ..} 都拦不住。相关决策见
 * {@code docs/decisions/bug-fix/2026-10-17-chained-commands-no-longer-ask.md} 与
 * {@code docs/decisions/architecture/2026-09-24-commands-are-whole-lines-through-bash.md}。
 */
public final class CommandLine {

    /**
     * 不在引号里时会改变解释结果的字符。
     *
     * <p>{@code ~} 不在这里：它只在词首展开，单独判断，见类注释。
     */
    private static final String UNREADABLE_PLAIN = "$`\\(){};|&<>\n\r";

    /**
     * 按引号外的 {@code &&}、{@code ||}、{@code ;} 把一行切成几条简单命令。
     *
     * <p>串联只是"依次跑几条"：每一段仍各自按 {@link #simpleWords} 及后续判据校验，
     * 任何一段不合格，整行都要问人。{@code |} 和单个 {@code &} 不切，仍算看不懂 ——
     * 管道改变数据流向，后台执行让命令不受等待。
     *
     * @param commandLine 模型写的一行
     * @return 每条命令的词表；空白输入、空段或任何一段读不懂时为 {@link Optional#empty()}
     */
    public static Optional<List<List<String>>> simpleCommands(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return Optional.empty();
        }
        List<List<String>> commands = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (quote != 0) {
                segment.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                segment.append(c);
                continue;
            }
            boolean doubled = i + 1 < commandLine.length()
                    && commandLine.charAt(i + 1) == c
                    && (c == '&' || c == '|');
            boolean ends = c == ';' || doubled;
            if (!ends) {
                segment.append(c);
                continue;
            }
            Optional<List<String>> words = simpleWords(segment.toString());
            if (words.isEmpty() || words.get().isEmpty()) {
                return Optional.empty();
            }
            commands.add(words.get());
            segment.setLength(0);
            if (doubled) {
                i++;      // 跳过第二个 & 或 |
            }
        }
        Optional<List<String>> tail = simpleWords(segment.toString());
        if (tail.isEmpty() || tail.get().isEmpty()) {
            return Optional.empty();
        }
        commands.add(tail.get());
        return Optional.of(commands);
    }

    /**
     * 双引号里仍会被解释的字符：{@code $}、反引号、反斜杠与换行。
     *
     * <p>{@code ; | & < > ( ) { } ~} 在双引号里是字面量。
     */
    private static final String UNREADABLE_IN_DOUBLE_QUOTES = "$`\\\n\r";

    /**
     * 单引号里会被解释的字符：只有换行与回车。
     *
     * <p>{@code '$HOME'} 就是那五个字符本身。
     */
    private static final String UNREADABLE_IN_SINGLE_QUOTES = "\n\r";

    private CommandLine() {
    }

    /**
     * 这行命令能安全切开时给出它的词；看不懂时给空。
     *
     * @param commandLine 模型写的那一行
     * @return 词表（引号内的空格不当分隔）；null、空白、存在会改变解释结果的字符、
     *         引号未配平时为 {@link Optional#empty()}
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
                // 两种引号查的不是同一张表：单引号不做解释，双引号里 $、反引号、反斜杠生效
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
            // 引号未闭合：shell 会认为命令还没写完，这里不猜
            return Optional.empty();
        }
        if (wordStarted) {
            words.add(word.toString());
        }
        return Optional.of(words);
    }
}
