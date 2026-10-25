package com.codeloom.agent.tool;

import com.codeloom.domain.workspace.WorkspacePathGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读取工作区里的一个文件，返回带行号的文本，可选行范围。
 *
 * <p>产出有真实上界且可用 offset/limit 继续读取，因此声明为 {@link #selfBounded()}。
 * 空文件是一次成功的读取，否则空文件将无法被读取，也就永远无法被覆盖。
 */
public final class ReadFileTool implements Tool {

    private static final int DEFAULT_MAX_LINES = 2_000;
    private static final int DEFAULT_MAX_LINE_CHARS = 2_000;

    @Override
    public String name() {
        return ToolNames.READ_FILE;
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

    /** 只读，可与同一批的只读调用并发；见 {@link Tool#concurrencySafe()}。 */
    @Override
    public boolean concurrencySafe() {
        return true;
    }

    /**
     * offset/limit 是继续读取的入口，且单次输出受 {@link ToolLimits#MAX_OUTPUT_CHARS} 限制，
     * 因此不触发每轮总量超限时的落盘改写（落盘提示会对读文件形成循环）；它的占用仍计入该轮总量。
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


        // 流式读取，不物化整个文件；offset/limit 的意义就是读取大文件。
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
                    truncated = true;               // 行数已读够，后面还有内容
                    break;
                }
                if (out.length() >= ToolLimits.MAX_OUTPUT_CHARS) {
                    // 行数未到但总字符先到顶；压缩后的 JS 或大 JSON 可以触发。
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
            // 空文件是成功读取：否则 write_file 与 edit_file 都无法填充它。
            if (isEmpty(file)) {
                reads(context, file, true);
                return ToolOutcome.ok(rawPath + " 现在**是空的**（0 行）。要往里写内容就用 write_file。");
            }
            return ToolOutcome.failed("起始行 " + from + " 超出了文件长度（这个文件一共 "
                    + lineNo + " 行）。");
        }
        // 只有从第一行开始且未被任何上限截住才算整份；部分读取不能用于整份覆盖。
        reads(context, file, !truncated && from == 1);
        if (truncated) {
            // 明确下一次读取的 offset；只说“后面还有”会迫使模型自己数已读行数。
            int shown = lineNo - from;
            out.append("...（从第 ").append(from).append(" 行起显示了 ").append(shown)
                    .append(" 行，后面还有。接着读就用 offset=").append(from + shown).append("）\n");
        }

        return new ToolOutcome(true, out.toString(), truncated, null, 0,
                ToolOutcome.Failure.NONE, false);
    }

    /**
     * 记录一次成功读取。只有真正读到才记录，错误路径不记录。
     *
     * @param context 工具上下文
     * @param file    读到的文件
     * @param whole   是否读到整份；部分读取不允许整份覆盖
     */
    private static void reads(ToolContext context, Path file, boolean whole) {
        context.reads().mark(file, whole);
    }

    /** 读不到大小就当作非空，走“起始行超出长度”的报错。 */
    private static boolean isEmpty(Path file) {
        try {
            return Files.size(file) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 追加带行号的文本；手写补位以避免每次格式化的开销。 */
    private static void appendNumbered(StringBuilder out, int lineNo, String line) {
        String shown = line.length() <= DEFAULT_MAX_LINE_CHARS
                ? line
                : line.substring(0, DEFAULT_MAX_LINE_CHARS) + "  ...（本行已截断）";
        String number = Integer.toString(lineNo);
        out.repeat(" ", Math.max(0, 5 - number.length()));
        out.append(number).append("| ").append(shown).append('\n');
    }
}
