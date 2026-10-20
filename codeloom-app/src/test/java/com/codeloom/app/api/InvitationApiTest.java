package com.codeloom.app.api;

import com.codeloom.app.support.TestBrowser;
import com.codeloom.domain.project.ProjectId;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邀请链接的端到端测试（真 HTTP）。
 *
 * <p>链接一次性、有有效期、可撤销，项目还有人数上限，每种边界的失败方式都不同；
 * "点一下就进去"看起来太简单，这些边界需要各自的用例。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
class InvitationApiTest {

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
        // 真提交的数据必须自己清理。本类不加 @Transactional：请求跑在容器线程、用另一条连接，
        // 看不见测试事务里的行
        for (ProjectId projectId : createdProjects) {
            jdbc.update("DELETE FROM project_invitation WHERE project_id = ?", projectId.value());
            jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId.value());
            jdbc.update("DELETE FROM project WHERE id = ?", projectId.value());
        }
        for (String username : createdUsernames) {
            jdbc.update("DELETE FROM `user` WHERE username = ?", username);
        }
        createdProjects.clear();
        createdUsernames.clear();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【主路径】生成链接 → 对方点开就能加入，而且**不用先知道他的账号**")
    void anInvitedPersonJoinsByOpeningTheLink() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);
        TestBrowser invited = newAccount();

        String token = issue(owner, projectId);

        // 预览不要求登录：未注册的人也要先看清被邀请去哪，才能决定是否注册。这里不带 cookie
        JsonNode preview = body(TestBrowser.at(port).get("/api/invitations/" + token));
        assertThat(preview.get("projectId").asText()).isEqualTo(projectId.value());
        assertThat(preview.get("usable").asBoolean()).isTrue();

        assertThat(invited.post("/api/invitations/" + token + "/accept", "{}").statusCode())
                .isEqualTo(200);
        assertThat(membersOf(owner, projectId)).contains(invited.username());
    }

    @Test
    @DisplayName("【一次性】同一张链接用第二次 → 409，而且说的是「已经被用过了」")
    void theLinkWorksOnlyOnce() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);
        String token = issue(owner, projectId);

        TestBrowser first = newAccount();
        assertThat(first.post("/api/invitations/" + token + "/accept", "{}").statusCode())
                .isEqualTo(200);

        TestBrowser second = newAccount();
        HttpResponse<String> again = second.post("/api/invitations/" + token + "/accept", "{}");

        // 分享出去的链接会被转发、截图，一次性是它的全部意义
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("已经被用过了");
    }

    @Test
    @DisplayName("【过期】过了有效期就进不去，而且预览里就说得出来为什么")
    void anExpiredLinkIsRejected() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);
        String token = issue(owner, projectId);

        // 只能直接写库：schema 刻意不允许更新已有邀请的时间（upsert 只更新那三列）。
        // 两个时间必须一起前移，只改 expires_at 会违反 expiresAt > createdAt，
        // 读回时被领域构造器拦下 —— 那才是坏数据
        Instant twoHoursAgo = Instant.now().minusSeconds(7200);
        jdbc.update("UPDATE project_invitation SET created_at = ?, expires_at = ? WHERE token = ?",
                twoHoursAgo, Instant.now().minusSeconds(3600), token);

        JsonNode preview = body(TestBrowser.at(port).get("/api/invitations/" + token));
        assertThat(preview.get("usable").asBoolean()).isFalse();
        assertThat(preview.get("reason").asText()).contains("过期");

        assertThat(newAccount().post("/api/invitations/" + token + "/accept", "{}").statusCode())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("【撤销】撤销之后立刻失效，预览里也说得出原因")
    void aRevokedLinkStopsWorking() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);
        String token = issue(owner, projectId);

        assertThat(owner.delete("/api/projects/" + projectId.value() + "/invitations/" + token)
                .statusCode()).isEqualTo(204);

        JsonNode preview = body(TestBrowser.at(port).get("/api/invitations/" + token));
        assertThat(preview.get("usable").asBoolean()).isFalse();
        assertThat(preview.get("reason").asText()).contains("撤销");

        assertThat(newAccount().post("/api/invitations/" + token + "/accept", "{}").statusCode())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("【满员】项目两个人之后拒绝加入，但**那张链接没有被消耗掉**")
    void aFullProjectRejectsWithoutBurningTheLink() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);
        TestBrowser second = newAccount();

        assertThat(second.post("/api/invitations/" + issue(owner, projectId) + "/accept", "{}")
                .statusCode()).isEqualTo(200);

        String thirdToken = issue(owner, projectId);
        HttpResponse<String> rejected = newAccount()
                .post("/api/invitations/" + thirdToken + "/accept", "{}");

        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(rejected.body()).contains("2 名成员");

        // 链接没有被烧掉：满员可修（移走成员或另开项目），烧掉凭据则修不回来。
        // 拒绝发生在标记已接受之前
        JsonNode preview = body(TestBrowser.at(port).get("/api/invitations/" + thirdToken));
        assertThat(preview.get("usable").asBoolean())
                .as("满员被拒之后这张链接还该是能用的")
                .isTrue();
    }

    @Test
    @DisplayName("【权限】不是项目成员的人生成不了链接 —— 否则谁都能往别人项目里塞人")
    void onlyMembersCanIssueInvitations() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);
        TestBrowser stranger = newAccount();

        // 404 而不是 403：对"不是我的东西"一律装作不存在，否则可拿一批 id 枚举项目
        assertThat(stranger.post("/api/projects/" + projectId.value() + "/invitations", "{}")
                .statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("【不存在的凭据】404，而且和「不是你的」看起来一样")
    void anUnknownTokenLooksLikeNothing() throws Exception {
        assertThat(TestBrowser.at(port).get("/api/invitations/" + randomToken()).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("【列表】还在外面飘的那张看得见，兑现掉的、撤销掉的一律不出现")
    void pendingListShowsOnlyWhatIsStillOutThere() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);

        String stillOut = issue(owner, projectId);

        // 仍在有效期内的凭据必须可见：忘记发过的有效链接最危险
        assertThat(tokensOf(owner, projectId)).containsExactly(stillOut);

        owner.delete("/api/projects/" + projectId.value() + "/invitations/" + stillOut);
        // 撤销后不再是凭据，清单只保留当前可用的；否则每次生成都会攒下一行
        assertThat(tokensOf(owner, projectId)).isEmpty();
    }

    @Test
    @DisplayName("【一张】再生成一张会让上一张当场失效 —— 一个项目同时只有一张有效链接")
    void issuingAgainRevokesThePreviousOne() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);

        String first = issue(owner, projectId);
        String second = issue(owner, projectId);

        // 清单里只有新的那张，旧的不再属于"仍在外面"
        assertThat(tokensOf(owner, projectId)).containsExactly(second);

        // 旧链接确实失效：预览给出原因，接受被拒绝
        JsonNode preview = body(TestBrowser.at(port).get("/api/invitations/" + first));
        assertThat(preview.get("usable").asBoolean()).isFalse();
        assertThat(preview.get("reason").asText()).contains("撤销");
        assertThat(newAccount().post("/api/invitations/" + first + "/accept", "{}").statusCode())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("【自己点自己的】发起人点开自己那张链接 → 放行，但凭据**不许被烧掉**")
    void openingYourOwnLinkDoesNotBurnIt() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);
        String mine = issue(owner, projectId);

        // 他本来就在里面，所以这一步成功（不是错误），界面会把他送进项目
        assertThat(owner.post("/api/invitations/" + mine + "/accept", "{}").statusCode())
                .isEqualTo(200);

        // 链接仍然有效：它本是发给对方的，发起人自己点一下不该毁掉凭据。
        // 若把自己记成接受者，就看不到"对方已加入"的真实状态
        JsonNode preview = body(TestBrowser.at(port).get("/api/invitations/" + mine));
        assertThat(preview.get("usable").asBoolean())
                .as("自己点过之后，这张链接还该是能用的")
                .isTrue();

        // 而且确实还能用：换一个新账号仍可加入
        assertThat(newAccount().post("/api/invitations/" + mine + "/accept", "{}").statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("【已经在内】成员点别人发的链接 → 也放行，那张凭据同样不许被烧")
    void anExistingMemberDoesNotBurnSomeoneElsesLink() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);
        TestBrowser member = newAccount();
        assertThat(member.post("/api/invitations/" + issue(owner, projectId) + "/accept", "{}")
                .statusCode()).isEqualTo(200);

        // 再生成一张给第三人，然后让已在项目里的成员去点它
        String forThird = issue(owner, projectId);
        assertThat(member.post("/api/invitations/" + forThird + "/accept", "{}").statusCode())
                .isEqualTo(200);

        JsonNode preview = body(TestBrowser.at(port).get("/api/invitations/" + forThird));
        assertThat(preview.get("usable").asBoolean())
                .as("成员点了它之后，这张链接还该是能用的")
                .isTrue();
    }

    @Test
    @DisplayName("【预览】说得出拿着链接的人是谁 —— 没加入 / 已经在里面 / 就是他发的")
    void previewSaysWhoIsHoldingTheLink() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);
        TestBrowser member = newAccount();
        String forGuest = issue(owner, projectId);

        // 未登录：纯访客，登录后可加入
        assertThat(viewerOf(TestBrowser.at(port), forGuest)).isEqualTo("GUEST");
        // 已登录但尚未加入：同样是 GUEST，界面给「加入项目」
        assertThat(viewerOf(member, forGuest)).isEqualTo("GUEST");
        // 发起人自己：界面不能给「加入项目」，按下去什么也不会发生
        assertThat(viewerOf(owner, forGuest)).isEqualTo("INVITER");

        // 加入之后，再看同一链接就是 MEMBER
        assertThat(member.post("/api/invitations/" + forGuest + "/accept", "{}").statusCode())
                .isEqualTo(200);
        assertThat(viewerOf(member, forGuest)).isEqualTo("MEMBER");
    }

    /** 拿着这张链接的人是谁（预览里那一栏）。 */
    private String viewerOf(TestBrowser viewer, String token) throws Exception {
        return body(viewer.get("/api/invitations/" + token)).get("viewer").asText();
    }

    @Test
    @DisplayName("【列表】用掉的那张不再出现 —— 凭据已经兑现成成员身份")
    void consumedInvitationDisappears() throws Exception {
        TestBrowser owner = newAccount();
        ProjectId projectId = createProject(owner);

        String used = issue(owner, projectId);
        assertThat(newAccount().post("/api/invitations/" + used + "/accept", "{}").statusCode())
                .isEqualTo(200);

        assertThat(tokensOf(owner, projectId)).isEmpty();
    }

    // ------------------------------------------------------------------

    /** 生成一张链接并返回 token；客户端用它拼完整链接，服务端不猜自己的对外地址。 */
    private String issue(TestBrowser owner, ProjectId projectId) throws Exception {
        HttpResponse<String> created = owner.post(
                "/api/projects/" + projectId.value() + "/invitations", "{}");
        assertThat(created.statusCode()).isEqualTo(201);
        return body(created).get("token").asText();
    }

    private List<String> tokensOf(TestBrowser owner, ProjectId projectId) throws Exception {
        JsonNode list = body(owner.get("/api/projects/" + projectId.value() + "/invitations"));
        List<String> tokens = new ArrayList<>();
        list.forEach(node -> tokens.add(node.get("token").asText()));
        return tokens;
    }

    private List<String> membersOf(TestBrowser owner, ProjectId projectId) throws Exception {
        JsonNode project = body(owner.get("/api/projects/" + projectId.value()));
        List<String> names = new ArrayList<>();
        project.get("members").forEach(member -> names.add(member.get("username").asText()));
        return names;
    }

    private ProjectId createProject(TestBrowser owner) throws Exception {
        HttpResponse<String> created = owner.post("/api/projects", """
                {"name":"invite-%s"}
                """.formatted(UUID.randomUUID()));
        assertThat(created.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        ProjectId id = ProjectId.of(body(created).get("id").asText());
        createdProjects.add(id);
        return id;
    }

    private TestBrowser newAccount() throws Exception {
        String username = "inv-" + UUID.randomUUID();
        createdUsernames.add(username);
        TestBrowser browser = TestBrowser.at(port);
        browser.register(username);
        return browser;
    }

    /** 43 个 URL 安全字符，和真 token 一样长 —— 免得测的是"格式就先被拒了"。 */
    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }
}
