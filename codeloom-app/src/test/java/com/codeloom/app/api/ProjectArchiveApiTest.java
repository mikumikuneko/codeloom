package com.codeloom.app.api;

import com.codeloom.app.support.TempDirs;
import com.codeloom.app.support.TestBrowser;
import com.codeloom.domain.project.ProjectId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 项目下载（zip）的端到端测试。
 *
 * <p>这条路上有两件会悄悄坏掉的事：导出的是哪个提交（主干，而不是某个人的半成品），
 * 以及谁能下载（只有成员）。"点一下下载"看起来太简单，这两条需要各自的用例。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
class ProjectArchiveApiTest {

    private static final Path REPOS_ROOT = TempDirs.create("codeloom-zip-repos-");
    private static final Path WORKSPACES_ROOT = TempDirs.create("codeloom-zip-ws-");

    @DynamicPropertySource
    static void codeloomProperties(DynamicPropertyRegistry registry) {
        registry.add("codeloom.repos-root", REPOS_ROOT::toString);
        registry.add("codeloom.workspaces-root", WORKSPACES_ROOT::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private final List<ProjectId> createdProjects = new ArrayList<>();
    private final List<String> createdUsernames = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (ProjectId projectId : createdProjects) {
            jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId.value());
            jdbc.update("DELETE FROM project WHERE id = ?", projectId.value());
        }
        for (String username : createdUsernames) {
            jdbc.update("DELETE FROM `user` WHERE username = ?", username);
        }
        createdProjects.clear();
        createdUsernames.clear();
    }

    @AfterAll
    static void deleteTempDirs() {
        TempDirs.deleteRecursively(REPOS_ROOT);
        TempDirs.deleteRecursively(WORKSPACES_ROOT);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【主路径】成员下到的是 zip，里面是项目的内容 —— 而且不带 untitled 那一层")
    void aMemberDownloadsTheTrunkAsZip() throws Exception {
        TestBrowser owner = newAccount();
        String projectId = createProject(owner, "下载测试");

        HttpResponse<InputStream> response = owner.getStreaming("/api/projects/" + projectId + "/archive");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("zip");
        // 是下载而不是在页面上打开，靠的正是这个响应头
        assertThat(response.headers().firstValue("content-disposition").orElse(""))
                .contains("attachment").contains(".zip");

        List<String> entries = zipEntries(response.body());
        // 建档时种下的 README 在基点提交里；它出现在包中，说明导出的是主干提交的内容
        assertThat(entries).contains("README.md");
        // 包里不应带仓库里那一层目录名：下载项目不该多出 untitled/
        assertThat(entries).noneMatch(name -> name.startsWith("untitled/"));
    }

    @Test
    @DisplayName("【授权】不是成员的人下不到 —— 404，连「这个项目在不在」都不告诉他")
    void aStrangerCannotDownload() throws Exception {
        TestBrowser owner = newAccount();
        String projectId = createProject(owner, "别人的项目");
        TestBrowser stranger = newAccount();

        assertThat(stranger.get("/api/projects/" + projectId + "/archive").statusCode())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------

    /** 读 zip 条目名；内容由 git 保证，这里要钉的是包的形状。 */
    private static List<String> zipEntries(InputStream body) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(body)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private TestBrowser newAccount() throws Exception {
        String username = "zip-" + UUID.randomUUID();
        createdUsernames.add(username);
        TestBrowser browser = TestBrowser.at(port);
        browser.register(username);
        return browser;
    }

    private String createProject(TestBrowser browser, String name) throws Exception {
        HttpResponse<String> response = browser.post("/api/projects", "{\"name\":\"" + name + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        String id = json.readTree(response.body()).path("id").asText();
        createdProjects.add(ProjectId.of(id));
        return id;
    }
}
