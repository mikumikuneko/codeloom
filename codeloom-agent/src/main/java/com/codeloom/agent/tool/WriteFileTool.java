package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.nio.file.Path;

/**
 * 新建文件，或者**覆盖一个这一轮对话里读过的文件**。
 *
 * <h2>为什么不是"只在不存在时工作"</h2>
 * 允许覆盖就等于把 {@code edit_file} 的所有保护（唯一匹配、改动可见、不误伤他人改动）
 * 绕过去，模型会倾向于图省事全用覆盖 —— 所以从前只在文件不存在时工作。
 *
 * <p><b>但那条规矩有一个没算到的代价：空文件谁也写不进去。</b>
 * {@code edit_file} 要求 {@code old_string} 非空且唯一，而空文件里没有东西可匹配 ——
 * 于是**一个空文件就再也填不上了**。
 *
 * <h2>现在这条规则是"读过没有"，不是"存不存在"</h2>
 * 和 Claude Code 一致（它的 {@code Write} 就是"创建或覆盖"，门槛是"在当前对话里
 * 读过这个文件才许覆盖"）。这条规则的用意是**别盲改你没看过的东西** ——
 * 它比"别改已经存在的东西"更贴题：你读过了，你就知道自己在覆盖什么。
 *
 * <p>账记在 {@link ReadLedger} 里，只有 {@code read_file} **真的读到**才记。
 */
public final class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
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
        // 和 edit_file 共用 EDIT 形状（都要一张 diff 卡片），靠这个词把人话分开
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

        // 先记下它原来在不在：下面那句回话要说清这次**是新建还是覆盖**
        boolean existed = Files.exists(file);

        // 三道检查，**贴着写入放** —— 中间不插别的事。理由见 ReadLedger 的类注释：
        // 检查和写入之间那道缝关不死（另一个人的 agent 可能正好在这几微秒里写同一个文件），
        // 能做的是把它压到最小，而不是假装它不存在
        if (existed) {
            Optional<ReadLedger.Observation> observed = context.reads().observationOf(file);
            if (observed.isEmpty()) {
                // 说"这条对话"而不是"这一轮"：账是**跨轮**的（上一次交互里读过的算数，
                // 见 seedReadLedger），说成"这一轮"会让模型以为"刚读过的东西为什么还不算数"
                return ToolOutcome.failed(rawPath + " 已经存在，而你还没读过它。"
                        + "先用 read_file 看一眼它现在是什么，再决定是覆盖它（write_file）"
                        + "还是只改几行（edit_file）。");
            }
            ReadLedger.Observation observation = observed.get();
            // 只读了一部分就整份覆盖 = 把**没看到的那部分静默抹掉**
            if (!observation.whole()) {
                return ToolOutcome.failed(rawPath + " 你只读了它的一部分，而 write_file 是整份覆盖 ——"
                        + "没读到的那部分会被抹掉。要么从头整份读一遍，要么用 edit_file 只改那一处。");
            }
            // 读完之后被改过：按你看到的那一份覆盖，等于**吃掉别人的改动**，而且不会报错
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

        // **写完立刻记一笔。** 现在磁盘上是什么，我们比谁都清楚 ——
        // 不记的话，模型新建完一个文件紧接着想改它，会被上面那条规则拦住，
        // 而那句"你还没读过它"在它听来毫无道理（它刚亲手写的那份）
        context.reads().mark(file, true);

        long lines = content.lines().count();
        // **新建和覆盖是两个事实，说成两句话。** 覆盖时旧内容已经没了，
        // 而模型得知道自己抹掉了什么（它读过，但说一句"覆盖"能提醒它这件事真的发生了）
        return ToolOutcome.mutated((existed ? "已覆盖 " : "已新建 ")
                + WorkspacePathGuard.relativize(context.worktree(), file)
                + "（" + lines + " 行）");
    }
}
