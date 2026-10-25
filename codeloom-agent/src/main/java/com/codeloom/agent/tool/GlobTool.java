package com.codeloom.agent.tool;

import com.codeloom.domain.workspace.WorkspacePathGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.PatternSyntaxException;
import java.util.TreeSet;

/**
 * 按 glob 列出工作区里的文件路径，供模型确认目录结构与文件是否存在。
 *
 * <p>结果按字典序排列、最多返回指定条数，且只保留字典序最小的那些，保证输出确定。
 */
public final class GlobTool implements Tool {

    private static final int DEFAULT_MAX_RESULTS = 500;

    @Override
    public String name() {
        return ToolNames.GLOB;
    }

    @Override
    public String description() {
        return "按 glob 模式列出工作区内的文件路径。"
                + "用来确认目录结构和文件是否真实存在。";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "pattern":     {"type": "string",  "description": "glob 模式，如 **/*.java"},
                    "path":        {"type": "string",  "description": "搜索起点，相对工作区，默认整个工作区"},
                    "max_results": {"type": "integer", "description": "最多返回多少条，默认 500"}
                  },
                  "required": ["pattern"]
                }
                """;
    }

    /** 只读，可与同一批的只读调用并发；见 {@link Tool#concurrencySafe()}。 */
    @Override
    public boolean concurrencySafe() {
        return true;
    }

    @Override
    public ToolSurface surface() {
        // 与 grep 共用 SEARCH 形状，动作词区分查找与搜索。
        return ToolSurface.of(ToolSurface.Shape.SEARCH, "查找", "pattern");
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        String rawPattern = arguments.path("pattern").asText("");
        if (rawPattern.isBlank()) {
            return ToolOutcome.failed("pattern 不能为空");
        }

        Path root;
        try {
            root = WorkspacePathGuard.resolve(context.worktree(), arguments.path("path").asText("."));
        } catch (WorkspacePathGuard.PathEscapeException e) {
            return ToolOutcome.failed(e.getMessage());
        }
        if (!Files.isDirectory(root)) {
            return ToolOutcome.failed("搜索起点不是目录");
        }

        Pattern matcher;
        try {
            matcher = compile(rawPattern);
        } catch (PatternSyntaxException e) {
            return ToolOutcome.failed("glob 模式不合法: " + rawPattern);
        }

        int maxResults = arguments.path("max_results").asInt(DEFAULT_MAX_RESULTS);
        Path worktree = context.worktree();

        // 有界集合：始终保留字典序最小的 maxResults 个。
        TreeSet<String> matches = new TreeSet<>();
        boolean[] exceeded = {false};

        try {
            WorkspaceFiles.walk(root, file -> {
                // 统一用正斜杠匹配：Windows 的 Path.toString() 用反斜杠。
                String relative = worktree.relativize(file).toString().replace('\\', '/');
                // 同时按文件名和相对路径匹配，兼顾 *.java 与 src/**/*.java 两种写法。
                if (!matcher.matcher(file.getFileName().toString()).matches()
                        && !matcher.matcher(relative).matches()) {
                    return true;
                }
                // 显示口径与 grep 一致，避免同一文件出现两个名字。
                String shown = WorkspacePathGuard.relativize(worktree, file);
                if (matches.add(shown) && matches.size() > maxResults) {
                    matches.pollLast();
                    exceeded[0] = true;
                }
                return true;   // 需要完整遍历才能确定字典序最小的 N 个
            });
        } catch (IOException e) {
            throw new UncheckedIOException("遍历工作区失败", e);
        }

        if (matches.isEmpty()) {
            return ToolOutcome.ok("没有匹配 " + rawPattern + " 的文件。");
        }
        String body = String.join("\n", matches)
                + (exceeded[0] ? "\n...（超过 " + maxResults + " 条，已保留字典序靠前的部分）" : "");
        return new ToolOutcome(true, body, exceeded[0], null, 0, ToolOutcome.Failure.NONE, false);
    }

    /**
     * 把 glob 翻成正则。{@code **}{@code /} 表示零个或多个目录，与 bash、gitignore、ripgrep 一致；
     * JDK 的 glob 实现把它当作至少一层，会让 {@code **}{@code /}*.java 漏掉根目录文件，
     * 也会让 {@code src/**}{@code /}*.java 匹配不到 {@code src/A.java}，因此自行转换。
     *
     * @param glob glob 模式
     * @return 编译后的正则
     * @throws PatternSyntaxException 转换结果不是合法正则
     */
    static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                    i++;                                   // 一并消费后面的斜杠
                    regex.append("(?:.*/)?");              // 双星号加斜杠表示零层或更多目录
                } else {
                    regex.append(".*");                    // 单独的双星号匹配跨目录的任意串
                }
            } else if (c == '*') {
                regex.append("[^/]*");                     // 单个星号不跨目录
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                // 其余字符按字面量处理，使用 quote 避免遗漏转义。
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
