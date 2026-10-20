package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 精确局部替换，永远不做整文件覆盖。
 *
 * <p>整份覆盖会让 diff 失去意义，并可能静默抹掉同时发生的改动。局部替换要求
 * {@code old_string} 唯一匹配；匹配不到说明文件已变，失败信息要引导模型重新读取，
 * 这就是两条会话分叉后的自愈入口。
 */
public final class EditFileTool implements Tool {

    @Override
    public String name() {
        return ToolNames.EDIT_FILE;
    }

    @Override
    public String description() {
        return "把文件里的一段内容替换成新的内容。old_string 必须在文件中【唯一出现】，"
                + "否则会被拒绝 —— 请多带几行上下文让它唯一。不要用它整文件重写，"
                + "需要新建文件时用 write_file。";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path":       {"type": "string", "description": "相对于工作区根目录的文件路径"},
                    "old_string": {"type": "string", "description": "要被替换掉的原文，必须与文件内容逐字符一致"},
                    "new_string": {"type": "string", "description": "替换成的新内容"}
                  },
                  "required": ["path", "old_string", "new_string"]
                }
                """;
    }

    @Override
    public ToolSurface surface() {
        return ToolSurface.of(ToolSurface.Shape.EDIT, "修改", "path");
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        String rawPath = arguments.path("path").asText("");
        String oldString = arguments.path("old_string").asText("");
        String newString = arguments.path("new_string").asText("");

        if (oldString.isEmpty()) {
            return ToolOutcome.failed("old_string 不能为空。要新建文件请用 write_file。");
        }
        if (oldString.equals(newString)) {
            return ToolOutcome.failed("old_string 与 new_string 相同，这次调用不会产生任何改动。");
        }

        Path file;
        try {
            file = WorkspacePathGuard.resolve(context.worktree(), rawPath);
        } catch (WorkspacePathGuard.PathEscapeException e) {
            return ToolOutcome.failed(e.getMessage());
        }
        if (!Files.isRegularFile(file)) {
            return ToolOutcome.failed("文件不存在: " + rawPath + "。要新建请用 write_file。");
        }

        // 修改前必须观测过，否则没有基准证明改的是模型以为的内容。
        // 不要求整份：edit 只改动逐字引用的那一段，部分读取是正当用法。
        Optional<ReadLedger.Observation> observed = context.reads().observationOf(file);
        if (observed.isEmpty()) {
            // 只说明状态，不重复工具描述里的指导。
            return ToolOutcome.failed("还没读过 " + rawPath + "，不能改它。");
        }
        // 读后被改动时，old_string 可能仍匹配，但匹配的是另一份内容里的位置。
        if (ReadLedger.changedSince(file, observed.get())) {
            return ToolOutcome.failed(rawPath + " 在你读过之后被改过了（另一个 agent，或者人自己动的）。"
                    + "先重新读一遍，确认当前内容再改。");
        }

        String content = read(file, rawPath);

        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            // 这是“对方会话改了同一文件”后的自愈入口。
            return ToolOutcome.failed("在 " + rawPath + " 里没有找到 old_string 那段内容。"
                    + "文件可能已被其他会话修改，或者你的内容与文件不完全一致（注意空白字符）。"
                    + "请重新用 read_file 读取 " + rawPath + " 确认当前内容后再试。");
        }
        if (occurrences > 1) {
            return ToolOutcome.failed("old_string 在 " + rawPath + " 里出现了 " + occurrences
                    + " 次，无法确定要改哪一处。请在 old_string 里多包含几行上下文，使它唯一。");
        }

        String updated = content.replace(oldString, newString);
        write(file, updated, rawPath);

        // 写完刷新记录：工具手里就是全文，因此算整份；否则紧接着的 edit 会被旧版本拦住。
        context.reads().mark(file, true);

        int line = lineOf(content, oldString);
        // 界面需要结构化的起始行号，不能从给模型的自由文本反解。
        String presentation = "{\"kind\":\"diff\",\"startLine\":" + line + "}";
        return ToolOutcome.mutated("已修改 " + rawPath + "（第 " + line + " 行附近，替换 "
                + oldString.length() + " 字符为 " + newString.length() + " 字符）")
                .withPresentation(presentation);
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int from = 0;
        while ((from = content.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static int lineOf(String content, String needle) {
        int index = content.indexOf(needle);
        if (index < 0) {
            return 0;
        }
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String read(Path file, String rawPath) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + rawPath, e);
        }
    }

    private static void write(Path file, String content, String rawPath) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败: " + rawPath, e);
        }
    }
}
