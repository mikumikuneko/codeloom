package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.nio.file.Path;

/**
 * 新建文件，或覆盖一个这条对话里读过的文件。
 *
 * <p>覆盖门槛是“读过”而不是“不存在”：既避免盲改未看过的内容，也让空文件可以被填充
 * （{@code edit_file} 无法匹配空文件）。记录见 {@link ReadLedger}；读后被改过则拒绝并
 * 要求重读。见 {@code docs/decisions/architecture/2026-10-20-overwrite-checks-the-version-it-read.md}。
 */
public final class WriteFileTool implements Tool {

    @Override
    public String name() {
        return ToolNames.WRITE_FILE;
    }

    @Override
    public String description() {
        return "新建一个文件，或者覆盖一个你已经读过的文件（写整份内容，不追加、不合并）。"
                + "覆盖一个**没读过**的文件会被拒绝 —— 先 read_file 看一眼再写。"
                + "只想改几行的话用 edit_file，别整份重写。";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path":    {"type": "string", "description": "相对于工作区根目录的文件路径"},
                    "content": {"type": "string", "description": "文件的完整内容"}
                  },
                  "required": ["path", "content"]
                }
                """;
    }

    @Override
    public ToolSurface surface() {
        // 与 edit_file 共用 EDIT 形状，动作词区分新建与修改。
        return ToolSurface.of(ToolSurface.Shape.EDIT, "新建", "path");
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        String rawPath = arguments.path("path").asText("");
        String content = arguments.path("content").asText("");

        Path file;
        try {
            file = WorkspacePathGuard.resolve(context.worktree(), rawPath);
        } catch (WorkspacePathGuard.PathEscapeException e) {
            return ToolOutcome.failed(e.getMessage());
        }

        // 记录原来是否存在，用于回话中区分新建与覆盖。
        boolean existed = Files.exists(file);

        // 三道检查紧贴写入执行，尽量缩小检查与写入之间的 TOCTOU 窗口。
        if (existed) {
            Optional<ReadLedger.Observation> observed = context.reads().observationOf(file);
            if (observed.isEmpty()) {
                // 账跨轮有效；只说明状态，不重复工具描述里的指导。
                return ToolOutcome.failed("还没读过 " + rawPath + "，不能覆盖它。");
            }
            ReadLedger.Observation observation = observed.get();
            // 部分读取后整份覆盖会静默抹掉未看到的内容。
            if (!observation.whole()) {
                return ToolOutcome.failed(rawPath + " 你只读了它的一部分，而 write_file 是整份覆盖 ——"
                        + "没读到的那部分会被抹掉。要么从头整份读一遍，要么用 edit_file 只改那一处。");
            }
            // 读后被改动时覆盖会吃掉别人的修改。
            if (ReadLedger.changedSince(file, observation)) {
                return ToolOutcome.failed(rawPath + " 在你读过之后被改过了（另一个 agent，或者人自己动的）。"
                        + "先重新读一遍，确认你要覆盖的是哪一份内容。");
            }
        }

        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败: " + rawPath, e);
        }

        // 写完立即记录，否则紧接着的 edit 会被“还没读过”拦住。
        context.reads().mark(file, true);

        long lines = content.lines().count();
        // presentation 只带界面需要的“新建/覆盖”和行数；正文已在调用参数里。
        String presentation = "{\"kind\":\"write\",\"created\":" + !existed
                + ",\"lines\":" + lines + "}";
        return ToolOutcome.mutated((existed ? "已覆盖 " : "已新建 ")
                + WorkspacePathGuard.relativize(context.worktree(), file)
                + "（" + lines + " 行）")
                .withPresentation(presentation);
    }
}
