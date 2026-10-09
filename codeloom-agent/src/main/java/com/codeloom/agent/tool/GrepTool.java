package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 按正则搜索工作区里的文件内容。
 *
 * <h2>这就是我们不做 RAG 的原因</h2>
 * 代码是**精确匹配**的、不是语义相似的。模型要找 {@code OrderService} 的定义，
 * grep 给的是确定的答案；embedding 给的是"看起来相关"的一堆东西。
 * 而且 grep 不需要建索引、不会过期、结果可复现。
 *
 * <p>遍历由 {@link WorkspaceFiles} 负责（含噪声目录剪枝），本类只管匹配。
 */
public final class GrepTool implements Tool {

    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final int MAX_LINE_CHARS = 500;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在工作区内按正则搜索文件内容，返回 文件:行号:内容。"
                + "支持 Java 正则。";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "pattern":     {"type": "string",  "description": "Java 正则表达式"},
                    "path":        {"type": "string",  "description": "搜索范围，相对工作区，默认整个工作区"},
                    "include":     {"type": "string",  "description": "只搜匹配这个 glob 的文件名，如 *.java"},
                    "max_results": {"type": "integer", "description": "最多返回多少条，默认 200"}
                  },
                  "required": ["pattern"]
                }
                """;
    }

    /** 只读：可以和同一批里的其它只读调用并发跑，见 {@link Tool#concurrencySafe()}。 */
    @Override
    public boolean concurrencySafe() {
        return true;
    }

    @Override
    public ToolSurface surface() {
        // 主语取【被搜的那个模式】而不是搜在哪儿：人看这一行，想看的是"在找什么"。
        // 搜的范围是这次调用的状语，不是主语 —— 而"在哪"由结果里的路径回答
        return ToolSurface.of(ToolSurface.Shape.SEARCH, "搜索", "pattern");
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        String rawPattern = arguments.path("pattern").asText("");
        if (rawPattern.isEmpty()) {
            return ToolOutcome.failed("pattern 不能为空");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(rawPattern);
        } catch (PatternSyntaxException e) {
            return ToolOutcome.failed("正则表达式不合法: " + e.getDescription());
        }

        Path root;
        try {
            root = WorkspacePathGuard.resolve(context.worktree(),
                    arguments.path("path").asText("."));
        } catch (WorkspacePathGuard.PathEscapeException e) {
            return ToolOutcome.failed(e.getMessage());
        }
        if (!Files.isDirectory(root)) {
            return ToolOutcome.failed("搜索范围不是目录: " + arguments.path("path").asText("."));
        }

        Path worktree = context.worktree();
        PathMatcher includeMatcher = compileInclude(arguments.path("include").asText(""));
        int maxResults = arguments.path("max_results").asInt(DEFAULT_MAX_RESULTS);

        List<String> hits = new ArrayList<>();
        boolean[] reachedLimit = {false};

        try {
            // 谓词返回 false = 够了，遍历立即终止 —— 不用先把整棵树收成 List
            WorkspaceFiles.walk(root, file -> {
                if (!matchesInclude(includeMatcher, file)) {
                    return true;
                }
                if (scan(worktree, file, pattern, hits, maxResults)) {
                    reachedLimit[0] = true;
                    return false;
                }
                return true;
            });
        } catch (IOException e) {
            throw new UncheckedIOException("搜索失败", e);
        }

        if (hits.isEmpty()) {
            return ToolOutcome.ok("没有匹配到 " + rawPattern + " 的内容。");
        }
        String body = String.join("\n", hits)
                + (reachedLimit[0] ? "\n...（结果超过 " + maxResults + " 条，已截断，请缩小搜索范围）" : "");
        return new ToolOutcome(true, body, reachedLimit[0], null, 0,
                ToolOutcome.Failure.NONE, false);
    }

    /** @return true 表示已经达到上限、该停了 */
    private static boolean scan(Path worktree, Path file, Pattern pattern,
                                List<String> hits, int maxResults) {
        String relative = WorkspacePathGuard.relativize(worktree, file);
        // **逐行流式读**，不先把整个文件读成行的列表：一次搜索要为遍历到的每个文件
        // 做这件事，而大多数文件整份都不匹配 —— 那就等于把它们全部物化一遍再丢掉。
        // 达到上限时也要能立刻停下，而 readAllLines 已经把整份读完了
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!pattern.matcher(line).find()) {
                    continue;
                }
                hits.add(relative + ":" + lineNumber + ": " + abbreviate(line));
                if (hits.size() >= maxResults) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;   // 读不了就跳过（二进制或编码问题），不该让整个搜索失败
        }
        return false;
    }

    private static String abbreviate(String line) {
        String stripped = line.strip();
        return stripped.length() <= MAX_LINE_CHARS
                ? stripped
                : stripped.substring(0, MAX_LINE_CHARS) + "…";
    }

    private static PathMatcher compileInclude(String include) {
        return include == null || include.isBlank()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + include);
    }

    private static boolean matchesInclude(PathMatcher matcher, Path file) {
        return matcher == null || matcher.matches(file.getFileName());
    }
}
