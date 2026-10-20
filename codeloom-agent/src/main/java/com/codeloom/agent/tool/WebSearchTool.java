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
 * 在互联网上搜索网页，返回来源列表：标题、链接、短摘要、日期。
 *
 * <p>不返回正文（用 {@link WebFetchTool} 打开），也不返回服务商的长摘要；长摘要会让模型
 * 跳过原文直接作答。搜索回答的是“去哪儿看”。
 *
 * <p>最多 {@value #MAX_QUERIES} 条查询，结果按名次轮流合并、按 URL 去重，总数封顶
 * {@value #MAX_SOURCES} 条；条数由工具控制，不由模型决定。
 */
public final class WebSearchTool implements Tool {

    private static final int MAX_QUERIES = 4;

    /** 每条查询向服务商请求的条数；合并后还会再封顶。 */
    private static final int PER_QUERY_COUNT = 5;

    /** 合并去重之后最多交给模型几条来源。 */
    private static final int MAX_SOURCES = 8;

    /** 每条来源的短摘要上限。 */
    private static final int MAX_SNIPPET_CHARS = 200;

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
     * 界面所需的结构化结果。不能从给模型的文本反解，那份文本有损；模型看不到这份 JSON。
     *
     * @param merged 合并后的来源
     * @return JSON 文本
     */
    private static String presentation(Merged merged) {
        ObjectNode root = ToolJson.MAPPER.createObjectNode();
        root.put("kind", "web_search");
        // 不传截断标记：界面折叠展示即可，提示写给模型。
        ArrayNode sources = root.putArray("sources");
        for (WebAccess.Hit hit : merged.hits()) {
            ObjectNode source = sources.addObject();
            source.put("url", text(hit.url(), ""));
            // 与给模型的那份使用同一长度上限，避免事件流膨胀。
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
     * 取出查询：去空白、丢弃空串、按原顺序去重。相同的查询重复发送只会增加费用。
     *
     * @param arguments 调用参数
     * @return 去重后的查询
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
     * 合并多组结果：按名次轮流取、按 URL 去重、总数封顶。轮流取让每条查询的头名先出现。
     *
     * @param perQuery 每条查询的结果
     * @return 合并结果
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
        // 常驻指令：要求引用 URL，避免无法核对的来源。
        out.append("\n回答里用到上面哪条来源，就把那条 URL 作为 markdown 链接写出来。");
        return out.toString();
    }

    /** 站点与发布时间，有哪个写哪个，供模型判断来源与时效。 */
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
     * 压成一行并截到上限；换行会让界面折叠后只剩第一行。
     *
     * @param value    原文
     * @param limit    字符上限
     * @param fallback 空值时的替代文本
     * @return 单行文本
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
