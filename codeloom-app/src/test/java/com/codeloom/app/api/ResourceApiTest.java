package com.codeloom.app.api;

import com.codeloom.agent.support.ScriptedLlm;
import com.codeloom.app.project.ProjectLayout;
import com.codeloom.app.support.Await;
import com.codeloom.app.support.ScriptedLlmClientProvider;
import com.codeloom.app.support.StubModelConfig;
import com.codeloom.app.support.TempDirs;
import com.codeloom.app.support.TestGit;
import com.codeloom.app.support.TestBrowser;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import com.codeloom.workspace.LocalWorkspaceManager;
import com.codeloom.workspace.git.GitClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 项目与会话 REST 接口及资源级授权；真 HTTP、真 git、真库。
 *
 * <p>安全断言是重点：登录只是第一道门，"登录后能碰谁的什么"才是接口契约。
 * 不加 {@code @Transactional}：请求跑在容器线程、用另一条连接，看不见测试事务里未提交的行；
 * 代价是收尾自行清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubModelConfig.class)
// 免审批名单置空，让"挂起等人批"的测试真的挂起。用默认名单时，是否挂起会随
// application.yml 的常用命令增删而变，测试成败不该取决于那份列表
@TestPropertySource(properties = "codeloom.command-whitelist=")
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
class ResourceApiTest {

    private static final Path REPOS_ROOT = TempDirs.create("codeloom-it-repos-");
    private static final Path WORKSPACES_ROOT = TempDirs.create("codeloom-it-ws-");

    @DynamicPropertySource
    static void codeloomProperties(DynamicPropertyRegistry registry) {
        registry.add("codeloom.repos-root", REPOS_ROOT::toString);
        registry.add("codeloom.workspaces-root", WORKSPACES_ROOT::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private EventStore events;

    @Autowired
    private ChatMessageRepository chat;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedLlmClientProvider model;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private GitClient git;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private ExecutionLease leases;

    private final List<ProjectId> createdProjects = new ArrayList<>();
    private final List<String> createdUsernames = new ArrayList<>();

    // ------------------------------------------------------------------
    // 主路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("建项目：库里多一行，磁盘上多一个 git 仓库，而且有那个必需的基点提交")
    void creatingAProjectInitialisesARepository() throws Exception {
        TestBrowser alice = register("alice");

        HttpResponse<String> created = alice.post("/api/projects", """
                {"name":"codeloom 演示"}
                """);

        assertThat(created.statusCode()).isEqualTo(201);
        ProjectId projectId = ProjectId.of(body(created).get("id").asText());
        createdProjects.add(projectId);

        Path repo = REPOS_ROOT.resolve(projectId.value());
        assertThat(Files.isDirectory(repo.resolve(".git"))).isTrue();
        // 基点提交不是形式：没有它，两条会话分支会成为互不相关的历史，第一次合并会被拒。
        // 这里验证历史里确实有一个提交，而不是 git init 跑过
        assertThat(TestGit.run(repo, "rev-list", "--count", "HEAD").strip()).isEqualTo("1");

        // 新项目带着一个 README.md 起步。这里列的是项目根的内容，不是工作区根：
        // 工作区根下还有 untitled/（项目根），界面不应看见它（见 ProjectLayout）。
        // 走界面取数用的接口验证，顺带验证创建者的工作区在创建时已建好；缺了那步这里会是 500
        HttpResponse<String> entries =
                alice.get("/api/projects/" + projectId.value() + "/files?path=");
        assertThat(entries.statusCode()).isEqualTo(200);
        assertThat(body(entries).findValuesAsText("path")).containsExactly("README.md");

        // README 必须在基点提交里：两棵树都从基点 checkout，留成未跟踪则两边都没有它
        assertThat(TestGit.run(repo, "ls-tree", "-r", "--name-only", "HEAD").strip())
                // 基点提交只有用户的内容；平台中间产物由 info/exclude 挡在版本库外
                //（见 LocalWorkspaceManager#excludeToolOutput），不进历史
                .isEqualTo("untitled/README.md");

        // 响应里不该出现服务端的文件系统路径
        assertThat(created.body()).doesNotContain(REPOS_ROOT.toString());
    }

    @Test
    @DisplayName("建会话：工作区真的建出来了，事件流的第 0 条也落了库")
    void creatingASessionCreatesAWorktreeAndTheFirstEvent() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);

        HttpResponse<String> created = alice.post("/api/projects/" + projectId.value() + "/sessions", """
                {"provider":"deepseek","modelId":"deepseek-chat"}
                """);

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode session = body(created);
        SessionId sessionId = SessionId.of(session.get("id").asText());

        // 工作区是一个真 worktree，落在 workspaces-root 下按 ownerId、projectId 分两层的目录
        assertThat(Files.isDirectory(worktreeOf(sessionId))).isTrue();
        assertThat(session.get("state").asText()).isEqualTo("IDLE");
        // 还没人说过话，该项为空。必须用 hasNonNull 而不是 path(...).isNull()：
        // 应用把 null 从 JSON 中省略，"空"表现为键不存在，而 MissingNode.isNull() 为 false
        assertThat(session.hasNonNull("firstMessage")).isFalse();

        // 两条事件：起点和第 0 个 checkpoint（会话刚建、尚未跑过任何一轮的位置）。
        // 少了后者就无法撤销第一轮的全部改动
        assertThat(events.readAll(sessionId))
                .extracting(stored -> stored.event().getClass().getSimpleName())
                .containsExactly("SessionStarted", "CheckpointCreated");
        // 它们都走带 token 的写入路径：事实来源上没有"这条不用记账"的例外
    }

    @Test
    @DisplayName("发一句话 → 一轮跑完，事件流里能看到用户消息和模型的回答")
    void sendingAMessageRunsATurn() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);

        model.script(ScriptedLlm.answer("你好，我是你的协作助手"));

        HttpResponse<String> turn = alice.post("/api/sessions/" + sessionId.value() + "/messages", """
                {"text":"你好"}
                """);

        assertThat(turn.statusCode()).isEqualTo(200);
        assertThat(body(turn).get("status").asText()).isEqualTo("COMPLETED");

        assertThat(events.readAll(sessionId))
                .extracting(stored -> stored.event().getClass().getSimpleName())
                .contains("SessionStarted", "SessionStateChanged", "UserMessage", "AssistantMessage");
        assertThat(events.readAll(sessionId)).anySatisfy(stored ->
                assertThat(stored.event()).isEqualTo(new UserMessage("你好")));

        // 历史列表用用户开口的第一句区分会话（见 SessionView）：同一栏里都是同一个人的会话，
        // "谁在说"区分不了
        assertThat(body(alice.get("/api/projects/" + projectId.value() + "/sessions"))
                .findValuesAsText("firstMessage"))
                .containsExactly("你好");
    }

    @Test
    @DisplayName("「我的项目」只列我参与的")
    void myProjectsOnlyIncludeMine() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser bob = register("bob");
        ProjectId alices = createProject(alice);
        ProjectId bobs = createProject(bob);

        assertThat(alice.get("/api/projects").body())
                .contains(alices.value())
                .doesNotContain(bobs.value());
    }

    @Test
    @DisplayName("【分页】limit 真的生效，而且荒唐的参数不会变成 500")
    void listPagingIsBoundedAndForgiving() throws Exception {
        TestBrowser alice = register("alice");
        createProject(alice);
        createProject(alice);
        createProject(alice);

        // 默认 limit 是 50，必须显式传小值才看得出它生效
        assertThat(body(alice.get("/api/projects?limit=1")).size()).isEqualTo(1);
        assertThat(body(alice.get("/api/projects?limit=2")).size()).isEqualTo(2);
        // offset 跳过太多返回空数组，不是错误
        assertThat(body(alice.get("/api/projects?offset=99")).size()).isZero();

        // offset 和 limit 由客户端完全控制，不夹取会直接进入 SQL。负数透传是语法错误
        //（实测 MySQL 抛 BadSqlGrammarException），并非"从头开始"，不夹就是一条通向 500 的捷径
        assertThat(alice.get("/api/projects?offset=-1").statusCode()).isEqualTo(200);
        // 天文数字的 limit 要夹到上限，而不是照单全收
        assertThat(alice.get("/api/projects?limit=1000000").statusCode()).isEqualTo(200);
        // limit=0 夹成 1；返回 0 条会让调用方误以为没有数据
        assertThat(body(alice.get("/api/projects?limit=0")).size()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 挂起与批准
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【批准】挂起的调用能被答复，会话接着跑完 —— 这条循环从前一条测试都没有")
    void aSuspendedCallCanBeApprovedAndTheTurnResumes() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);

        // 类的 @TestPropertySource 把免审批名单置空，这条 run_command 必然挂起等人批。
        // command 是一整行字符串而非数组（见 RunCommandTool 的 schema）。
        // 用 Jackson 造参数而不手拼 JSON：手拼曾把真实换行塞进字符串，工具报参数不是合法 JSON，
        // 后续断言都跑在错误的初始状态上
        model.script(
                ScriptedLlm.toolCall("c1", "run_command",
                        json.writeValueAsString(Map.of("command", "java -version"))),
                ScriptedLlm.answer("好，跑完了"));
        send(alice, sessionId, "看看 java 版本");

        // 停在这里等人批。没有 ToolResult 是刻意的：调用还没跑，其结果要等答复时的
        // ToolApprovalResolved
        assertThat(events(alice, sessionId, 0)).anySatisfy(event ->
                assertThat(event.get("type").asText()).isEqualTo("TOOL_APPROVAL_REQUESTED"));
        assertThat(sessionState(alice, sessionId)).isEqualTo("AWAITING_APPROVAL");

        HttpResponse<String> approved = alice.post(
                "/api/sessions/" + sessionId.value() + "/approvals/c1", "{\"approved\":true}");

        // 202 而不是 200：答复已记下，但那一轮还没跑完，随后会被续跑
        assertThat(approved.statusCode()).isEqualTo(202);

        // 续跑是异步的；等到会话走出 AWAITING_APPROVAL，说明它没有停在
        // "等待一个已经给出的答复"的状态里
        Await.until("会话从 AWAITING_APPROVAL 走出来",
                () -> "WAITING_USER".equals(sessionState(alice, sessionId)));

        assertThat(events(alice, sessionId, 0)).anySatisfy(event ->
                assertThat(event.get("type").asText()).isEqualTo("TOOL_APPROVAL_RESOLVED"));

        // 批准之后命令真的执行了。这里断言的是唯一走完全链路的路径：
        // agent 循环 → 挂起 → 答复 → resume → 补跑 → 落库 → 投影。
        // 单测只能证明循环里补跑了，不能证明结果走到了事件流里
        assertThat(events(alice, sessionId, 0)).anySatisfy(event -> {
            assertThat(event.get("type").asText()).isEqualTo("TOOL_RESULT");
            JsonNode payload = event.get("payload");
            assertThat(payload.get("callId").asText()).isEqualTo("c1");
            assertThat(payload.get("success").asBoolean()).isTrue();
            assertThat(payload.get("output").asText()).containsIgnoringCase("version");
        });
    }

    @Test
    @DisplayName("【拒绝】没留下指示时那一轮停住等你 —— 模型不再多跑一轮")
    void rejectingWithoutInstructionsStopsTheTurn() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);

        model.script(
                ScriptedLlm.toolCall("c1", "run_command",
                        json.writeValueAsString(Map.of("command", "java -version"))),
                ScriptedLlm.answer("好，跑完了"));
        send(alice, sessionId, "看看 java 版本");
        assertThat(sessionState(alice, sessionId)).isEqualTo("AWAITING_APPROVAL");

        // 拒绝且不留任何指示；界面上本来也没有输入理由的地方
        HttpResponse<String> rejected = alice.post(
                "/api/sessions/" + sessionId.value() + "/approvals/c1", "{\"approved\":false}");
        assertThat(rejected.statusCode()).isEqualTo(202);

        // 那一轮停在等用户说话，而不是变成 FAILED；用户叫停是常态，不是错误
        Await.until("会话从 AWAITING_APPROVAL 走出来",
                () -> "WAITING_USER".equals(sessionState(alice, sessionId)));

        // 模型没有再被调用：脚本第二句"好，跑完了"没有消费。拒绝且无指示时不续跑。
        // 用脚本消费到哪一步来断言，而不是去事件流里找"没发生的事"
        assertThat(model.requests()).as("模型调用次数").hasSize(1);

        List<JsonNode> stream = events(alice, sessionId, 0);
        assertThat(stream).anySatisfy(event ->
                assertThat(event.get("type").asText()).isEqualTo("TOOL_APPROVAL_RESOLVED"));
        // 那次调用没有执行：挂起时没有 ToolResult，拒绝后也没有
        assertThat(stream).noneSatisfy(event ->
                assertThat(event.get("type").asText()).isEqualTo("TOOL_RESULT"));

        // 但必须有一条收尾记录。消费端都靠"有没有收尾记录"判断调用是否结束；缺了它，
        // 界面上那一行会一直停在"正在跑"。它与 ToolApprovalResolved 是两个事实：
        // 后者记录谁做了决定、为什么，前者表示这次调用到此为止
        assertThat(stream).anySatisfy(event -> {
            assertThat(event.get("type").asText()).isEqualTo("TOOL_REJECTED");
            assertThat(event.get("payload").get("callId").asText()).isEqualTo("c1");
        });

        // 轮次号推进一格：用户叫停意味着这次交互结束，下一句属于第 2 轮；不推进会重复第 1 轮
        assertThat(body(alice.get("/api/sessions/" + sessionId.value())).get("turnIndex").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("【批准·权限】只有会话所有者能批 —— 队友 403，陌生人 404")
    void onlyTheOwnerCanApprove() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");
        TestBrowser stranger = register("stranger");
        SessionId mine = createSession(alice, projectId);
        String url = "/api/sessions/" + mine.value() + "/approvals/c1";

        // 403：他是项目成员且能看到会话列表，"这东西存在"并非秘密。
        // 放行一次危险调用等于替对方的选择负责：对方用自己的密钥驱动自己的 agent，
        // 你既不知道它在做什么，也不知道为什么
        assertThat(bob.post(url, "{\"approved\":true}").statusCode()).isEqualTo(403);
        // 404：非成员一律按不存在处理
        assertThat(stranger.post(url, "{\"approved\":true}").statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // 事件读取与回滚
    // ------------------------------------------------------------------

    @Test
    @DisplayName("事件端点：按 seq 升序、带类型判别字段、afterSeq 是开区间")
    void eventsAreReadableForReplay() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);
        model.script(ScriptedLlm.answer("好的"));
        send(alice, sessionId, "你好");

        List<JsonNode> all = events(alice, sessionId, 0);
        assertThat(all).isNotEmpty();
        // 每条都带类型判别字段，客户端用它决定渲染方式；
        // 该格式与 SSE 帧、Pub/Sub 消息体一致（都出自 EventEnvelopeCodec）
        assertThat(all).allSatisfy(event -> assertThat(event.hasNonNull("type")).isTrue());
        // 类型是 EventType 的枚举名而不是类名。它是持久化格式的一部分，发布后不能改；
        // 因此刻意使用显式枚举，而不是 getSimpleName() 推导
        assertThat(all.get(0).get("type").asText()).isEqualTo("SESSION_STARTED");

        long firstSeq = all.getFirst().get("seq").asLong();
        // 开区间：游标那条自己不再返回，否则断线重连会重复渲染一条
        assertThat(events(alice, sessionId, firstSeq))
                .extracting(event -> event.get("seq").asLong())
                .doesNotContain(firstSeq)
                .isNotEmpty();
    }

    @Test
    @DisplayName("回滚：代码退回那个 checkpoint，轮次号也跟着退 —— 两者绑死")
    void rewindingRollsBackCodeAndTheTurnIndexTogether() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);

        // 第一轮让 agent 写一个文件。用 Notes.md 而不是 README.md：项目根里已有新项目自带的
        // 空 README.md，write_file 会拒绝覆盖已存在文件，那会让这条测试变成"写不进去"
        model.script(ScriptedLlm.toolCall("c1", "write_file",
                        "{\"path\":\"Notes.md\",\"content\":\"第一版\"}"),
                ScriptedLlm.answer("写好了"));
        send(alice, sessionId, "写点笔记");

        // 两个 checkpoint：会话创建时和本轮结束时。turnIndex 数的是已完成交互数：
        // 创建时 0，跑完一轮为 1
        List<JsonNode> checkpoints = checkpoints(alice, sessionId);
        assertThat(checkpoints).hasSize(2);
        assertThat(checkpoints).extracting(node -> node.get("turnIndex").asInt())
                .containsExactly(0, 1);
        // 目标是第一个 checkpoint（会话刚创建、尚未跑过任何一轮），即"撤销这一轮的全部改动"。
        // 送事件流里的序号而不是 commit sha：本轮没有改动时两条 checkpoint 同 sha，
        // 只有序号能定位是哪一条（见 RewindRequest）
        long firstCheckpoint = checkpointSeqs(alice, sessionId).getFirst();
        Path projectRoot = ProjectLayout.rootBelow(worktreeOf(sessionId));
        assertThat(Files.exists(projectRoot.resolve("Notes.md"))).isTrue();

        // 回滚到那一轮之前；文件由该轮写入，因此应当消失
        HttpResponse<String> rewound = alice.post("/api/sessions/" + sessionId.value() + "/rewind", """
                {"toCheckpointSeq":%d}
                """.formatted(firstCheckpoint));

        assertThat(rewound.statusCode()).isEqualTo(200);
        assertThat(Files.exists(projectRoot.resolve("Notes.md"))).isFalse();
        // 轮次号与代码一起退回：只退代码的话，checkpoint 与对话的对应就悄悄错位了
        assertThat(body(rewound).get("turnIndex").asInt()).isZero();
        // 回滚本身也留痕 —— 否则回放时会看到代码突然退回旧版本而没有任何解释
        assertThat(events(alice, sessionId, 0)).anySatisfy(event ->
                assertThat(event.get("type").asText()).isEqualTo("SESSION_REWOUND"));
    }

    @Test
    @DisplayName("【回滚】同步过之后照样能回滚 —— 退掉的是这棵树上的一切，对方一点没少")
    void rewindingAfterASyncOnlyTouchesMyOwnTree() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");
        SessionId mine = createSession(alice, projectId);
        SessionId theirs = createSession(bob, projectId);

        // 我先在自己的树上跑一轮，留下回滚点
        writeFile(alice, mine, "mine.txt", "我的");
        int checkpointsBeforeSync = checkpoints(alice, mine).size();
        long beforeSync = checkpointSeqs(alice, mine).getLast();

        // 对方合进主干，我同步进来，于是我的分支多了别人的提交
        writeFile(bob, theirs, "theirs.txt", "对方写的");
        assertThat(bob.post("/api/sessions/" + theirs.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);
        assertThat(alice.post("/api/sessions/" + mine.value() + "/sync", "{}").statusCode())
                .isEqualTo(200);
        assertThat(sessionFile(mine, "theirs.txt")).isEqualTo("对方写的");

        // 同步本身不产生回滚点：回滚点要求代码位置与对话位置成对，而同步不推进对话。
        // "回到同步前"靠上一轮结束那个点，它恰好在同步前一刻，因为其间没人动过我的分支
        assertThat(checkpoints(alice, mine)).hasSize(checkpointsBeforeSync);

        // 回滚到同步前：同步进来的内容也是我这棵树的一部分，一起退掉
        HttpResponse<String> rewound = alice.post("/api/sessions/" + mine.value() + "/rewind", """
                {"toCheckpointSeq":%d}
                """.formatted(beforeSync));

        assertThat(rewound.statusCode()).isEqualTo(200);
        assertThat(sessionFile(mine, "theirs.txt")).isNull();

        // 但对方一点没少：回滚只是在自己分支上做了一次 git reset，
        // 主干和对方的工作区都不在路径上。旧的 409（"回滚会吃掉别人的代码"）已删除：
        // 那种伤害不会发生，对方的内容仍在主干和他自己的树里，下次同步就能拿回来，
        // 而那道检查的代价是永远回不到同步前
        assertThat(mainFile(projectId, "theirs.txt")).isEqualTo("对方写的");
        assertThat(sessionFile(theirs, "theirs.txt")).isEqualTo("对方写的");
    }

    @Test
    @DisplayName("只能回滚到这条会话自己打过的 checkpoint")
    void arbitraryCommitsCannotBeUsedAsARewindTarget() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);
        model.script(ScriptedLlm.answer("好"));
        send(alice, sessionId, "随便说句话");

        // 不校验的话，调用方可以用任意 sha 决定 reset 到哪里，因此这条限制不能放松
        HttpResponse<String> response = alice.post("/api/sessions/" + sessionId.value() + "/rewind", """
                {"toCheckpointSeq":999999}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("checkpoint");
    }

    @Test
    @DisplayName("【回滚】没给目标序号就直接拒绝 —— 别把它当成「退到第 0 条」")
    void rewindWithoutATargetIsRejected() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);
        model.script(ScriptedLlm.answer("好"));
        send(alice, sessionId, "随便说句话");

        // 请求体缺字段时 Jackson 给 null。这里验证它不能在读成 0 后悄悄退到会话开头，
        // 否则调用方写错一个字段名就会丢掉整段对话且不报错
        HttpResponse<String> response = alice.post("/api/sessions/" + sessionId.value() + "/rewind", "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("toCheckpointSeq");
    }

    @Test
    @DisplayName("【清单】模型写清单 → 事件落得下来、读得回来（JSON 那一列的往返）")
    void theTodoListSurvivesTheDatabase() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);
        model.script(
                ScriptedLlm.toolCall("c1", "todo_write", json.writeValueAsString(Map.of(
                        "todos", List.of(
                                Map.of("content", "改三个文件", "state", "in_progress"),
                                Map.of("content", "跑测试", "state", "pending"))))),
                ScriptedLlm.answer("好，我开始了"));

        send(alice, sessionId, "把这几件事做了");

        // payload 存在数据库 JSON 列里，MySQL 会规范化键序与数字形式。
        // 为防止写入与读回成为两份数据，这条测试走完整路径：工具 → 事件 → SQL → 读回
        JsonNode event = events(alice, sessionId, 0).stream()
                .filter(node -> "TODO_LIST_UPDATED".equals(node.get("type").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("事件流里没有 TODO_LIST_UPDATED"));
        JsonNode items = event.get("payload").get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("content").asText()).isEqualTo("改三个文件");
        assertThat(items.get(0).get("state").asText()).isEqualTo("IN_PROGRESS");
        assertThat(items.get(1).get("state").asText()).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------
    // 丢弃会话
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【丢弃】对话没了、**代码留着** —— 新开一条会话接着上一轮的改动继续")
    void discardingASessionKeepsTheTree() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId abandoned = createSession(alice, projectId);

        writeFile(alice, abandoned, "work.txt", "上一轮干的活");
        // 顺手造一条幂等键，验证它与会话一起删除
        jdbc.update("INSERT INTO turn_request (session_id, client_message_id, created_at)"
                + " VALUES (?, ?, NOW(3))", abandoned.value(), "req-1");

        assertThat(alice.delete("/api/sessions/" + abandoned.value()).statusCode()).isEqualTo(204);

        // 会话真的没了：查不到，事件流空了，幂等键也清了
        assertThat(alice.get("/api/sessions/" + abandoned.value()).statusCode()).isEqualTo(404);
        assertThat(events.readAll(abandoned)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM turn_request WHERE session_id = ?",
                Integer.class, abandoned.value())).isZero();

        // 代码还在：树属于「人 + 项目」而不是会话，收拾对话列表不必连代码一起删
        SessionId fresh = createSession(alice, projectId);
        assertThat(sessionFile(fresh, "work.txt")).isEqualTo("上一轮干的活");
        // 丢弃本身也没有改动 git 分支，主干一如既往地没有这个文件
        assertThat(Files.exists(REPOS_ROOT.resolve(projectId.value()).resolve("work.txt"))).isFalse();
    }

    @Test
    @DisplayName("【权限】只有会话所有者能丢弃自己的对话 —— 队友看得见，但删不掉")
    void onlyTheOwnerCanDiscardASession() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");
        SessionId mine = createSession(alice, projectId);

        // 403 而不是 404：他是项目成员且能看到会话列表，"这东西存在"不是秘密，
        // 假装不存在只会让人困惑（见 ProjectAccess）
        assertThat(bob.delete("/api/sessions/" + mine.value()).statusCode()).isEqualTo(403);

        assertThat(alice.get("/api/sessions/" + mine.value()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("【丢弃】正在跑的时候是 409 —— 不然那一轮收尾会把空壳会话重新插回来")
    void discardingWhileRunningIsRefused() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);

        // 直接占住这棵树的租约，模拟有一轮正在运行
        Session session = sessions.findById(sessionId).orElseThrow();
        LeaseToken held = leases.tryAcquire(session).orElseThrow();
        try {
            assertThat(alice.delete("/api/sessions/" + sessionId.value()).statusCode())
                    .isEqualTo(409);

            // 拒绝意味着什么都没发生：会话仍在，仍可打开
            assertThat(alice.get("/api/sessions/" + sessionId.value()).statusCode()).isEqualTo(200);
        } finally {
            leases.release(held);
        }
    }

    // ------------------------------------------------------------------
    // 聊天记录
    // ------------------------------------------------------------------

    @Test
    @DisplayName("聊天记录：按时间正序读，队友能看，非成员看不到")
    void chatHistoryIsReadableByMembers() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser bob = register("bob");
        TestBrowser stranger = register("stranger");
        ProjectId projectId = createProject(alice);
        addMember(alice, projectId, bob);

        // 聊天消息平时由 WebSocket 产生；这里直接落库，因为这条测试验证读取，
        // 写入路径由 ChatWebSocketHandlerTest 覆盖
        chat.append(projectId, membersOf(alice, bob).get(0), "你这段不对", null, null, Instant.now());
        chat.append(projectId, membersOf(alice, bob).get(1), "哪儿？", 42L, "编辑了 OrderService.java", Instant.now());

        List<JsonNode> history = chatHistory(bob, projectId);

        assertThat(history).extracting(node -> node.get("text").asText())
                .containsExactly("你这段不对", "哪儿？");
        // 锚点用于引用回复，指向某条 agent 事件，让"我刚才看你那段不对"有明确位置
        assertThat(history.getLast().get("anchorEventSeq").asLong()).isEqualTo(42L);

        // 非成员看不到 —— 聊天室是项目级的，权限按成员判
        assertThat(stranger.get("/api/projects/" + projectId.value() + "/chat/messages").statusCode())
                .isEqualTo(404);
    }

    private List<UserId> membersOf(TestBrowser alice, TestBrowser bob) throws Exception {
        JsonNode project = body(alice.get("/api/projects"));
        String aliceId = project.get(0).get("members").get(0).get("id").asText();
        String bobId = project.get(0).get("members").get(1).get("id").asText();
        return List.of(UserId.of(aliceId), UserId.of(bobId));
    }

    private List<JsonNode> chatHistory(TestBrowser owner, ProjectId projectId) throws Exception {
        HttpResponse<String> response = owner.get(
                "/api/projects/" + projectId.value() + "/chat/messages");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readerForListOf(JsonNode.class).readValue(response.body());
    }

    // ------------------------------------------------------------------
    // 合并与冲突裁决
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合并：会话的产出干净地进主干")
    void mergingASessionIntoMainLandsTheWork() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);
        writeFile(alice, sessionId, "notes.txt", "A 写的");

        HttpResponse<String> merged = alice.post("/api/sessions/" + sessionId.value() + "/merge", "{}");

        assertThat(merged.statusCode()).isEqualTo(200);
        // 断言主干工作区里真的有该文件，而不是接口返回 200 ——
        // 后者只说明调用了 git，不能说明结果落到了正确位置
        assertThat(mainFile(projectId, "notes.txt")).isEqualTo("A 写的");
        assertThat(body(merged).get("mergeCommitSha").asText()).isNotBlank();

        // 项目里没有可识别的构建文件，因此不做验证，而不是硬跑一条可能出错的命令
        assertThat(body(merged).has("verification")).isFalse();
    }

    @Test
    @DisplayName("【小步合并】合到某个 checkpoint —— 主干只拿到那一步的产出，后面的没进去")
    void mergingToACheckpointStopsThere() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);

        writeFile(alice, sessionId, "first.txt", "第一步");
        // 下标 1 而不是 0：0 号是建会话时的基点，尚未做任何事，合它是空操作
        String firstCheckpoint = checkpoints(alice, sessionId).get(1).get("commitSha").asText();
        writeFile(alice, sessionId, "second.txt", "第二步");

        HttpResponse<String> merged = alice.post("/api/sessions/" + sessionId.value() + "/merge",
                "{\"toCommitSha\":\"%s\"}".formatted(firstCheckpoint));

        assertThat(merged.statusCode()).isEqualTo(200);
        assertThat(mainFile(projectId, "first.txt")).isEqualTo("第一步");
        // 第二步不应在主干上，这正是小步合并的意义：一轮一个 checkpoint，做一段合一段，
        // 而不是攒完整个会话再面对全部冲突
        assertThat(Files.exists(REPOS_ROOT.resolve(projectId.value()).resolve("second.txt")))
                .as("合到第一个 checkpoint 之后，第二步的产出不该进主干")
                .isFalse();
    }

    @Test
    @DisplayName("【安全】只接受这条会话自己打过的 checkpoint —— 别人的、瞎编的一律 400")
    void onlyThisSessionsOwnCheckpointsCanBeMerged() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId mine = createSession(alice, projectId);
        SessionId other = createSession(alice, projectId);

        writeFile(alice, mine, "a.txt", "我的");
        writeFile(alice, other, "b.txt", "另一条会话的");
        String othersCheckpoint = checkpoints(alice, other).get(1).get("commitSha").asText();

        // 不校验的话，git merge SHA 会把该提交及其全部祖先搬进主干，
        // 等于让调用方决定写入哪段历史
        assertThat(mergeTo(alice, mine, othersCheckpoint).statusCode())
                .as("别人的 checkpoint 不属于「我这条会话的产出」")
                .isEqualTo(400);
        assertThat(mergeTo(alice, mine, "0000000000000000000000000000000000000000").statusCode())
                .isEqualTo(400);
    }

    private HttpResponse<String> mergeTo(TestBrowser owner, SessionId sessionId, String sha)
            throws Exception {
        return owner.post("/api/sessions/" + sessionId.value() + "/merge",
                "{\"toCommitSha\":\"%s\"}".formatted(sha));
    }

    // ------------------------------------------------------------------
    // 同步：主干 → 会话工作区（合并的反方向）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【同步】对方合进主干的改动，同步之后才进我的工作区 —— 在那之前是看不见的")
    void syncingPullsMainIntoTheSessionWorktree() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        // 两个成员各一棵树，都从同一个基点分出，此刻两边都还没有 a.txt。
        // 必须真的换一个人：树挂在「人 × 项目」上（见 WorkspaceId），同一人的两条会话
        // 共用一棵树，"我看不见对方写的"在它们之间不成立，而那正是本测试要验证的
        TestBrowser bob = join(alice, projectId, "bob");
        SessionId mine = createSession(alice, projectId);
        SessionId theirs = createSession(bob, projectId);

        writeFile(bob, theirs, "a.txt", "对方写的");
        assertThat(bob.post("/api/sessions/" + theirs.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);

        // 主干已有该文件，但我的工作区里还没有：agent 读不到，因此不知道对方已经创建了它
        assertThat(sessionFile(mine, "a.txt")).isNull();

        HttpResponse<String> synced = alice.post("/api/sessions/" + mine.value() + "/sync", "{}");

        assertThat(synced.statusCode()).isEqualTo(200);
        assertThat(body(synced).get("status").asText()).isEqualTo("FAST_FORWARD");
        assertThat(sessionFile(mine, "a.txt")).isEqualTo("对方写的");
        // head_commit 列必须跟着更新：事件与列不同步会让回滚、checkpoint、
        // 下一次合并都按错误位置计算，且不会报错
        assertThat(body(alice.get("/api/sessions/" + mine.value())).get("headCommit").asText())
                .isEqualTo(body(synced).get("toHead").asText());
    }

    @Test
    @DisplayName("【同步】主干没前进时是空操作，而且【不落事件】")
    void syncingWhenNothingChangedRecordsNothing() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");
        SessionId mine = createSession(alice, projectId);
        SessionId other = createSession(bob, projectId);

        writeFile(bob, other, "a.txt", "x");
        bob.post("/api/sessions/" + other.value() + "/merge", "{}");
        assertThat(body(alice.post("/api/sessions/" + mine.value() + "/sync", "{}"))
                .get("status").asText()).isEqualTo("FAST_FORWARD");

        // 主干没再动，第二次同步什么都做不了
        HttpResponse<String> again = alice.post("/api/sessions/" + mine.value() + "/sync", "{}");
        assertThat(body(again).get("status").asText()).isEqualTo("UP_TO_DATE");

        // 事件流里只该有一条：没有变化的事实不进事件流，否则回放时无法与真实同步区分
        assertThat(events(alice, mine, 0)).filteredOn(node -> "SESSION_SYNCED".equals(node.get("type").asText()))
                .singleElement();
    }

    @Test
    @DisplayName("【同步冲突】冲突留在会话的工作区里，裁决端点【自己找得到它】，而主干全程干净")
    void syncConflictsAreResolvedInTheSessionWorktree() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");
        SessionId mine = createSession(alice, projectId);
        SessionId other = createSession(bob, projectId);

        writeFile(bob, other, "a.txt", "对方写的");
        bob.post("/api/sessions/" + other.value() + "/merge", "{}");

        // 我这边也从同一个空基线新建同名文件，同步时形成 add/add 冲突
        writeFile(alice, mine, "a.txt", "我写的");

        assertThat(alice.post("/api/sessions/" + mine.value() + "/sync", "{}").statusCode())
                .isEqualTo(409);

        // 冲突未收尾时，同步和合并都不该是 500。git 不允许同一工作区有两个未完成的合并，
        // 第二次 merge 会以非零退出；不翻译时客户端只看到"请求失败（HTTP 500）"，
        // 把"你还有一步没做"说成"服务器坏了"。两条都必须返回 409 并说明原因
        HttpResponse<String> syncAgain = alice.post(
                "/api/sessions/" + mine.value() + "/sync", "{}");
        assertThat(syncAgain.statusCode()).isEqualTo(409);
        assertThat(syncAgain.body()).contains("还没收尾");

        HttpResponse<String> mergeWhilePending = alice.post(
                "/api/sessions/" + mine.value() + "/merge", "{}");
        assertThat(mergeWhilePending.statusCode()).isEqualTo(409);
        assertThat(mergeWhilePending.body()).contains("还没收尾");

        // 裁决端点不需要知道冲突在哪块工作区，它自己查找；
        // 客户端也不该记"在哪"，那由后端回答
        JsonNode envelope = conflictsEnvelope(alice, mine);
        // 方向随冲突一起返回，界面据此说明"你现在在做什么"
        assertThat(envelope.get("direction").asText()).isEqualTo("INTO_SESSION");
        assertThat(conflicts(alice, mine)).singleElement().satisfies(conflict -> {
            assertThat(conflict.get("path").asText()).isEqualTo("a.txt");
            // 字段按"是哪一边"命名，不按 git 的 ours/theirs，因此两种方向下含义完全相同，
            // 前端不需要翻转（本例 ours 是会话侧，合回主干时 ours 是主干侧）
            assertThat(conflict.get("sessionSide").asText()).isEqualTo("我写的");
            assertThat(conflict.get("mainSide").asText()).isEqualTo("对方写的");
        });

        // 裁决：两边都留 —— 两个人各加了一样东西时，这是最常见的那个正确答案
        HttpResponse<String> resolved = alice.post(
                "/api/sessions/" + mine.value() + "/conflicts/resolve",
                json.writeValueAsString(Map.of("path", "a.txt", "content", "对方写的\n我写的\n")));

        assertThat(resolved.statusCode()).isEqualTo(200);
        assertThat(sessionFile(mine, "a.txt")).isEqualTo("对方写的\n我写的\n");
        // 收尾的合并推进的是会话的 HEAD，因此 head_commit 必须更新；
        // 不更新会让回滚、checkpoint 和下一次合并按错误位置计算，且不会报错
        assertThat(body(alice.get("/api/sessions/" + mine.value())).get("headCommit").asText())
                .isEqualTo(body(resolved).get("mergeCommitSha").asText());
        // 主干全程未被这次冲突触及：冲突在分支上解决，主干保持干净
        assertThat(mainFile(projectId, "a.txt")).isEqualTo("对方写的");
    }

    @Test
    @DisplayName("【同步冲突·按侧裁决】side=session 取到的是**会话**那份 —— 换成同步方向后这句话不变")
    void resolvingASyncConflictBySessionSideIsNotFlipped() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");
        SessionId mine = createSession(alice, projectId);
        SessionId other = createSession(bob, projectId);

        writeFile(bob, other, "a.txt", "对方写的");
        bob.post("/api/sessions/" + other.value() + "/merge", "{}");
        writeFile(alice, mine, "a.txt", "我写的");
        assertThat(alice.post("/api/sessions/" + mine.value() + "/sync", "{}").statusCode())
                .isEqualTo(409);

        // 这条盯的是 Side → ours 映射（整个代码库只在这里翻一次）。本次冲突中 git 把
        // 会话侧叫 ours（当前分支即会话分支）；方向判反时会取到主干那份，
        // "我写的东西"会被静默丢掉，而调用方看到的是合并成功
        HttpResponse<String> resolved = alice.post(
                "/api/sessions/" + mine.value() + "/conflicts/resolve",
                "{\"path\":\"a.txt\",\"side\":\"session\"}");

        assertThat(resolved.statusCode()).isEqualTo(200);
        assertThat(sessionFile(mine, "a.txt")).isEqualTo("我写的");
        // 而主干那份没被动过 —— 同步从不写主干
        assertThat(mainFile(projectId, "a.txt")).isEqualTo("对方写的");
    }

    /**
     * 会话工作区里项目根下的一个文件；不存在时返回 null。
     *
     * <p>必须从项目根起算：工作区根下面还有一层项目目录（见
     * {@link com.codeloom.app.project.ProjectLayout}）。用工作区根拼会指向不存在的路径，
     * 而 {@code Files.exists} 对不存在路径返回 false，断言会安静地变成"什么都没验"。
     */
    private String sessionFile(SessionId sessionId, String name) throws IOException {
        Path file = ProjectLayout.rootBelow(worktreeOf(sessionId)).resolve(name);
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    /**
     * 这条会话所在那棵树的工作区目录。树挂在「人 × 项目」上，两半都要从库里的会话行取。
     *
     * <p>目录布局由 {@link LocalWorkspaceManager} 决定，这里问它而不是自己拼：拼错时
     * {@code Files.exists} 对不存在路径返回 false，断言会安静地什么都不验。
     */
    private Path worktreeOf(SessionId sessionId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT owner_id, project_id FROM session WHERE id = ?", sessionId.value());
        WorkspaceId id = WorkspaceId.of(UserId.of((String) row.get("owner_id")),
                ProjectId.of((String) row.get("project_id")));
        return new LocalWorkspaceManager(git, WORKSPACES_ROOT).worktreePath(id);
    }

    @Test
    @DisplayName("【合并后验证】主干构建挂了要响亮地说出来，而不是让人以为一切都好")
    void theMergedMainIsVerified() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);

        // 刻意放一个坏的 pom.xml，mvn 会立即报 "modelVersion is missing"。
        // 这里要验证的正是失败会被说出来：合并是最容易出问题的路径——
        // 两人各自的改动都对、合起来编译不过，而 git 检测不到
        writeFile(alice, sessionId, "pom.xml", "<project></project>");

        HttpResponse<String> merged = alice.post("/api/sessions/" + sessionId.value() + "/merge", "{}");

        assertThat(merged.statusCode()).isEqualTo(200);
        JsonNode outcome = body(merged);
        String mergeCommit = outcome.get("mergeCommitSha").asText();

        JsonNode verification = outcome.get("verification");
        assertThat(verification).isNotNull();
        // 验证未通过仍是 200：合并本身成功，验证说的是另一件事。
        // 改成错误码会让人以为合并没做成而重试，而代码已经在主干上
        assertThat(verification.get("passed").asBoolean()).isFalse();
        assertThat(verification.get("command").asText()).contains("mvn");
        // callId 带被验证的 sha：构建期间主干可能被另一次合并推进，
        // 这是事后分辨验的是哪一份的唯一线索
        assertThat(verification.get("callId").asText())
                .startsWith("merge-verify:")
                .contains(mergeCommit.substring(0, 7));

        // 结论也落了库，它是"主干现在是什么状态"的证据链
        assertThat(events(alice, sessionId, 0)).anySatisfy(event ->
                assertThat(event.get("type").asText()).isEqualTo("VERIFICATION_RESULT"));
    }

    @Test
    @DisplayName("冲突：停下来给清单，裁决完自动收尾 —— 两侧内容一起给")
    void conflictsAreHandedOverForAHumanDecision() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser bob = register("bob");
        ProjectId projectId = createProject(alice);
        addMember(alice, projectId, bob);
        // 两条会话都在 A 合并之前创建，都从空基点分出；各自新建同名文件即构成
        // 最典型的 add/add 冲突
        SessionId alices = createSession(alice, projectId);
        SessionId bobs = createSession(bob, projectId);

        writeFile(alice, alices, "notes.txt", "A 写的");
        assertThat(alice.post("/api/sessions/" + alices.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);

        writeFile(bob, bobs, "notes.txt", "B 写的");

        HttpResponse<String> conflicted = bob.post("/api/sessions/" + bobs.value() + "/merge", "{}");

        // 409 而不是 200：这不是一次成功的合并，客户端必须据此进入裁决流程
        assertThat(conflicted.statusCode()).isEqualTo(409);
        // 冲突清单跟着 409 一起回来，客户端不用再问一次
        assertThat(conflicted.body()).contains("notes.txt");

        // 合并会先同步（先同步再合，冲突才不会撞在主干上），
        // 因此冲突首先发生在同步那一步，方向是 INTO_SESSION 而不是 INTO_MAIN
        assertThat(conflictsEnvelope(bob, bobs).get("direction").asText()).isEqualTo("INTO_SESSION");
        assertThat(conflicts(bob, bobs)).singleElement().satisfies(conflict -> {
            assertThat(conflict.get("path").asText()).isEqualTo("notes.txt");
            // 裁决需要看到两侧内容；这两个值的含义与同步那条测试完全相同：
            // sessionSide 恒为会话那份，mainSide 恒为主干那份
            assertThat(conflict.get("sessionSide").asText()).isEqualTo("B 写的");
            assertThat(conflict.get("mainSide").asText()).isEqualTo("A 写的");
        });

        // 用会话侧裁决；这是最后一个冲突，因此同步那一步自动收尾
        HttpResponse<String> resolved = bob.post(
                "/api/sessions/" + bobs.value() + "/conflicts/resolve", """
                        {"path":"notes.txt","side":"session"}
                        """);

        assertThat(resolved.statusCode()).isEqualTo(200);
        assertThat(body(resolved).get("status").asText()).isEqualTo("MERGED");
        // 收尾的是同步：会话工作区里已有 B 那份，而主干还没被碰过
        assertThat(mainFile(projectId, "notes.txt")).isEqualTo("A 写的");

        // 所以还得再点一次合并 —— 这一次分支已经含了主干，是快进，不会再撞。
        // 前端把这两下串起来就还是一个按钮（先合并 → 有冲突就裁决 → 再合并）
        assertThat(bob.post("/api/sessions/" + bobs.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);
        assertThat(mainFile(projectId, "notes.txt")).isEqualTo("B 写的");
    }

    @Test
    @DisplayName("裁决做到一半反悔：放弃合并，主干回到合并前")
    void abortingAMergeRestoresMain() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser bob = register("bob");
        ProjectId projectId = createProject(alice);
        addMember(alice, projectId, bob);
        SessionId alices = createSession(alice, projectId);
        SessionId bobs = createSession(bob, projectId);

        writeFile(alice, alices, "notes.txt", "A 写的");
        // 第一步必须成功，否则"主干是 A 写的"这个前提不成立
        assertThat(alice.post("/api/sessions/" + alices.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);
        writeFile(bob, bobs, "notes.txt", "B 写的");
        // 第二步必须冲突（409），否则"合并做到一半反悔"根本没有发生；
        // abort 仍会返回 204、主干仍是 A 写的，测试会在没走目标路径时通过
        assertThat(bob.post("/api/sessions/" + bobs.value() + "/merge", "{}").statusCode())
                .isEqualTo(409);

        HttpResponse<String> aborted = bob.post("/api/sessions/" + bobs.value() + "/merge/abort", "{}");

        assertThat(aborted.statusCode()).isEqualTo(204);
        // 主干回到合并前，且不留合并状态；否则下一次合并会以"已经在合并中"失败
        assertThat(mainFile(projectId, "notes.txt")).isEqualTo("A 写的");
        assertThat(conflicts(bob, bobs)).isEmpty();
    }

    @Test
    @DisplayName("【场景①】两人各加一个方法 → 冲突 → 给出合并后的内容，两个方法都留下")
    void bothAddedMethodsCanBeKept() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser bob = register("bob");
        ProjectId projectId = createProject(alice);
        // 先在主干放一个 main.java，让两人的改动都是"在同一个已有文件里添加内容"，
        // 这正是"各加一个方法"的场景
        SessionId alices = createSession(alice, projectId);
        writeFile(alice, alices, "main.java", "class Main {\n    // methods\n}\n");
        assertThat(alice.post("/api/sessions/" + alices.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);

        // B 在主干已有该文件之后才加入，因此他的树从这个点分出，基点里就带着它。
        // 加入时机在这里有意义：树在拿到项目访问权时从当时的主干分出
        //（见 WorkspaceProvisioner）；之后别人合进主干的内容需要同步才看得到
        addMember(alice, projectId, bob);
        SessionId bobs = createSession(bob, projectId);

        // 两个人各自在同一个位置加一个方法 —— 改到的是同一行区域
        editFile(alice, alices, "main.java", "    // methods",
                "    // methods\\n    void printHello() {}");
        editFile(bob, bobs, "main.java", "    // methods",
                "    // methods\\n    void printHi() {}");

        assertThat(bob.post("/api/sessions/" + bobs.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);

        // A 后合：两边都在同一个位置插了不同的行 → 冲突（git 只按行判，这正是它该做的）
        HttpResponse<String> conflicted = alice.post(
                "/api/sessions/" + alices.value() + "/merge", "{}");
        assertThat(conflicted.statusCode()).isEqualTo(409);

        // 裁决的关键步骤：取任一侧都会丢掉一个人的方法，正确答案是两份都留，
        // 因此由人给出裁决后的全文
        HttpResponse<String> resolved = alice.post(
                "/api/sessions/" + alices.value() + "/conflicts/resolve", """
                        {"path":"main.java","content":"class Main {\\n    // methods\\n    void printHello() {}\\n    void printHi() {}\\n}\\n"}
                        """);

        assertThat(resolved.statusCode()).isEqualTo(200);
        assertThat(body(resolved).get("status").asText()).isEqualTo("MERGED");
        // 裁决后会话工作区里应是人给的那份
        assertThat(sessionFile(alices, "main.java"))
                .as("裁决的内容应该落在会话的工作区里")
                .contains("printHello");
        // 收尾的是同步，主干还没动：仍是 bob 合进去的那份，有 printHi 而没有 printHello
        assertThat(mainFile(projectId, "main.java")).doesNotContain("printHello");
        // 再合一次才落地 —— 这时已经是快进
        assertThat(alice.post("/api/sessions/" + alices.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);
        assertThat(mainFile(projectId, "main.java"))
                .contains("void printHello() {}")
                .contains("void printHi() {}");
    }

    @Test
    @DisplayName("【场景②】两人把同一行改成不同的东西 → 绝不自动合并，交给人")
    void theSameLineChangedDifferentlyIsNeverAutoMerged() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser bob = register("bob");
        ProjectId projectId = createProject(alice);

        SessionId alices = createSession(alice, projectId);
        writeFile(alice, alices, "main.java", "class Main {\n    // hello\n}\n");
        // 这是布景：B 的树必须建立在"A 的版本已在主干上"的前提下。
        // 不校验的话，布景失败时后面验证的是完全不同的场景
        assertThat(alice.post("/api/sessions/" + alices.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);

        // B 在布景之后加入，因此他的树从带着 main.java 的那个点分出
        addMember(alice, projectId, bob);
        SessionId bobs = createSession(bob, projectId);

        editFile(alice, alices, "main.java", "    // hello", "    // hi");
        editFile(bob, bobs, "main.java", "    // hello", "    // hehe");

        assertThat(bob.post("/api/sessions/" + bobs.value() + "/merge", "{}").statusCode())
                .isEqualTo(200);

        // 两边对同一行的意图不同，机器无法判断，必须停下由人决定。
        // 这条断言的正是"绝不自动合并"：git 不会自行挑一个
        HttpResponse<String> conflicted = alice.post(
                "/api/sessions/" + alices.value() + "/merge", "{}");
        assertThat(conflicted.statusCode()).isEqualTo(409);

        // 两侧差异原样展示，让人看清分歧在哪一行
        assertThat(conflicts(alice, alices)).singleElement().satisfies(conflict -> {
            assertThat(conflict.get("mainSide").asText()).contains("// hehe");
            assertThat(conflict.get("sessionSide").asText()).contains("// hi");
        });

        // 人决定用哪一份（这里也可以是各取一半的内容 —— 接口两种都收）
        HttpResponse<String> resolved = alice.post(
                "/api/sessions/" + alices.value() + "/conflicts/resolve", """
                        {"path":"main.java","content":"class Main {\\n    // hehe\\n}\\n"}
                        """);

        assertThat(body(resolved).get("status").asText()).isEqualTo("MERGED");
        assertThat(mainFile(projectId, "main.java")).contains("// hehe").doesNotContain("// hi");
    }

    @Test
    @DisplayName("裁决必须明确：两个参数都不给 → 400，而不是默默替人取一侧")
    void resolutionMustBeExplicit() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);

        // side 和 content 都没给。不能默认取一侧：那会让另一个人的代码无声消失，
        // 而当事人只会看到"合并成功了"
        HttpResponse<String> vague = alice.post(
                "/api/sessions/" + sessionId.value() + "/conflicts/resolve",
                "{\"path\":\"main.java\"}");

        assertThat(vague.statusCode()).isEqualTo(400);
        assertThat(vague.body()).contains("side").contains("content");

        // 参数正确但当前没有待裁决的合并时，返回 409
        HttpResponse<String> nothingPending = alice.post(
                "/api/sessions/" + sessionId.value() + "/conflicts/resolve",
                "{\"path\":\"main.java\",\"side\":\"session\"}");

        assertThat(nothingPending.statusCode()).isEqualTo(409);
        assertThat(nothingPending.body()).contains("没有待裁决的合并");

        // 旧的 git 词汇不再接受：它随合并方向反转而有歧义；拒绝而非兼容，避免陷阱回归
        assertThat(alice.post("/api/sessions/" + sessionId.value() + "/conflicts/resolve",
                "{\"path\":\"main.java\",\"side\":\"ours\"}").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("【安全】只能合并自己的会话 —— 替队友决定"
            + "「这些工作可以进主干了吗」不该发生")
    void onlyTheOwnerCanMergeTheirSession() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser bob = register("bob");
        ProjectId projectId = createProject(alice);
        addMember(alice, projectId, bob);
        SessionId alices = createSession(alice, projectId);

        assertThat(bob.post("/api/sessions/" + alices.value() + "/merge", "{}").statusCode())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // 安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【安全】不是成员的项目 → 404，不是 403")
    void foreignProjectsLookLikeTheyDoNotExist() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser stranger = register("stranger");
        ProjectId projectId = createProject(alice);

        // 403 等于承认"存在但不给你看"，拿一批 id 试一遍就能枚举出系统里有哪些项目；
        // 因此对外一律装作不存在
        assertThat(stranger.get("/api/projects/" + projectId.value()).statusCode()).isEqualTo(404);
        assertThat(stranger.get("/api/projects/" + projectId.value() + "/sessions").statusCode())
                .isEqualTo(404);
        assertThat(stranger.post("/api/projects/" + projectId.value() + "/sessions", """
                {"provider":"deepseek","modelId":"deepseek-chat"}
                """).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("【安全】队友看得见我的会话，但驱动不了 → 403")
    void teammatesCanWatchButNotDrive() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser bob = register("bob");
        ProjectId projectId = createProject(alice);
        SessionId alices = createSession(alice, projectId);
        addMember(alice, projectId, bob);

        // 看得见：实时观战的前提是队友能看到你的会话
        assertThat(bob.get("/api/sessions/" + alices.value()).statusCode()).isEqualTo(200);
        assertThat(bob.get("/api/projects/" + projectId.value() + "/sessions").body())
                .contains(alices.value());

        // 但驱动不了。返回 403 而不是 404：他是项目成员且能看到会话列表，
        // "这东西存在"不是秘密，403 才符合事实
        model.script(ScriptedLlm.answer("不该跑到这里"));
        HttpResponse<String> denied = bob.post("/api/sessions/" + alices.value() + "/messages", """
                {"text":"我来替你改"}
                """);

        assertThat(denied.statusCode()).isEqualTo(403);
        // 事件流里没有写入：403 之后不应留下痕迹
        assertThat(events.readAll(alices)).noneSatisfy(stored ->
                assertThat(stored.event()).isEqualTo(new UserMessage("我来替你改")));
    }

    @Test
    @DisplayName("【安全】不是成员连会话都看不见，哪怕知道它的 id")
    void strangersCannotSeeSessionsAtAll() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser stranger = register("stranger");
        ProjectId projectId = createProject(alice);
        SessionId alices = createSession(alice, projectId);

        assertThat(stranger.get("/api/sessions/" + alices.value()).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // 退出项目
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【退出】他名下的会话、事件、工作区、磁盘上那棵树一起走 —— 项目还在，聊天也还在")
    void leavingTakesOnlyTheirOwnThings() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");
        SessionId hers = createSession(alice, projectId);
        SessionId his = createSession(bob, projectId);

        // 两人各真正跑一轮，使会话有事件、幂等键和磁盘文件
        writeFile(alice, hers, "a.txt", "A 写的");
        writeFile(bob, his, "b.txt", "B 写的");

        UserId bobId = ownerOf(his);
        UserId aliceId = ownerOf(hers);
        Path hisTree = treeOf(bobId, projectId);
        Path herTree = treeOf(aliceId, projectId);
        assertThat(hisTree).exists();
        assertThat(herTree).exists();
        chat.append(projectId, bobId, "说一句", null, null, Instant.now());

        assertThat(bob.delete("/api/projects/" + projectId.value() + "/members/me").statusCode())
                .isEqualTo(204);

        // 他的三样都没了。必须按会话 id 查而不是按项目查：会话行删除后按项目的子查询恒为 0，
        // 那样即使事件一条没删断言也会通过
        assertThat(count("SELECT COUNT(*) FROM session WHERE id = ?", his.value())).isZero();
        assertThat(count("SELECT COUNT(*) FROM `event` WHERE session_id = ?", his.value())).isZero();
        assertThat(count("SELECT COUNT(*) FROM turn_request WHERE session_id = ?", his.value())).isZero();
        assertThat(count("SELECT COUNT(*) FROM workspace WHERE owner_id = ? AND project_id = ?",
                bobId.value(), projectId.value())).isZero();
        assertThat(hisTree).doesNotExist();

        // 她的三样不受影响
        assertThat(count("SELECT COUNT(*) FROM session WHERE id = ?", hers.value())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM `event` WHERE session_id = ?", hers.value()))
                .isPositive();
        assertThat(herTree).exists();

        // 项目级数据保留：聊天记录挂在项目上，项目还在，那是两人共有的地方
        assertThat(chat.findRecent(projectId, 10)).hasSize(1);

        // 项目仍在，房主未变
        JsonNode row = projectRowOf(alice, projectId);
        assertThat(row).isNotNull();
        assertThat(row.get("ownerId").asText()).isEqualTo(aliceId.value());
        assertThat(projectRowOf(bob, projectId)).isNull();   // 他自己的列表里没了
    }

    @Test
    @DisplayName("【退出·房主易主】房主退出后项目还在，接手的是剩下那个人")
    void theProjectPassesToTheOtherMember() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");

        Path herTree = treeOf(ownerOf(projectId), projectId);
        assertThat(herTree).exists();

        assertThat(alice.delete("/api/projects/" + projectId.value() + "/members/me").statusCode())
                .isEqualTo(204);

        // 她那边已不可见；他那边仍可见，且他成为房主
        assertThat(projectRowOf(alice, projectId)).isNull();
        JsonNode row = projectRowOf(bob, projectId);
        assertThat(row).isNotNull();
        assertThat(row.get("ownerId").asText())
                .isEqualTo(row.get("members").get(0).get("id").asText());
        assertThat(herTree).doesNotExist();
    }

    @Test
    @DisplayName("【退出·最后一个人】项目真的消失：行、仓库目录、聊天记录、邀请全都清掉")
    void theLastOneOutTurnsOffTheLights() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");
        SessionId hers = createSession(alice, projectId);
        SessionId his = createSession(bob, projectId);

        writeFile(alice, hers, "notes.txt", "A 写的");
        writeFile(bob, his, "b.txt", "B 写的");
        UserId aliceId = ownerOf(hers);
        chat.append(projectId, aliceId, "说一句", null, null, Instant.now());
        assertThat(alice.post("/api/projects/" + projectId.value() + "/invitations", "{}").statusCode())
                .isEqualTo(201);

        Path repo = REPOS_ROOT.resolve(projectId.value());
        Path herTree = treeOf(aliceId, projectId);
        // 两条会话的主人分别才是两个人：房主此刻仍是 alice，用 ownerOf(projectId) 算
        // bob 的树会得到同一棵树，"他那棵没了"就永远为真，什么也没验到
        Path hisTree = treeOf(ownerOf(his), projectId);
        String leave = "/api/projects/" + projectId.value() + "/members/me";
        assertThat(herTree).exists();
        assertThat(hisTree).exists();

        // bob 先走：项目还在，只是他那些东西跟着走了
        assertThat(bob.delete(leave).statusCode()).isEqualTo(204);
        assertThat(repo).exists();
        assertThat(hisTree).doesNotExist();

        // 最后一个人退出，项目从此不存在
        assertThat(alice.delete(leave).statusCode()).isEqualTo(204);

        // 项目行，以及挂在项目上、不属于任何个人的聊天和邀请
        assertThat(count("SELECT COUNT(*) FROM project WHERE id = ?", projectId.value())).isZero();
        assertThat(count("SELECT COUNT(*) FROM project_member WHERE project_id = ?", projectId.value()))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_message WHERE project_id = ?", projectId.value()))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM project_invitation WHERE project_id = ?",
                projectId.value())).isZero();
        // 以及她自己的数据
        assertThat(count("SELECT COUNT(*) FROM session WHERE id = ?", hers.value())).isZero();
        assertThat(count("SELECT COUNT(*) FROM `event` WHERE session_id = ?", hers.value())).isZero();
        assertThat(count("SELECT COUNT(*) FROM turn_request WHERE session_id = ?", hers.value()))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM workspace WHERE project_id = ?", projectId.value()))
                .isZero();

        // 磁盘上：仓库和最后一棵树也不在了（树是仓库的兄弟目录，删仓库带不走它）
        assertThat(repo).doesNotExist();
        assertThat(herTree).doesNotExist();
        assertThat(projectRowOf(alice, projectId)).isNull();
    }

    @Test
    @DisplayName("【退出·权限】不是成员的人看到的只有 404 —— 装作这个项目不存在")
    void strangersCannotLeave() throws Exception {
        TestBrowser alice = register("alice");
        TestBrowser stranger = register("stranger");
        ProjectId projectId = createProject(alice);

        assertThat(stranger.delete(
                "/api/projects/" + projectId.value() + "/members/me").statusCode())
                .isEqualTo(404);

        // 拒绝意味着什么都没发生
        assertThat(alice.get("/api/projects/" + projectId.value()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("【退出】有人在跑的时候是 409 —— 不然那一轮收尾会把刚删掉的行写回来")
    void leavingWhileRunningIsRefused() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        SessionId sessionId = createSession(alice, projectId);

        // 直接占住这棵树的租约，模拟有一轮正在运行（与丢弃测试同一造法）
        Session session = sessions.findById(sessionId).orElseThrow();
        LeaseToken held = leases.tryAcquire(session).orElseThrow();
        try {
            assertThat(alice.delete(
                    "/api/projects/" + projectId.value() + "/members/me").statusCode())
                    .isEqualTo(409);

            // 拒绝 = 什么都没发生：项目还在、会话还在、他的还是他的
            assertThat(alice.get("/api/projects/" + projectId.value()).statusCode()).isEqualTo(200);
            assertThat(alice.get("/api/sessions/" + sessionId.value()).statusCode()).isEqualTo(200);
        } finally {
            leases.release(held);
        }
    }

    // ------------------------------------------------------------------
    // 手改文件：新建 / 改名 / 删除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【新建】文件真的落到工作区里，而且列得出来")
    void creatingAFileLands() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        Path tree = treeOf(ownerOf(projectId), projectId);

        assertThat(alice.post("/api/projects/" + projectId.value() + "/files",
                "{\"path\":\"Hello.java\",\"directory\":false}").statusCode())
                .isEqualTo(201);

        // 判据是磁盘上确实有它，而不只是接口返回 201
        assertThat(tree.resolve("Hello.java")).hasContent("");
        assertThat(alice.get("/api/projects/" + projectId.value() + "/files?path=").body())
                .contains("Hello.java");

        assertThat(alice.post("/api/projects/" + projectId.value() + "/files",
                "{\"path\":\"pkg\",\"directory\":true}").statusCode())
                .isEqualTo(201);
        assertThat(tree.resolve("pkg")).isDirectory();
    }

    @Test
    @DisplayName("【新建】已经有了就是 409，而且绝不覆盖 —— 那是这个界面能造成的最坏后果")
    void creatingOverSomethingIsRefused() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        String url = "/api/projects/" + projectId.value() + "/files";
        Path tree = treeOf(ownerOf(projectId), projectId);

        assertThat(alice.post(url, "{\"path\":\"a.txt\",\"directory\":false}")
                .statusCode()).isEqualTo(201);

        assertThat(alice.post(url, "{\"path\":\"a.txt\",\"directory\":false}")
                .statusCode()).isEqualTo(409);
        // 目录也一样：拿一个目录去顶一个目录
        assertThat(alice.post(url, "{\"path\":\"pkg\",\"directory\":true}")
                .statusCode()).isEqualTo(201);
        assertThat(alice.post(url, "{\"path\":\"pkg\",\"directory\":true}")
                .statusCode()).isEqualTo(409);

        assertThat(tree.resolve("a.txt")).exists();
    }

    @Test
    @DisplayName("【新建】上一级目录不在 → 409，而不是顺手建出一串中间目录")
    void creatingIntoNowhereIsRefused() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        Path tree = treeOf(ownerOf(projectId), projectId);

        assertThat(alice.post("/api/projects/" + projectId.value() + "/files",
                "{\"path\":\"nope/deep/a.txt\",\"directory\":false}").statusCode())
                .isEqualTo(409);

        // 不应建出任何东西：顺手创建中间目录是另一种产品决定，
        // 一次拼写错误就会留下一串空目录
        assertThat(tree.resolve("nope")).doesNotExist();
    }

    @Test
    @DisplayName("【改名】旧路径没了、新路径有，内容跟着走")
    void renamingMovesTheFile() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        Path tree = treeOf(ownerOf(projectId), projectId);
        writeFileTo(alice, projectId, "Old.java", "class Old {}\n");

        assertThat(alice.post("/api/projects/" + projectId.value() + "/files/move",
                "{\"from\":\"Old.java\",\"to\":\"New.java\"}").statusCode())
                .isEqualTo(200);

        assertThat(tree.resolve("Old.java")).doesNotExist();
        assertThat(tree.resolve("New.java"))
                .hasContent("class Old {}\n");
    }

    @Test
    @DisplayName("【删除】目录是整棵没的，而不是「删不掉，因为里面还有东西」")
    void deletingADirectoryTakesItsChildren() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        Path tree = treeOf(ownerOf(projectId), projectId);
        // 先建出该层目录：新建不会补中间目录（见 creatingIntoNowhereIsRefused）
        assertThat(alice.post("/api/projects/" + projectId.value() + "/files",
                "{\"path\":\"pkg\",\"directory\":true}").statusCode()).isEqualTo(201);
        writeFileTo(alice, projectId, "pkg/Deep.java", "class Deep {}\n");

        assertThat(alice.delete("/api/projects/" + projectId.value()
                + "/files?path=" + URLEncoder.encode("pkg", StandardCharsets.UTF_8))
                .statusCode())
                .isEqualTo(204);

        assertThat(tree.resolve("pkg")).doesNotExist();
        // 上一层还在 —— 删的是那一棵，不是它的父目录
        assertThat(tree.resolve(".")).isDirectory();
    }

    @Test
    @DisplayName("【越界】`..` 一律 400 —— 借道新建或改名都能写到工作区外面去")
    void escapingPathsAreRejected() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        String files = "/api/projects/" + projectId.value() + "/files";

        assertThat(alice.post(files, "{\"path\":\"../outside.txt\",\"directory\":false}")
                .statusCode()).isEqualTo(400);
        assertThat(alice.post(files + "/move",
                "{\"from\":\"README.md\",\"to\":\"../../outside\"}").statusCode()).isEqualTo(400);
        // 绝对路径同样不行
        assertThat(alice.post(files, "{\"path\":\"C:/Windows/System32/x.txt\",\"directory\":false}")
                .statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("【写】只能写自己那棵树 —— 请求体里塞一个 owner 也没用")
    void writesAlwaysLandInYourOwnTree() throws Exception {
        TestBrowser alice = register("alice");
        ProjectId projectId = createProject(alice);
        TestBrowser bob = join(alice, projectId, "bob");
        UserId bobsId = otherMember(alice, projectId);

        // 读接口认 owner（观战），因此"写接口不认"必须是固定事实，而不是"大家记得别传"。
        // 传了也当没传：文件落在 alice 自己的树里
        assertThat(alice.post("/api/projects/" + projectId.value() + "/files",
                "{\"path\":\"sneaky.txt\",\"directory\":false,\"owner\":\"" + bobsId.value() + "\"}")
                .statusCode()).isEqualTo(201);

        assertThat(treeOf(ownerOf(projectId), projectId).resolve("sneaky.txt")).exists();
        assertThat(treeOf(bobsId, projectId).resolve("sneaky.txt")).doesNotExist();

        // 他那边照样用自己那棵树 —— 没受影响
        assertThat(bob.get("/api/projects/" + projectId.value() + "/files?path=").statusCode())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // 输入校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("非法输入 → 400，而且把领域那句原话说出来")
    void invalidInputIsRejectedWithTheDomainsOwnMessage() throws Exception {
        TestBrowser alice = register("alice");

        HttpResponse<String> blankName = alice.post("/api/projects", """
                {"name":""}
                """);
        assertThat(blankName.statusCode()).isEqualTo(400);
        assertThat(blankName.body()).contains("项目名不能为空");

        // provider 形状不合法：领域（ProviderId）要求小写短标识；
        // 这里验证领域校验能一路冒泡成 400
        ProjectId projectId = createProject(alice);
        HttpResponse<String> badProvider = alice.post(
                "/api/projects/" + projectId.value() + "/sessions", """
                        {"provider":"DeepSeek!","modelId":"deepseek-chat"}
                        """);
        assertThat(badProvider.statusCode()).isEqualTo(400);
        assertThat(badProvider.body()).contains("provider");
    }

    @Test
    @DisplayName("未登录访问任何资源 → 401")
    void everythingRequiresLogin() throws Exception {
        TestBrowser anonymous = TestBrowser.at(port);

        assertThat(anonymous.get("/api/projects").statusCode()).isEqualTo(401);
        assertThat(anonymous.post("/api/projects", "{\"name\":\"x\"}").statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private void send(TestBrowser owner, SessionId sessionId, String text) throws Exception {
        HttpResponse<String> response = owner.post("/api/sessions/" + sessionId.value() + "/messages", """
                {"text":"%s"}
                """.formatted(text));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private List<JsonNode> events(TestBrowser owner, SessionId sessionId, long afterSeq) throws Exception {
        HttpResponse<String> response = owner.get(
                "/api/sessions/" + sessionId.value() + "/events?afterSeq=" + afterSeq);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readerForListOf(JsonNode.class).readValue(response.body());
    }

    /**
     * 事件流里的 {@code CHECKPOINT_CREATED}，按顺序。从事件流取而不是另设列表接口：
     * 界面也这么拿（见 RewindPicker），因此顺带钉住"界面和测试看到的是同一份位置"。
     */
    private List<JsonNode> checkpoints(TestBrowser owner, SessionId sessionId) throws Exception {
        return events(owner, sessionId, 0).stream()
                .filter(frame -> "CHECKPOINT_CREATED".equals(frame.get("type").asText()))
                .map(frame -> frame.get("payload"))
                .toList();
    }

    /**
     * 事件流里各 checkpoint 的 seq，按顺序；回滚目标就是其中之一。
     * seq 是事件在流里的位置，只有流知道；回滚送的就是它，界面也这么取（见 RewindPicker）。
     */
    private List<Long> checkpointSeqs(TestBrowser owner, SessionId sessionId) throws Exception {
        return events(owner, sessionId, 0).stream()
                .filter(frame -> "CHECKPOINT_CREATED".equals(frame.get("type").asText()))
                .map(frame -> frame.get("seq").asLong())
                .toList();
    }

    /**
     * 冲突响应信封 {@code {direction, conflicts}}。方向是整个合并的属性，
     * 放进每个文件会把同一信息复制 N 遍。
     */
    private JsonNode conflictsEnvelope(TestBrowser owner, SessionId sessionId) throws Exception {
        HttpResponse<String> response = owner.get(
                "/api/sessions/" + sessionId.value() + "/conflicts");
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    /** 待裁决的冲突文件（信封里的那个数组）。用 {@link #conflictsEnvelope} 拿方向。 */
    private List<JsonNode> conflicts(TestBrowser owner, SessionId sessionId) throws Exception {
        List<JsonNode> files = new ArrayList<>();
        conflictsEnvelope(owner, sessionId).get("conflicts").forEach(files::add);
        return files;
    }

    /**
     * 让 agent 在会话的工作区里写一个文件。
     *
     * <p>参数必须用 Jackson 构造，不手拼 JSON：手拼曾把多行内容的真实换行塞进字符串，
     * 工具报"参数不是合法 JSON"、文件没建出来，后续断言都跑在错误初始状态上。
     */
    private void writeFile(TestBrowser owner, SessionId sessionId, String path, String content) throws Exception {
        model.script(
                ScriptedLlm.toolCall("c1", "write_file",
                        json.writeValueAsString(Map.of("path", path, "content", content))),
                ScriptedLlm.answer("写好了"));
        send(owner, sessionId, "写一个 " + path);
        assertLastToolSucceeded(owner, sessionId);
    }

    /**
     * 让 agent 在会话的工作区里做一次精确替换；参数同样由 Jackson 构造。
     *
     * <p>脚本先读一次：{@code edit_file} 要求改之前观测过（见 ReadLedger），
     * 真实会话里模型也是先读后改，这里不绕过它。
     */
    private void editFile(TestBrowser owner, SessionId sessionId, String path,
                          String oldString, String newString) throws Exception {
        model.script(
                ScriptedLlm.toolCall("c0", "read_file",
                        json.writeValueAsString(Map.of("path", path))),
                ScriptedLlm.toolCall("c1", "edit_file",
                        json.writeValueAsString(Map.of(
                                "path", path, "old_string", oldString, "new_string", newString))),
                ScriptedLlm.answer("改好了"));
        send(owner, sessionId, "改 " + path);
        assertLastToolSucceeded(owner, sessionId);
    }

    /**
     * 断言本轮最后一个工具成功。辅助方法因此不会"默默什么都没做"：工具失败时只会在事件流里
     * 留下 {@code ToolResult(success=false)}，调用方看起来一切正常，失败会以很远处的
     * "合并竟然没冲突"出现。
     */
    private void assertLastToolSucceeded(TestBrowser owner, SessionId sessionId) throws Exception {
        List<JsonNode> results = events(owner, sessionId, 0).stream()
                .filter(event -> "TOOL_RESULT".equals(event.get("type").asText()))
                .toList();
        assertThat(results).isNotEmpty();
        JsonNode last = results.getLast().get("payload");
        assertThat(last.get("success").asBoolean())
                .as("工具没成功：%s", last.get("output").asText())
                .isTrue();
    }

    /** 主干那棵树项目根下的一个文件；从项目根起算，理由见 {@link #sessionFile}。 */
    private String mainFile(ProjectId projectId, String name) throws IOException {
        Path root = ProjectLayout.rootBelow(REPOS_ROOT.resolve(projectId.value()));
        return Files.readString(root.resolve(name), StandardCharsets.UTF_8);
    }

    private ProjectId createProject(TestBrowser owner) throws Exception {
        HttpResponse<String> created = owner.post("/api/projects", """
                {"name":"项目-%s"}
                """.formatted(UUID.randomUUID()));
        assertThat(created.statusCode()).isEqualTo(201);
        ProjectId id = ProjectId.of(body(created).get("id").asText());
        createdProjects.add(id);
        return id;
    }

    private SessionId createSession(TestBrowser owner, ProjectId projectId) throws Exception {
        HttpResponse<String> created = owner.post("/api/projects/" + projectId.value() + "/sessions", """
                {"provider":"deepseek","modelId":"deepseek-chat"}
                """);
        assertThat(created.statusCode()).isEqualTo(201);
        return SessionId.of(body(created).get("id").asText());
    }

    /**
     * 把一个人加进项目，走完整的邀请链接流程（生成 → 接受）。
     *
     * <p>不直接写库：那样得到的"两人都在项目里"是测试自造的状态，而真实路径
     *（生成链接、对方点开、接受）每一步都可能失败。这个辅助方法让那几步每次都真的跑一遍。
     */
    private void addMember(TestBrowser owner, ProjectId projectId, TestBrowser invitee) throws Exception {
        HttpResponse<String> created = owner.post(
                "/api/projects/" + projectId.value() + "/invitations", "{}");
        assertThat(created.statusCode()).isEqualTo(201);
        String token = body(created).get("token").asText();

        HttpResponse<String> accepted = invitee.post("/api/invitations/" + token + "/accept", "{}");
        assertThat(accepted.statusCode()).isEqualTo(200);
    }

    /**
     * 往项目里再加一个人，返回他的浏览器。
     *
     * <p>要造出互相看不见的两个工作区，只能造两个人：树挂在「人 × 项目」上，
     * 同一人的两条会话共用一棵树（见 {@code WorkspaceId}）。"对方写了什么我看不到"
     * 这类断言的前提正是两边各有一棵树。
     */
    private TestBrowser join(TestBrowser owner, ProjectId projectId, String name) throws Exception {
        TestBrowser peer = register(name);
        addMember(owner, projectId, peer);
        return peer;
    }

    /** 注册一个用户，并返回一个带着它的会话 cookie 的"浏览器"。 */
    private TestBrowser register(String prefix) throws Exception {
        String username = prefix + "-" + UUID.randomUUID();
        createdUsernames.add(username);
        TestBrowser browser = TestBrowser.at(port);
        browser.register(username);
        return browser;
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    /** 这个项目的房主；问库而不是从成员里猜，它决定退出后项目归谁。 */
    private UserId ownerOf(ProjectId projectId) {
        return UserId.of(jdbc.queryForObject(
                "SELECT owner_id FROM project WHERE id = ?", String.class, projectId.value()));
    }

    /** 一条会话的主人；问库要的是"这个 id 属于谁"，不是猜出来的顺序。 */
    private UserId ownerOf(SessionId sessionId) {
        return sessions.findById(sessionId).orElseThrow().ownerId();
    }

    /** 「我的项目」里的那一行；没有就返回 null。 */
    private JsonNode projectRowOf(TestBrowser who, ProjectId projectId) throws Exception {
        for (JsonNode row : body(who.get("/api/projects"))) {
            if (row.get("id").asText().equals(projectId.value())) {
                return row;
            }
        }
        return null;
    }

    /**
     * 这棵树的项目根；直接落文件的测试用它，因为文件接口也以它为准。
     * 要工作区根（例如断言 {@code .git} 位置）时用 {@link #worktreeOf(SessionId)}。
     */
    private Path treeOf(UserId owner, ProjectId projectId) {
        Path worktree = new LocalWorkspaceManager(git, WORKSPACES_ROOT)
                .worktreePath(WorkspaceId.of(owner, projectId));
        return ProjectLayout.rootBelow(worktree);
    }

    /**
     * 一条会话当前的状态；续跑是异步的，断言靠它轮询。
     *
     * <p>不抛受检异常是有意的：它要在 {@link Await#until} 的条件里使用，而条件不允许抛。
     * 读取失败（连接抖动、会话刚被删）返回空串，让轮询下一轮再试。
     */
    private String sessionState(TestBrowser who, SessionId sessionId) {
        try {
            return body(who.get("/api/sessions/" + sessionId.value())).get("state").asText();
        } catch (Exception e) {
            return "";
        }
    }

    /** 一条 COUNT 查询。 */
    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    /** 项目里不是房主的那个成员；用于断言"我写的东西没落到他那里"。 */
    private UserId otherMember(TestBrowser who, ProjectId projectId) throws Exception {
        UserId owner = ownerOf(projectId);
        for (JsonNode member : body(who.get("/api/projects/" + projectId.value())).get("members")) {
            UserId id = UserId.of(member.get("id").asText());
            if (!id.equals(owner)) {
                return id;
            }
        }
        throw new IllegalStateException("这个项目里没有第二个人");
    }

    /**
     * 在工作区里放一个带内容的文件。先走新建接口（只能建空文件），再直接写盘：
     * 要验证的是改名/删除后内容是否跟随，空文件验不出这一点。
     */
    private void writeFileTo(TestBrowser who, ProjectId projectId, String path, String content)
            throws Exception {
        assertThat(who.post("/api/projects/" + projectId.value() + "/files",
                json.writeValueAsString(Map.of("path", path, "directory", false))).statusCode())
                .isEqualTo(201);
        Files.writeString(treeOf(ownerOf(projectId), projectId).resolve(path), content,
                StandardCharsets.UTF_8);
    }

    @AfterEach
    void cleanUp() {
        // 真提交的数据必须自己清理（原因见类注释）。
        // 外键是刻意不建的，因此删除顺序只是习惯，不是约束
        for (ProjectId projectId : createdProjects) {
            jdbc.update("DELETE FROM `event` WHERE session_id IN "
                    + "(SELECT id FROM session WHERE project_id = ?)", projectId.value());
            jdbc.update("DELETE FROM workspace WHERE project_id = ?", projectId.value());
            jdbc.update("DELETE FROM turn_request WHERE session_id IN "
                    + "(SELECT id FROM session WHERE project_id = ?)", projectId.value());
            jdbc.update("DELETE FROM session WHERE project_id = ?", projectId.value());
            jdbc.update("DELETE FROM chat_message WHERE project_id = ?", projectId.value());
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
}
