package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.PatternSyntaxException;
import java.util.TreeSet;

/**
 * 按 glob 列出工作区里的文件路径，供模型确认目录结构和文件是否存在。
 *
 * <h2>结果为什么用 {@link TreeSet} 而不是"收集完再排序"</h2>
 * 物化整棵树的路径再全量排序、只为返回前 N 条，既费内存，排序键也白算
 *（{@code Comparator.comparing(Path::toString)} **每次比较都重算一次**）。
 *
 * <p>换成有界 TreeSet：超过上限就丢掉最大的那个，始终保持"字典序最小的 k 个"，
 * 复杂度 O(n log k)，不需要全量 List，结果也仍是确定有序的。
 */
public final class GlobTool implements Tool {

    private static final int DEFAULT_MAX_RESULTS = 500;

    @Override
    public String name() {
        return "glob";
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

    /** 只读：可以和同一批里的其它只读调用并发跑，见 {@link Tool#concurrencySafe()}。 */
    @Override
    public boolean concurrencySafe() {
        return true;
    }

    @Override
    public ToolSurface surface() {
        // 和 grep 共用 SEARCH 形状：要的是同一张卡片，差别只在这个词上
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

        // 有界 TreeSet：始终保留字典序最小的 maxResults 个
        TreeSet<String> matches = new TreeSet<>();
        boolean[] exceeded = {false};

        try {
            WorkspaceFiles.walk(root, file -> {
                // 路径一律换成正斜杠再匹配：模式里写的是 `/`，而 Windows 上
                // `Path.toString()` 给的是 `\` —— 不换的话 `src/**/*.java` 一条都匹配不上
                String relative = worktree.relativize(file).toString().replace('\\', '/');
                // 同时按**文件名**和**相对路径**匹配：模型既可能写 *.java 也可能写
                // src/**/*.java。宽容一层是有意的 —— 少匹配一次它要再花一步去确认，
                // 而多匹配一次它从打印出来的完整路径就能看出来
                if (!matcher.matcher(file.getFileName().toString()).matches()
                        && !matcher.matcher(relative).matches()) {
                    return true;
                }
                // 显示走和 grep 同一个归一函数 —— 同一个文件在两个工具里必须长得一样，
                // 否则模型对着两个名字会以为它们是两个文件
                String shown = WorkspacePathGuard.relativize(worktree, file);
                if (matches.add(shown) && matches.size() > maxResults) {
                    matches.pollLast();
                    exceeded[0] = true;
                }
                return true;   // glob 要的是"字典序最小的 N 个"，不能提前终止
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
     * 把 glob 翻成正则，**按大家对 {@code **} 的共识**：{@code **&#47;} 表示
     * **零个或多个目录**。
     *
     * <h2>为什么不能用 JDK 自带的那个</h2>
     * JDK 的 {@code FileSystems.getDefault().getPathMatcher("glob:…")} 对 {@code **}
     * 的处理和所有人预期的都不一样 —— 实测它就是"**至少一层**目录"：
     *
     * <pre>
     *   **&#47;*.java      对 helloworld.java  **不命中**      对 src/A.java 命中
     *   **&#47;*           对 helloworld.java  **不命中**      对 src/A.java 命中
     *   src/**&#47;*.java  对 src/A.java       **不命中**
     * </pre>
     *
     * <p>后果不是"少一条结果"：{@code **&#47;*} 匹配不到刚建好的
     * {@code helloworld.java}，模型会**以为自己刚建的文件不见了**，多花几步去确认；
     * 而 {@code src/**&#47;*.java} 这条 —— **我们自己写在工具 description 里的例子** ——
     * 连 {@code src/A.java} 都匹配不到。
     *
     * <p>bash、gitignore、ripgrep 全都是"零层或更多"，模型也是照那个写的。所以这里自己翻，
     * 翻法就一条要紧的：{@code **&#47;} → {@code (?:.*&#47;)?}。
     */
    static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                    i++;                                   // 连那个 `/` 一起吃掉
                    regex.append("(?:.*/)?");              // ← 全文唯一要紧的一行
                } else {
                    regex.append(".*");                    // 光秃秃的 `**`：跨目录的一串
                }
            } else if (c == '*') {
                regex.append("[^/]*");                     // 单个 `*` 不跨目录
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                // 其余一律当字面量。用 quote 而不是自己列转义字符 ——
                // 漏掉一个就是一个能让模式行为出人意料的口子
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
