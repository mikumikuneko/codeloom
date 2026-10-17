package com.codeloom.agent.tool;

import com.codeloom.agent.web.WebAccess;
import com.codeloom.agent.web.WebAccessException;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.CommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个联网工具：怎么把结果写给模型、失败怎么回、边界在哪。
 *
 * <p>联网那一层用假的 {@link WebAccess} —— 这些用例验的是**工具**（格式、合并、上限、提示），
 * 不是那一家服务商。真连一次验的是另一件事，见 {@code DeepSeekWebAccessTest}。
 */
class WebToolsTest {

    @TempDir
    Path worktree;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebSearchTool search = new WebSearchTool();
    private final WebFetchTool fetch = new WebFetchTool();

    @Nested
    @DisplayName("web_search")
    class Search {

        @Test
        @DisplayName("每条来源给标题、链接、站点、日期和短摘要，最后要求引用 URL")
        void formatsSources() throws Exception {
            FakeWeb web = new FakeWeb();
            web.responses.add(List.of(
                    new WebAccess.Hit("DeepSeek V4 发布", "https://example.com/a",
                            "一句话短摘要", "Example News", "2026-06-01T00:00:00+00:00"),
                    new WebAccess.Hit("只有标题", "https://example.com/b", null, null, null)));

            ToolOutcome outcome = search.execute(context(web), args("""
                    {"queries":["DeepSeek"]}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.output())
                    .startsWith(WebFetchTool.UNTRUSTED_NOTICE)
                    .contains("1. DeepSeek V4 发布")
                    .contains("https://example.com/a")
                    .contains("Example News · 2026-06-01T00:00:00+00:00")
                    .contains("一句话短摘要")
                    .contains("2. 只有标题")
                    // 常驻指令：没有它，答案里就会出现无法核对的"据某媒体报道"
                    .contains("作为 markdown 链接写出来");
        }

        @Test
        @DisplayName("多条查询按名次轮流合并、按 URL 去重")
        void mergesQueriesRoundRobin() throws Exception {
            FakeWeb web = new FakeWeb();
            web.responses.add(List.of(hit("a1"), hit("a2")));
            web.responses.add(List.of(hit("b1"), hit("a1"), hit("b3")));
            web.responses.add(List.of(hit("a3")));

            ToolOutcome outcome = search.execute(context(web), args("""
                    {"queries":["一","二","三"]}"""));

            assertThat(web.queries).containsExactly("一", "二", "三");
            // 每条查询的头名先各取一条，再取第二圈；重复的 a1 只出现一次
            assertThat(urlsIn(outcome.output()))
                    .containsExactly("https://example.com/a1", "https://example.com/b1",
                            "https://example.com/a3", "https://example.com/a2",
                            "https://example.com/b3");
        }

        @Test
        @DisplayName("合并之后封顶 8 条 —— 条数由工具定，不由模型定")
        void capsTheMergedList() throws Exception {
            FakeWeb web = new FakeWeb();
            web.responses.add(List.of(hit("1"), hit("2"), hit("3"), hit("4"), hit("5")));
            web.responses.add(List.of(hit("6"), hit("7"), hit("8"), hit("9"), hit("10")));

            ToolOutcome outcome = search.execute(context(web), args("""
                    {"queries":["一","二"]}"""));

            assertThat(urlsIn(outcome.output())).hasSize(8);
            assertThat(outcome.output()).contains("只列了前 8 条来源");
        }

        @Test
        @DisplayName("空白查询被丢掉、重复查询只发一次")
        void dropsBlankAndDuplicateQueries() throws Exception {
            FakeWeb web = new FakeWeb();

            search.execute(context(web), args("""
                    {"queries":["  ","DeepSeek","DeepSeek","   "]}"""));

            assertThat(web.queries).containsExactly("DeepSeek");
        }

        @Test
        @DisplayName("超过 4 条查询当场拒绝，不打到网络上")
        void rejectsTooManyQueries() throws Exception {
            FakeWeb web = new FakeWeb();

            ToolOutcome outcome = search.execute(context(web), args("""
                    {"queries":["1","2","3","4","5"]}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("最多 4 条");
            assertThat(web.queries).isEmpty();
        }

        @Test
        @DisplayName("没有命中是一次正常的回答，不是失败")
        void emptyResultsAreAnAnswer() throws Exception {
            FakeWeb web = new FakeWeb();

            ToolOutcome outcome = search.execute(context(web), args("""
                    {"queries":["找不到的东西"]}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.output()).contains("没有搜到结果");
        }

        @Test
        @DisplayName("联网失败作为【失败结果】返回，消息原样交给模型")
        void failureIsAResult() throws Exception {
            FakeWeb web = new FakeWeb();
            web.failure = new WebAccessException("请求太频繁（这两个接口按账号限 5 QPS），稍后再试");

            ToolOutcome outcome = search.execute(context(web), args("""
                    {"queries":["x"]}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("5 QPS");
        }

        @Test
        @DisplayName("界面那份结构化结果：来源齐全，摘要和给模型的那份一样长")
        void publishesStructuredSourcesForTheUi() throws Exception {
            FakeWeb web = new FakeWeb();
            web.responses.add(List.of(new WebAccess.Hit("标题", "https://example.com/a",
                    "短".repeat(500), "站点", "2026-10-01")));

            ToolOutcome outcome = search.execute(context(web), args("""
                    {"queries":["x"]}"""));

            JsonNode presentation = mapper.readTree(outcome.presentation());
            assertThat(presentation.get("kind").asText()).isEqualTo("web_search");
            // 砍掉多少条是写给模型的，不随结构化结果下发给界面
            assertThat(presentation.has("truncated")).isFalse();
            JsonNode source = presentation.get("sources").get(0);
            assertThat(source.get("url").asText()).isEqualTo("https://example.com/a");
            assertThat(source.get("snippet").asText()).hasSize(201).endsWith("…");
        }

        @Test
        @DisplayName("多传的过滤参数被挡下来 —— 否则它以为设了条件，而搜索根本没看")
        void rejectsUnknownParameters() throws Exception {
            Optional<String> problem = ToolRegistry.withWeb()
                    .validateArguments("web_search", args("""
                            {"queries":["x"],"after":"2026-01-01"}"""));

            assertThat(problem).isPresent();
            assertThat(problem.get()).contains("`after`").contains("queries");
        }
    }

    @Nested
    @DisplayName("web_fetch")
    class Fetch {

        @Test
        @DisplayName("正文前先说清它来自互联网 —— 这句话必须在正文之前")
        void warnsBeforeContent() throws Exception {
            FakeWeb web = new FakeWeb();
            web.page = new WebAccess.Page("Example article", "# 标题\n\n正文");

            ToolOutcome outcome = fetch.execute(context(web), args("""
                    {"url":"https://example.com/a"}"""));

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.truncated()).isFalse();
            assertThat(outcome.output()).startsWith(WebFetchTool.UNTRUSTED_NOTICE)
                    .contains("Example article")
                    .contains("# 标题");
        }

        @Test
        @DisplayName("超长正文截断并说明取不回剩下的")
        void truncatesLongPages() throws Exception {
            FakeWeb web = new FakeWeb();
            web.page = new WebAccess.Page("长文", "字".repeat(100_001));

            ToolOutcome outcome = fetch.execute(context(web), args("""
                    {"url":"https://example.com/long"}"""));

            assertThat(outcome.truncated()).isTrue();
            assertThat(outcome.output()).contains("只保留了前 100000 个字符")
                    .contains("剩下的取不回来");
        }

        @Test
        @DisplayName("只接受 http/https —— 别的写法不是网址")
        void rejectsNonHttpUrls() throws Exception {
            FakeWeb web = new FakeWeb();

            ToolOutcome outcome = fetch.execute(context(web), args("""
                    {"url":"file:///etc/passwd"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(web.lastUrl).isNull();
        }

        @Test
        @DisplayName("界面那份只有标题和截断标记 —— 正文和那句提示词都不上界面")
        void publishesTitleOnlyForTheUi() throws Exception {
            FakeWeb web = new FakeWeb();
            web.page = new WebAccess.Page("网页标题", "正文");

            ToolOutcome outcome = fetch.execute(context(web), args("""
                    {"url":"https://example.com/a"}"""));

            JsonNode presentation = mapper.readTree(outcome.presentation());
            assertThat(presentation.get("kind").asText()).isEqualTo("web_fetch");
            assertThat(presentation.get("title").asText()).isEqualTo("网页标题");
            assertThat(presentation.get("truncated").asBoolean()).isFalse();
            assertThat(presentation.has("content")).isFalse();
            assertThat(outcome.presentation()).doesNotContain(WebFetchTool.UNTRUSTED_NOTICE);
        }

        @Test
        @DisplayName("抓取失败作为失败结果返回")
        void failureIsAResult() throws Exception {
            FakeWeb web = new FakeWeb();
            web.failure = new WebAccessException("没能取到可用的网页内容");

            ToolOutcome outcome = fetch.execute(context(web), args("""
                    {"url":"https://example.com/missing"}"""));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("没能取到");
        }
    }

    @Test
    @DisplayName("两个工具都声明了自己是什么：形状、动作词、主语")
    void declarations() {
        assertThat(search.name()).isEqualTo("web_search");
        assertThat(search.surface().shape()).isEqualTo(ToolSurface.Shape.SEARCH);
        assertThat(search.surface().subjectKey()).isEqualTo("queries");

        assertThat(fetch.name()).isEqualTo("web_fetch");
        // 网址**不是**工作区路径：用 READ 会让界面把它做成"点开这个文件"
        assertThat(fetch.surface().shape()).isEqualTo(ToolSurface.Shape.WEB);
        assertThat(fetch.surface().subjectIsPath()).isFalse();
        assertThat(fetch.surface().subjectKey()).isEqualTo("url");
    }

    // ------------------------------------------------------------------

    private ToolContext context(WebAccess web) {
        CommandExecutor executor = (ws, command, timeout, limit, cancellation) ->
                new CommandResult(0, "", false, 0);
        return new ToolContext(worktree, executor, CancellationToken.none(),
                commandLine -> Optional.empty(), new ReadLedger(), web);
    }

    private JsonNode args(String json) throws Exception {
        return mapper.readTree(json);
    }

    /** 从输出里按顺序取出所有来源的 URL。 */
    private static List<String> urlsIn(String output) {
        return output.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("https://"))
                .toList();
    }

    private static WebAccess.Hit hit(String slug) {
        return new WebAccess.Hit("标题 " + slug, "https://example.com/" + slug,
                "摘要 " + slug, "站点", "2026-10-01");
    }

    /** 假的联网层：按查询顺序给出预置结果，记下收到的查询。 */
    private static final class FakeWeb implements WebAccess {

        final List<List<Hit>> responses = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        Page page = new Page("", "");
        WebAccessException failure;
        String lastUrl;

        @Override
        public List<Hit> search(String query, int count) {
            queries.add(query);
            if (failure != null) {
                throw failure;
            }
            int index = queries.size() - 1;
            return index < responses.size() ? responses.get(index) : List.of();
        }

        @Override
        public Page fetch(String url) {
            lastUrl = url;
            if (failure != null) {
                throw failure;
            }
            return page;
        }
    }
}
