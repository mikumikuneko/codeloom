package com.codeloom.app.tool;

import com.codeloom.app.support.TestBrowser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具目录接口。
 *
 * <p>它守的是：界面渲染每条工具调用全靠这份数据，包括组件、动作词、主语在参数里的哪一项。
 * 形状一变，界面只会“显示得糙”而不会报错，所以测试把线上的字段名和取值拉住，和前端
 * {@code ToolView} 依赖的一致。另一半是它要登录：工具目录没有用户数据，但也不需要公开，
 * 这是安全配置“其余一律要登录”的默认结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
class ToolsApiTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private final List<String> createdUsernames = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String username : createdUsernames) {
            jdbc.update("DELETE FROM `user` WHERE username = ?", username);
        }
        createdUsernames.clear();
    }

    @Test
    @DisplayName("每个工具各带形状、词和主语键 —— 界面靠这三样渲染，不再认工具名")
    void everyToolDeclaresItsOwnSurface() throws Exception {
        TestBrowser browser = newAccount();

        HttpResponse<String> response = browser.get("/api/tools");
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

        Map<String, JsonNode> byName = StreamSupport.stream(
                        json.readTree(response.body()).spliterator(), false)
                .collect(Collectors.toMap(node -> node.get("name").asText(), node -> node));

        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "read_file", "write_file", "edit_file", "grep", "glob", "run_command", "todo_write",
                "web_search", "web_fetch");

        assertThat(byName.get("read_file").get("shape").asText()).isEqualTo("read");
        assertThat(byName.get("read_file").get("label").asText()).isEqualTo("读取");
        assertThat(byName.get("read_file").get("subjectKey").asText()).isEqualTo("path");
        assertThat(byName.get("read_file").get("subjectIsPath").asBoolean()).isTrue();

        assertThat(byName.get("run_command").get("shape").asText()).isEqualTo("execute");
        assertThat(byName.get("run_command").get("subjectKey").asText()).isEqualTo("command");
        // 命令不是路径 —— 界面据此不把它做成"点开这个文件"的入口
        assertThat(byName.get("run_command").get("subjectIsPath").asBoolean()).isFalse();

        // 清单没有主语：它的参数是一整个列表，不是"某一样东西"。
        // 注意字段是缺席的，不是 null —— 全项目按 {@code default-property-inclusion: non_null}
        // 走，所以"没有"在线上就是"这一项不存在"。前端要按可选读它
        assertThat(byName.get("todo_write").get("shape").asText()).isEqualTo("plan");
        assertThat(byName.get("todo_write").has("subjectKey")).isFalse();

        assertThat(byName.get("web_search").get("shape").asText()).isEqualTo("search");
        assertThat(byName.get("web_search").get("subjectKey").asText()).isEqualTo("queries");
        // 网址不是工作区里的路径 —— 不能做成"点开这个文件"的入口
        assertThat(byName.get("web_fetch").get("shape").asText()).isEqualTo("web");
        assertThat(byName.get("web_fetch").get("subjectKey").asText()).isEqualTo("url");
        assertThat(byName.get("web_fetch").get("subjectIsPath").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("没登录拿不到 —— 默认拒绝，不是默认放行")
    void requiresLogin() throws Exception {
        HttpResponse<String> response = TestBrowser.at(port).get("/api/tools");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    private TestBrowser newAccount() throws Exception {
        String username = "tools-" + UUID.randomUUID();
        createdUsernames.add(username);
        TestBrowser browser = TestBrowser.at(port);
        browser.register(username);
        return browser;
    }
}
