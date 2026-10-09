package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 精确局部替换。**永远不做整文件覆盖。**
 *
 * <h2>为什么这条很重要</h2>
 * 整文件覆盖有两个致命问题：一是 diff 会变成"整个文件都变了"，diff 卡片直接失去意义、
 * 审计也失去价值；二是**它会静默地抹掉别人在这期间对这个文件的改动**。
 *
 * <p>局部替换天然规避了第二条：如果 {@code old_string} 在工作区里已经找不到了，
 * 说明文件被改过（很可能是对方会话合并进来的），工具会失败并让模型重新读文件 ——
 * 这正是"两条会话分叉后如何自愈"的机制。
 *
 * <h2>失败信息是故意写长的</h2>
 * 模型只能通过这段文本来决定下一步。所以"没找到"时要告诉它**该怎么办**
 * （重新读文件），而不是只丢一句 "not found"。
 */
public final class EditFileTool implements Tool {

    @Override
    public String name() {
        return "edit_file";
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

        // **改之前必须观测过。** 它挡的是一件很实的事：没有"读到过的那一份"做基准，
        // 就没有任何东西能说明模型改的是**它以为的那份内容**。
        //
        // 两个参考实现在这一处是同一条规矩：Claude Code 的 Edit 直接报
        // "File has not been read yet"；deepseek-harness 的改文件那道检查在没观测过时
        // 同样拒绝。它们要的都是"有个版本可以做基准"。
        //
        // **不要求"整份"**（那是 write_file 那条规则的要求）：edit 改的是它**逐字引用的
        // 那一段**，读到的是不是整份与它无关。要求整份反而会把"读了 100-200 行，
        // 改其中一行"这种完全正当的用法挡掉。
        Optional<ReadLedger.Observation> observed = context.reads().observationOf(file);
        if (observed.isEmpty()) {
            return ToolOutcome.failed("还没读过 " + rawPath + "，不能改它。"
                    + "先用 read_file 看一眼它现在是什么样 —— 改之前得知道你要改的是哪一份内容。");
        }
        // 读完之后被别人改过：old_string 可能还匹配得上，但那是**另一份内容**里的位置。
        // 这时候改下去，改的和模型以为的不是同一处
        if (ReadLedger.changedSince(file, observed.get())) {
            return ToolOutcome.failed(rawPath + " 在你读过之后被改过了（另一个 agent，或者人自己动的）。"
                    + "先重新读一遍，确认当前内容再改。");
        }

        String content = read(file, rawPath);

        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            // 这条分支就是"对方会话改了同一个文件"之后的自愈入口
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

        // 写完刷新账上的版本：现在磁盘上是 `updated`，而这个工具**手里就有它的全文** ——
        // 所以它是"整份"。不刷新的话，紧接着的第二次 edit 会拿旧版本去比对，然后被自己刚写的改动拦住
        context.reads().mark(file, true);

        int line = lineOf(content, oldString);
        return ToolOutcome.mutated("已修改 " + rawPath + "（第 " + line + " 行附近，替换 "
                + oldString.length() + " 字符为 " + newString.length() + " 字符）");
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
