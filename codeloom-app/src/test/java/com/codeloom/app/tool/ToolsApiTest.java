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
 * <h2>它守的是什么</h2>
 * 界面渲染每一条工具调用全靠这份数据 —— 用哪个组件、动作词、主语在参数里的哪一项。
 * 那份形状一变，界面就是"这条显示得糙"，**不会报错**。所以它值得一个测试
 * 把线拉住：接口上的字段名和取值，就是前端 `ToolView` 依赖的那几个。
 *
 * <p>另一半是它**要登录**：工具目录里没有用户数据，但它也不需要对外公开 ——
 * 这是安全配置里"其余一律要登录"那条的默认结果，顺手验一下别哪天被放开。
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
    @DisplayName("七个工具各带形状、词和主语键 —— 界面靠这三样渲染，不再认工具名")
    void everyToolDeclaresItsOwnSurface() throws Exception {
        TestBrowser browser = newAccount();

        HttpResponse<String> response = browser.get("/api/tools");
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

        Map<String, JsonNode> byName = StreamSupport.stream(
                        json.readTree(response.body()).spliterator(), false)
                .collect(Collectors.toMap(node -> node.get("name").asText(), node -> node));

        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "read_file", "write_file", "edit_file", "grep", "glob", "run_command", "todo_write");

        assertThat(byName.get("read_file").get("shape").asText()).isEqualTo("read");
        assertThat(byName.get("read_file").get("label").asText()).isEqualTo("读取");
        assertThat(byName.get("read_file").get("subjectKey").asText()).isEqualTo("path");
        assertThat(byName.get("read_file").get("subjectIsPath").asBoolean()).isTrue();

        assertThat(byName.get("run_command").get("shape").asText()).isEqualTo("execute");
        assertThat(byName.get("run_command").get("subjectKey").asText()).isEqualTo("command");
        // 命令不是路径 —— 界面据此不把它做成"点开这个文件"的入口
        assertThat(byName.get("run_command").get("subjectIsPath").asBoolean()).isFalse();

        // 清单没有主语：它的参数是一整个列表，不是"某一样东西"。
        // 注意**字段是缺席的，不是 null** —— 全项目按 `default-property-inclusion: non_null`
        // 走，所以"没有"在线上就是"这一项不存在"。前端要按可选读它
        assertThat(byName.get("todo_write").get("shape").asText()).isEqualTo("plan");
        assertThat(byName.get("todo_write").has("subjectKey")).isFalse();
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
