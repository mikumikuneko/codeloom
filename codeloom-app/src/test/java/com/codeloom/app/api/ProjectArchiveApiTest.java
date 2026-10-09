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
 * 下载项目（zip），真 HTTP。
 *
 * <h2>为什么它值得一个测试</h2>
 * 这条路上有两件会**悄悄**坏掉的事：导的是哪个提交（主干，不是某个人的半成品），
 * 以及谁能下（只有成员）。"点一下下载"看起来太简单，简单到不会有人去想这两件 ——
 * 所以它们要有名字。
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
        // 下载而不是"在页面上打开它"：靠的就是这个头
        assertThat(response.headers().firstValue("content-disposition").orElse(""))
                .contains("attachment").contains(".zip");

        List<String> entries = zipEntries(response.body());
        // 建项目时种下的那个 README 在**基点提交**里 —— 它在包里，说明导的是主干那个提交的内容
        assertThat(entries).contains("README.md");
        // 而它不该带仓库里那一层目录名：下载一个项目，不该多出一级 untitled/
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

    /** 读 zip 的条目名。只读条目名就够 —— 它的内容由 git 保证，这里要钉的是"包里是什么形状"。 */
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
