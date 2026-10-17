package com.codeloom.agent.tool;

import com.codeloom.agent.web.WebAccess;
import com.codeloom.agent.web.WebAccessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在互联网上搜网页，把命中的**来源列表**交给模型：标题、链接、短摘要、日期。
 *
 * <h2>它刻意不带两样东西</h2>
 * <ul>
 *   <li><b>正文</b>：一条正文就是一个网页。要正文就用 {@link WebFetchTool} 打开那一条。</li>
 *   <li><b>服务商的长摘要</b>（{@code summary}）：文档说"通常 600～800 字符"，实测
 *       一次十条里出现过 3,628 和 5,060 字符的，整条结果 13,882 字符。它长到能直接
 *       编答案时，模型就没有理由再去读原文 —— 于是答案里全是无法核对的说法。
 *       所以这里只给短摘要（{@code snippet}，100～200 字符）：**搜到的是"去哪儿看"，
 *       不是"看到了什么"**。</li>
 * </ul>
 *
 * <h2>一次可以问几条</h2>
 * 查询是数组（最多 {@value #MAX_QUERIES} 条），结果按名次轮流合并、按 URL 去重，
 * 总数封顶 {@value #MAX_SOURCES} 条。模型想换个说法再搜时，一次给出来比来回两轮便宜；
 * 而条数上限由**工具**定，不由模型定 —— 它多要只会把上下文撑大。
 */
public final class WebSearchTool implements Tool {

    private static final int MAX_QUERIES = 4;

    /** 每条查询向服务商要几条。合并之后还会再封顶，见 {@link #MAX_SOURCES}。 */
    private static final int PER_QUERY_COUNT = 5;

    /** 合并去重之后最多交给模型几条来源。 */
    private static final int MAX_SOURCES = 8;

    /** 每条来源的短摘要最多留这么长；它本来就 100～200 字符，只防个别超长的。 */
    private static final int MAX_SNIPPET_CHARS = 200;

    /** 只用来拼界面那份结构化结果；配置一次之后是线程安全的。 */

    @Override
    public String name() {
        return ToolNames.WEB_SEARCH;
    }

    @Override
    public String description() {
        return "在互联网上搜网页，返回若干条来源（标题、链接、短摘要、日期）。"
                + "可以一次给 1 到 " + MAX_QUERIES + " 条查询，结果会合并去重。"
                + "短摘要只够判断值不值得看；要引用正文里的事实，先用 web_fetch 打开那一条。"
                + "site:、-site:、\"精确短语\"、lang:zh,en、after:2025、before:2026 这些操作符"
                + "写在各自的查询字符串里（如 \"DeepSeek after:2026-10-01\"），没有单独的过滤参数。";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "queries": {
                      "type": "array",
                      "items": {"type": "string",
                                "description": "一条搜索词。日期、站点、语言这些过滤也写在这里（如 DeepSeek after:2026-10-01），没有单独的过滤参数"}
                    }
                  },
                  "required": ["queries"]
                }
                """;
    }

    @Override
    public ToolSurface surface() {
        return ToolSurface.of(ToolSurface.Shape.SEARCH, "搜索", "queries");
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        List<String> queries = queries(arguments);
        if (queries.isEmpty()) {
            return ToolOutcome.failed("queries 要至少给一条非空的查询");
        }
        if (queries.size() > MAX_QUERIES) {
            return ToolOutcome.failed("一次最多 " + MAX_QUERIES + " 条查询，收到 " + queries.size() + " 条");
        }

        List<List<WebAccess.Hit>> perQuery = new ArrayList<>(queries.size());
        for (String query : queries) {
            try {
                perQuery.add(context.web().search(query, PER_QUERY_COUNT));
            } catch (WebAccessException e) {
                return ToolOutcome.failed(e.getMessage());
            }
        }

        Merged merged = merge(perQuery);
        if (merged.hits().isEmpty()) {
            return ToolOutcome.ok("没有搜到结果。换个说法，或者去掉限定条件再试。");
        }
        return ToolOutcome.ok(format(merged)).withPresentation(presentation(merged));
    }

    /**
     * 界面要的那份结构化结果。
     *
     * <p>为什么不从 {@link #format} 的文本里反解：那份文本是有损的（标题、域名、摘要
     * 压进普通的行里），反解不出来就该是"猜" —— 而猜错的表现是卡片上显示别的东西。
     * 这份 JSON 是同一份事实的忠实形状，模型看不到它。
     */
    private static String presentation(Merged merged) {
        ObjectNode root = ToolJson.MAPPER.createObjectNode();
        root.put("kind", "web_search");
        // 不写 truncated：工具砍掉多少条是写给模型的（"想看更多就缩小查询范围"），
        // 界面上折叠成"展开其余 N 条"已经够了
        ArrayNode sources = root.putArray("sources");
        for (WebAccess.Hit hit : merged.hits()) {
            ObjectNode source = sources.addObject();
            source.put("url", text(hit.url(), ""));
            // 和给模型的那份**同一个长度**：这两份讲的是同一件事，
            // 而原文整段存进事件既撑大事件流，卡片上也放不下
            putIfPresent(source, "title", brief(hit.title(), MAX_SNIPPET_CHARS, null));
            putIfPresent(source, "snippet", brief(hit.snippet(), MAX_SNIPPET_CHARS, null));
            putIfPresent(source, "siteName", hit.siteName());
            putIfPresent(source, "date", hit.date());
        }
        return root.toString();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value.strip());
        }
    }

    // ------------------------------------------------------------------

    /**
     * 取出查询：去空白、丢掉空串、**按原顺序去重**。
     *
     * <p>去重放在调用之前：一模一样的查询发两次，除了多花一次钱不会有新结果。
     */
    private static List<String> queries(JsonNode arguments) {
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode node : arguments.path("queries")) {
            if (node.isTextual() && !node.asText().isBlank()) {
                unique.add(node.asText().strip());
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 合并多组结果：**按名次轮流取**（每组的第一条、然后每组的第二条……），
     * 按 URL 去重，总数封顶。
     *
     * <p>轮流而不是"一组接一组"：每组的第一条都是各自查询里最相关的，
     * 先取这一圈，模型看到的头几条才是每条查询各自的头名。
     */
    private static Merged merge(List<List<WebAccess.Hit>> perQuery) {
        List<WebAccess.Hit> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean truncated = false;
        int ranks = perQuery.stream().mapToInt(List::size).max().orElse(0);

        outer:
        for (int rank = 0; rank < ranks; rank++) {
            for (List<WebAccess.Hit> hits : perQuery) {
                if (rank >= hits.size()) {
                    continue;
                }
                WebAccess.Hit hit = hits.get(rank);
                if (hit.url() == null || !seen.add(hit.url())) {
                    continue;
                }
                if (merged.size() == MAX_SOURCES) {
                    truncated = true;
                    break outer;
                }
                merged.add(hit);
            }
        }
        return new Merged(merged, truncated);
    }

    private static String format(Merged merged) {
        StringBuilder out = new StringBuilder(WebFetchTool.UNTRUSTED_NOTICE).append('\n');
        for (int i = 0; i < merged.hits().size(); i++) {
            WebAccess.Hit hit = merged.hits().get(i);
            out.append('\n').append(i + 1).append(". ")
                    .append(brief(hit.title(), MAX_SNIPPET_CHARS, "（无标题）")).append('\n');
            out.append("   ").append(text(hit.url(), "")).append('\n');
            String source = source(hit);
            if (!source.isEmpty()) {
                out.append("   ").append(source).append('\n');
            }
            String snippet = brief(hit.snippet(), MAX_SNIPPET_CHARS, "");
            if (!snippet.isEmpty()) {
                out.append("   ").append(snippet).append('\n');
            }
        }
        if (merged.truncated()) {
            out.append("\n（只列了前 ").append(MAX_SOURCES)
                    .append(" 条来源；想看更多就缩小查询范围）\n");
        }
        // 常驻指令：没有它，答案里就会出现"据某媒体报道"这种无从核对的说法
        out.append("\n回答里用到上面哪条来源，就把那条 URL 作为 markdown 链接写出来。");
        return out.toString();
    }

    /** 站点和发布时间，有哪个写哪个 —— 模型靠它判断来源与时效。 */
    private static String source(WebAccess.Hit hit) {
        String site = text(hit.siteName(), "");
        String date = text(hit.date(), "");
        if (site.isEmpty()) {
            return date;
        }
        return date.isEmpty() ? site : site + " · " + date;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /**
     * 压成一行、截到上限。
     *
     * <p>换行必须压掉：一条来源自带换行时能占几十行，界面折叠之后只剩第一行 ——
     * 人看不见它到底读了什么。
     */
    private static String brief(String value, int limit, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String flat = value.strip().replaceAll("\\s+", " ");
        if (flat.length() <= limit) {
            return flat;
        }
        int end = limit;
        if (Character.isHighSurrogate(flat.charAt(end - 1))) {
            end--;
        }
        return flat.substring(0, end) + "…";
    }

    private record Merged(List<WebAccess.Hit> hits, boolean truncated) {
    }
}
