package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 读取工作区里的一个文件，带行号，可选行范围。 */
public final class ReadFileTool implements Tool {

    private static final int DEFAULT_MAX_LINES = 2_000;
    private static final int DEFAULT_MAX_LINE_CHARS = 2_000;

    /**
     * 一次读取的**总**字符上限。
     *
     * <h2>没有它的时候，这个工具其实没有上界</h2>
     * {@code 2000 行 × 每行 2000 字符} 最坏是 <b>400 万字符</b>（约 100 万 token）——
     * 一个压缩过的 JS、或者一行的大的 JSON，就能撞到。而"读文件"这一类工具的产出
     * **不该再走那一轮的总量限制**（见 {@link Tool#selfBounded()}），
     * 那意味着它必须自己设上界：不然就是把一个实际上无限大的东西直接灌进上下文。
     *
     * <p>取值和 {@code run_command} 的输出上限一致。两个都是"一次调用的产出"这件事，
     * 用两个不同的数字只会让下一个读代码的人以为它们之间有什么区别。
     */
    private static final int MAX_OUTPUT_CHARS = 100_000;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取工作区内一个文件的内容，返回带行号的文本。"
                + "可选 offset/limit 指定行范围。";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path":   {"type": "string",  "description": "相对于工作区根目录的文件路径"},
                    "offset": {"type": "integer", "description": "起始行号，从 1 开始；省略表示从头"},
                    "limit":  {"type": "integer", "description": "最多读多少行；省略表示 2000"}
                  },
                  "required": ["path"]
                }
                """;
    }

    /** 只读：可以和同一批里的其它只读调用并发跑，见 {@link Tool#concurrencySafe()}。 */
    @Override
    public boolean concurrencySafe() {
        return true;
    }

    /**
     * 产出自带分段：{@code offset} / {@code limit} 就是"再取剩下那部分"的入口。
     *
     * <p>所以它**不参与**那一轮的总量限制：对它的落盘提示是一条**循环指令**
     *（被落盘的正是这条 read 的结果），理由与两家的做法见 {@link Tool#selfBounded()}。
     *
     * <p>代价是它必须真的有界 —— 见 {@link #MAX_OUTPUT_CHARS}。
     */
    @Override
    public boolean selfBounded() {
        return true;
    }

    @Override
    public ToolSurface surface() {
        return ToolSurface.of(ToolSurface.Shape.READ, "读取", "path");
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        String rawPath = arguments.path("path").asText("");
        Path file;
        try {
            file = WorkspacePathGuard.resolve(context.worktree(), rawPath);
        } catch (WorkspacePathGuard.PathEscapeException e) {
            return ToolOutcome.failed(e.getMessage());
        }

        if (!Files.exists(file)) {
            return ToolOutcome.failed("文件不存在: " + rawPath + "。用 glob 或 grep 确认路径。");
        }
        if (Files.isDirectory(file)) {
            return ToolOutcome.failed(rawPath + " 是一个目录，不是文件。用 glob 列出里面的内容。");
        }

        int from = Math.max(1, arguments.path("offset").asInt(1));
        int limit = Math.max(1, arguments.path("limit").asInt(DEFAULT_MAX_LINES));


        // 【流式读】不先 readAllLines 物化整个文件 —— offset/limit 存在的意义就是读大文件，
        // 而 package-lock.json 这类几万到几十万行的文件全量读入会产生几十万个 String 然后丢弃。
        // 读到该停的地方就 break。
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        int lineNo = 0;
        boolean sawAnyLine = false;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo < from) {
                    continue;                       // 前面几行直接跳过，不物化
                }
                if (lineNo - from >= limit) {
                    truncated = true;               // 后面还有内容，但行数已经读够了
                    break;
                }
                if (out.length() >= MAX_OUTPUT_CHARS) {
                    // 行数还没到，**总量**先到顶了。一个压缩过的 JS、一行大 JSON 就能走到这儿
                    truncated = true;
                    break;
                }
                sawAnyLine = true;
                appendNumbered(out, lineNo, line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + rawPath, e);
        }

        if (!sawAnyLine) {
            // **空文件是一次成功的读取，不是失败。** 报错的话后果不只是多一句废话：
            // `write_file` 是"只在不存在时工作"、`edit_file` 要求 `old_string` 非空且唯一，
            // 再加上读不了 —— **一个空文件就再也填不上了**。
            //
            // 现在空文件读得动（这是"读过才许覆盖"的前提），写也就有了路
            if (isEmpty(file)) {
                reads(context, file, true);
                return ToolOutcome.ok(rawPath + " 现在**是空的**（0 行）。要往里写内容就用 write_file。");
            }
            return ToolOutcome.failed("起始行 " + from + " 超出了文件长度（这个文件一共 "
                    + lineNo + " 行）。");
        }
        // 是整份吗：从第一行开始读的，**而且没被任何一种上限截住**。
        // 只读了中间一段、或者后面还有没读到的，都不算整份 —— 拿那种印象去整份覆盖
        // 会把没看到的部分静默抹掉（见 WriteFileTool）
        reads(context, file, !truncated && from == 1);
        if (truncated) {
            // 说清**接着从哪一行读**：模型手里就有 offset 这个参数，它要的正是那个数
            //（只说"后面还有"的话，它得自己数收到了多少行，数错一次就白读一轮）。
            // （两种断法算出来是同一个式子：行数到顶时 lineNo 正好等于 from+limit，
            //  总量到顶时它是"没能追加进去的那一行"）
            int shown = lineNo - from;
            out.append("...（从第 ").append(from).append(" 行起显示了 ").append(shown)
                    .append(" 行，后面还有。接着读就用 offset=").append(from + shown).append("）\n");
        }

        return new ToolOutcome(true, out.toString(), truncated, null, 0,
                ToolOutcome.Failure.NONE, false);
    }

    /**
     * 记一笔：这个文件读过了，**读到的是现在这个版本**。
     *
     * <p>**只有真的读到了才记**，失败的那几条路径都不记 —— 见 {@link WriteFileTool}：
     * 覆盖那条规则靠的就是这本账，记错了等于它形同虚设。
     *
     * @param whole 拿到的是不是**整份**。不是整份的话，整份覆盖那条规则要拦住它：
     *              没看到的那部分会被静默抹掉
     */
    private static void reads(ToolContext context, Path file, boolean whole) {
        context.reads().mark(file, whole);
    }

    /** 读不到大小就当它不是空的 —— 那就走原来那句"起始行超出长度"的报错，不在这儿编一个结论。 */
    private static boolean isEmpty(Path file) {
        try {
            return Files.size(file) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 手写补位：{@code String.format} 每次都要新建 Formatter 并解析格式串，是朴素拼接的几十倍。 */
    private static void appendNumbered(StringBuilder out, int lineNo, String line) {
        String shown = line.length() <= DEFAULT_MAX_LINE_CHARS
                ? line
                : line.substring(0, DEFAULT_MAX_LINE_CHARS) + "  ...（本行已截断）";
        String number = Integer.toString(lineNo);
        out.repeat(" ", Math.max(0, 5 - number.length()));
        out.append(number).append("| ").append(shown).append('\n');
    }
}
