package com.codeloom.app.turn;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.agent.llm.LlmMessage;
import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.llm.TokenUsage;
import com.codeloom.agent.loop.AgentTurn;
import com.codeloom.agent.loop.TurnOutcome;
import com.codeloom.agent.support.ScriptedLlm;
import com.codeloom.app.note.AgentNotes;

import com.codeloom.app.support.Await;
import com.codeloom.app.support.ScriptedLlmClientProvider;
import com.codeloom.app.support.StubModelConfig;
import com.codeloom.app.support.TempDirs;
import com.codeloom.app.project.ProjectLayout;
import com.codeloom.app.support.TestGit;
import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.AssistantMessage;
import com.codeloom.domain.event.ReasoningDelta;
import com.codeloom.domain.event.ModelChanged;
import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.ToolApprovalRequested;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.WorkspaceChanges;
import com.codeloom.domain.workspace.FileChange;
import com.codeloom.domain.event.SessionStateChanged;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.workspace.LocalWorkspaceManager;
import com.codeloom.workspace.git.GitClient;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.SessionState;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import com.codeloom.app.support.TestSessions;
import com.codeloom.app.support.TestUsers;
import com.codeloom.realtime.lease.RedisExecutionLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行器的端到端测试：真 MySQL + 真 Redis + 真 git 仓库 + **假模型**。
 *
 * <p>这是整个项目里唯一一个把四边接起来的测试，验的是它们接在一起还成立 ——
 * 各环节自身的测试在别处。
 *
 * <h2>唯一被替掉的是模型调用</h2>
 * 真实模型不可重复、要钱、要网络，而这里要验的不是"模型答得好不好"。
 * 除了它，其余每一环都是真的。
 *
 * <h2>为什么这个类的租约 TTL 只有 1.5 秒</h2>
 * 为了能在两秒内制造出"租约到期被人接管"。{@code renewInterval} 是 TTL 的三分之一，
 * 所以这里的续约节奏是 500ms —— 而本类里正常的那些轮次都在 100ms 内跑完，
 * 压根轮不到一次续约，不存在误判丢租约的问题。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(StubModelConfig.class)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
@TestPropertySource(properties = "codeloom.lease-ttl=1500ms")
@Transactional
class TurnExecutorTest {


    private static final ModelConfig MODEL = TestSessions.DEFAULT_MODEL;

    /** 一个**查不到**的模型名：窗口退回保守默认（64k），于是压缩阈值低、fixture 不用造得很大。 */
    private static final String UNKNOWN_MODEL = "unknown-model-for-compaction-test";

    /** worktree 建在这下面，整个类共用一个（它是 bean 的构造参数，只能按类配）。 */
    private static final Path WORKSPACES_ROOT = TempDirs.create("codeloom-ws-");
    private static final Path GIT_SANDBOX = TempDirs.create("codeloom-git-");

    @DynamicPropertySource
    static void codeloomProperties(DynamicPropertyRegistry registry) {
        registry.add("codeloom.workspaces-root", WORKSPACES_ROOT::toString);
        registry.add("codeloom.git.sandbox-dir", GIT_SANDBOX::toString);
    }

    @Autowired
    private TurnExecutor executor;

    @Autowired
    private ScriptedLlmClientProvider model;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private WorkspaceRepository worktrees;

    @Autowired
    private GitClient git;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private UserRepository users;

    @Autowired
    private EventStore events;

    @Autowired
    private EventBus bus;

    @Autowired
    private ExecutionLease leases;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RunningTurns runningTurns;

    @Autowired
    private AgentNotes notes;

    @Autowired
    private com.codeloom.app.turn.SessionWriter writer;

    /** 每个测试一个真仓库，`main` 上有一个提交（worktree 要从它分出来）。 */
    @TempDir
    Path repoRoot;

    // ------------------------------------------------------------------

    @Test
    @DisplayName("跑一轮：工具读的是【这条会话的 worktree】里的真文件，事件逐条落库，状态一路推进")
    void runsATurnAgainstARealWorktree() throws IOException {
        Session session = prepareSession("README.md", "codeloom 演示项目\n");
        model.script(
                ScriptedLlm.toolCall("call_1", "read_file", "{\"path\":\"README.md\"}"),
                ScriptedLlm.answer("读到了，是一个演示项目"));

        TurnResult result = executor.send(session.id(), "看一下 README");

        assertThat(result).isInstanceOf(TurnResult.Completed.class);
        assertThat(((TurnResult.Completed) result).outcome().status())
                .isEqualTo(TurnOutcome.Status.COMPLETED);

        List<Event> stream = readStream(session.id());
        assertThat(stream.get(0)).isEqualTo(new SessionStateChanged(
                SessionState.IDLE, SessionState.THINKING, null));
        assertThat(stream).contains(new UserMessage("看一下 README"));
        // 一条 checkpoint 必须在**这一轮结束之后**出现 —— 它记的是这一轮改完的位置，
        // 也正是"回滚到下一轮开始前"要退到的地方
        assertThat(stream).anySatisfy(event -> assertThat(event).isInstanceOf(CheckpointCreated.class));

        // 工具真的跑在了这条会话的 worktree 里 —— ToolResult 里带回了文件内容
        ToolResult toolResult = stream.stream()
                .filter(ToolResult.class::isInstance).map(ToolResult.class::cast)
                .findFirst().orElseThrow();
        assertThat(toolResult.success()).isTrue();
        assertThat(toolResult.output()).contains("codeloom 演示项目");

        // 状态跟着事件走：推理 → 执行工具 → 回到推理 → 等用户
        assertThat(transitions(session.id())).extracting(SessionStateChanged::to)
                .containsExactly(SessionState.THINKING, SessionState.EXECUTING_TOOL,
                        SessionState.THINKING, SessionState.WAITING_USER);
        // 停下来的原因留在事件里 —— 打转了？被取消了？验证没过？审计时看得见
        assertThat(transitions(session.id()).getLast().reason()).isEqualTo("COMPLETED");

        // 会话表的当前状态与事件流一致 —— 这是 SessionWriter 那个事务保证的东西
        Session after = sessions.findById(session.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(SessionState.WAITING_USER);
        // 轮次号推进了一格 —— 它数的是**已完成的交互数**，跑完一轮就是 1。
        // 它存在的理由就是让 checkpoint 与对话能互相对上，而回滚是同时退这两样
        assertThat(after.turnIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("一轮跑完落一条用量事件")
    void turnUsageIsRecorded() throws IOException {
        Session session = prepareSession("README.md", "codeloom 演示项目\n");
        model.script(ScriptedLlm.answer("好的"));

        executor.send(session.id(), "说点什么");

        TurnTokensUsed usage = usageOf(session.id());

        assertThat(usage.inputTokens()).isEqualTo(100);      // ScriptedLlm 每条都报这个数
        assertThat(usage.outputTokens()).isEqualTo(20);
        assertThat(usage.model()).isEqualTo("deepseek-flash");
    }

    @Test
    @DisplayName("【续跑】resume 不追加用户消息 —— 审批放行之后接着跑靠的就是这条")
    void resumeDoesNotAppendAUserMessage() throws IOException {
        Session session = prepareSession("README.md", "codeloom 演示项目\n");
        model.script(ScriptedLlm.answer("接着干"));

        executor.resume(session.id());

        // 把历史原样喂回去接着跑，**不伪造一条用户消息** ——
        // 那会污染审计流（看起来像用户说了话），也会让模型的上下文里多一句它没说过的话
        assertThat(readStream(session.id())).noneMatch(UserMessage.class::isInstance);
        // 用量照样要记：这一轮也是真花了钱的
        assertThat(usageOf(session.id()).totalTokens()).isEqualTo(120);
    }

    private TurnTokensUsed usageOf(SessionId sessionId) {
        return readStream(sessionId).stream()
                .filter(TurnTokensUsed.class::isInstance)
                .map(TurnTokensUsed.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("这一轮没有留下用量事件"));
    }

    @Test
    @DisplayName("【压缩】历史超长时被摘要取代 —— 模型看到的是摘要，不是那段原文")
    void longHistoryIsCompactedIntoASummary() throws IOException {
        Session session = prepareSession("README.md", "x");

        seedHugeHistory(session);

        model.script(
                ScriptedLlm.answer("这就是摘要"),   // 压缩时那次生成摘要的调用
                ScriptedLlm.answer("好的"));         // 真正跑的那一轮

        TurnResult result = executor.send(session.id(), "现在做点新的");
        // 压缩过程中抛的任何异常都会被执行器的 catch 吞成 Failed。先钉住这一点，
        // 否则下面那句"没落压缩事件"的失败会掩盖真正的原因
        assertThat(result).isInstanceOf(TurnResult.Completed.class);

        ContextCompacted compacted = readStream(session.id()).stream()
                .filter(ContextCompacted.class::isInstance)
                .map(ContextCompacted.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("超长历史没有被压缩"));
        assertThat(compacted.droppedUpToSeq()).isPositive();

        // 用干净的装配器投影整条事件流，得到的就是这一轮模型看到的样子
        List<LlmMessage> seen = new ContextAssembler(userId -> null)
                .assemble(events.readAll(session.id()), MODEL.systemPrompt());
        assertThat(seen).anySatisfy(m -> assertThat(m.content()).contains("这就是摘要"));
        // 原文一个字都不该再送出去
        assertThat(seen).noneSatisfy(m -> assertThat(m.content()).contains("啰嗦"));
        // 而用户**这一轮**说的话必须还在 —— 压缩赶在它落库之前，就是为了这个
        assertThat(seen).anySatisfy(m -> assertThat(m.content()).isEqualTo("现在做点新的"));
    }

    @Test
    @DisplayName("【压缩】生成摘要失败不能拖垮这一轮 —— 它只是个优化，不压照样跑")
    void compactionFailureDoesNotFailTheTurn() throws IOException {
        Session session = prepareSession("README.md", "x");
        seedHugeHistory(session);

        model.script(
                ScriptedLlm.answer(""),        // 摘要那一次返回空 —— ContextCompacted 会拒绝空摘要
                ScriptedLlm.answer("好的"));    // 真正跑的那一轮

        TurnResult result = executor.send(session.id(), "现在做点新的");

        // 这一轮照常跑完：压缩没做成而已，不该让用户看到一次莫名其妙的失败
        assertThat(result).isInstanceOf(TurnResult.Completed.class);
        // 而且**没有**落下压缩事件 —— 宁可不压，也不能拿一条空摘要把那段历史抹掉
        assertThat(readStream(session.id())).noneMatch(ContextCompacted.class::isInstance);
    }

    @Test
    @DisplayName("【留言】别条会话捎来的话，在下一轮开头被领走、落成事件，并且带上来源")
    void noteIsDeliveredAtTheStartOfTheNextTurn() throws IOException {
        Session session = prepareSession("README.md", "x");
        // 真实路径是 REST 端点 —— 那时请求没有租约可带，所以只能先进队列。
        // 发起者用**真有的那个人**：投影要拿这个 id 去查他的名字（见 ContextAssembler）
        notes.enqueue(session.id(), new AgentNoteDelivered(
                SessionId.generate(), session.ownerId(), "我给 OrderService 加好锁了，你别重复改"));

        model.script(ScriptedLlm.answer("知道了"));
        executor.send(session.id(), "继续");

        // 到了这一步它才成为事实
        assertThat(readStream(session.id()))
                .anySatisfy(e -> assertThat(e).isInstanceOf(AgentNoteDelivered.class));

        // **而模型看到的那句话里带着来源** —— 这一条才是整个功能的关键。
        // 不标来源的话，模型会把别人的请求当成自己用户的指示，照着去动自己这边的代码。
        // 名字是**投影时按 id 现查**的（事件里只有 id，见 AgentNoteDelivered）——
        // 所以这里给它接上真的用户表，而不是自己编一个名字
        List<LlmMessage> seen = new ContextAssembler(
                        id -> users.findById(id).map(User::displayName).orElse(null))
                .assemble(events.readAll(session.id()), MODEL.systemPrompt());
        assertThat(seen).anySatisfy(m -> assertThat(m.content())
                .contains("来自 Owner")
                .contains("别重复改"));
    }

    @Test
    @DisplayName("【审批】白名单外的命令不再直接拒，而是把整轮挂起等人批 —— 而且它没有被执行")
    void unlistedCommandSuspendsTheTurnForApproval() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(ScriptedLlm.toolCall("c1", "run_command",
                "{\"command\":\"curl https://example.com\"}"));

        TurnResult result = executor.send(session.id(), "把那个页面拉下来");

        // 把结果本身带进断言信息里 —— 失败时 TurnResult.Failed 的原因才看得见
        assertThat(result).as("这一轮的结果: %s", result)
                .isInstanceOf(TurnResult.Completed.class);
        assertThat(((TurnResult.Completed) result).outcome().status())
                .isEqualTo(TurnOutcome.Status.AWAITING_APPROVAL);

        List<Event> stream = readStream(session.id());
        // "模型请求了它"照样是事实
        assertThat(stream).anySatisfy(e -> assertThat(e).isInstanceOf(ToolCallRequested.class));
        // 平台判定它得先问过人
        assertThat(stream).anySatisfy(e -> assertThat(e).isInstanceOf(ToolApprovalRequested.class));
        // 但**没有 ToolResult** —— 它压根没跑。这是 APPROVAL_REQUIRED 和"真失败"
        // 分开的全部意义：一个是"等一下"，一个是"不行"
        assertThat(stream).noneMatch(ToolResult.class::isInstance);

        assertThat(sessions.findById(session.id()).orElseThrow().state())
                .isEqualTo(SessionState.AWAITING_APPROVAL);
    }

    @Test
    @DisplayName("【审批】挂着等人批时又打了一句 —— 排队，不打死会话，答完之后它自己会被交给模型")
    void sendingWhileAnApprovalIsPendingIsQueuedNotLost() throws IOException {
        // 从前这条路会把**整条会话打成 FAILED**：每一轮开头要迁到 THINKING，而状态机只允许
        // AWAITING_APPROVAL → WAITING_USER —— 抛异常、被执行器收成 FAILED，用户那句话
        // 一个字都不落库（崩溃恢复那条路踩过同一个坑，那边修了这边没有）
        Session session = prepareSession("README.md", "x");
        model.script(ScriptedLlm.toolCall("c1", "run_command",
                "{\"command\":\"curl https://example.com\"}"));
        executor.send(session.id(), "把那个页面拉下来");
        assertThat(sessions.findById(session.id()).orElseThrow().state())
                .isEqualTo(SessionState.AWAITING_APPROVAL);

        // 还没答复那个调用，用户又打了一句
        TurnResult queued = executor.send(session.id(), "算了，先别拉");

        // ★ 收下了、排着队 —— 不是 FAILED，也不是 409
        assertThat(queued).as("第二次发送的结果: %s", queued)
                .isEqualTo(new TurnResult.Queued(session.id(), 0));
        // 而会话**动都没动**：那个批准还挂在那儿等着人点
        assertThat(sessions.findById(session.id()).orElseThrow().state())
                .isEqualTo(SessionState.AWAITING_APPROVAL);

        // 答复那个批准（拒绝）。走**真的那条路**：ApprovalService 用的是 writer.append，
        // 它顺带把状态从 AWAITING_APPROVAL 推到 WAITING_USER（见 TurnStates.after）——
        // 用裸的 events.append 的话状态不动，后面就会撞上同一个非法迁移。
        // 而且要用重新读出来的那一份：手上这个 session 是刚建会话时的快照，状态还停在 IDLE
        Session fresh = sessions.findById(session.id()).orElseThrow();
        LeaseToken token = leases.tryAcquire(fresh).orElseThrow();
        try {
            writer.append(fresh, new ToolApprovalResolved("c1", false, UserId.of("li"), null), token);
        } finally {
            leases.release(token);
        }

        // 两次模型调用：一次是答复之后的续跑，一次是**交付那句排队的话**
        model.script(ScriptedLlm.answer("好，那先不拉"), ScriptedLlm.answer("知道了"));
        assertThat(executor.resume(session.id())).isInstanceOf(TurnResult.Completed.class);

        // ★ 它被交给了模型 —— 落成一条**普通的用户消息**，就在答复那一轮收尾的时候
        assertThat(readStream(session.id())).anySatisfy(e -> {
            assertThat(e).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) e).text()).isEqualTo("算了，先别拉");
        });
    }

    @Test
    @DisplayName("【排队】一轮正在跑的时候发消息 —— 收下排队，而不是回 Busy 让人自己去重试")
    void sendingWhileATurnIsRunningIsQueued() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(ScriptedLlm.toolCall("c1", "read_file", "{\"path\":\"README.md\"}"),
                ScriptedLlm.answer("读完了"));

        // 先把那棵树的租约占住 —— 模拟"另一条会话（或另一个实例）正在跑"
        LeaseToken held = leases.tryAcquire(session).orElseThrow();
        try {
            TurnResult result = executor.send(session.id(), "顺便把这个也改一下");
            assertThat(result).as("结果: %s", result)
                    .isEqualTo(new TurnResult.Queued(session.id(), 0));
        } finally {
            leases.release(held);
        }
    }

    @Test
    @DisplayName("【审批】答复一旦落下，投影里它就充当那次调用的结果 —— 模型据此决定下一步")
    void approvalAnswerBecomesTheToolResult() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(ScriptedLlm.toolCall("c1", "run_command",
                "{\"command\":\"curl https://example.com\"}"));
        executor.send(session.id(), "把那个页面拉下来");

        LeaseToken token = leases.tryAcquire(session).orElseThrow();
        try {
            events.append(session.id(),
                    new ToolApprovalResolved("c1", true, UserId.of("li"), null), token);
        } finally {
            leases.release(token);
        }

        // 它出现在模型看到的历史里，而且**取代**了那个"没有结果的调用"
        List<LlmMessage> seen = new ContextAssembler(userId -> null)
                .assemble(events.readAll(session.id()), MODEL.systemPrompt());
        assertThat(seen).anySatisfy(m -> assertThat(m.content()).contains("批准了这次调用"));
        assertThat(seen).noneSatisfy(m -> assertThat(m.content()).contains("没有留下结果"));
    }

    @Test
    @DisplayName("【轮次】挂着等人批不算跑完 —— 号等到批准、续跑跑完才推")
    void suspendedTurnIsNotCountedUntilItCompletes() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(
                ScriptedLlm.toolCall("c1", "run_command",
                        "{\"command\":\"curl https://example.com\"}"),
                ScriptedLlm.answer("拉下来了"));

        executor.send(session.id(), "把那个页面拉下来");

        // 挂起那一刻：用户**确实**说了一句话，但模型还没答完 —— 它在等他点批准。
        // 一轮 = 问一次答一次，所以这次交互还不算结束，号停在 0
        assertThat(turnIndexOf(session)).as("挂起时").isZero();

        // 批准，接着跑完 —— 而对用户来说，**这一轮还是那一轮**。
        //
        // 两处细节都是必须的（同 sendingWhileAnApprovalIsPendingIsQueuedNotLost 那段注释）：
        // 走 writer.append 而不是裸的 events.append，因为前者顺带把状态从 AWAITING_APPROVAL
        // 推到 WAITING_USER —— 不推的话 resume 会撞上非法迁移；
        // 用重新读出来的那一份，因为手上这个 session 是刚建会话时的快照
        Session fresh = sessions.findById(session.id()).orElseThrow();
        LeaseToken token = leases.tryAcquire(fresh).orElseThrow();
        try {
            writer.append(fresh, new ToolApprovalResolved("c1", true, UserId.of("li"), null), token);
        } finally {
            leases.release(token);
        }
        // **先确认续跑真的发生了** —— 少了这条断言，下面那句"是 1"会在
        // "resume 压根没跑起来"的情况下也通过，而那样测试就成了摆设
        assertThat(executor.resume(session.id())).as("续跑的结果")
                .isInstanceOf(TurnResult.Completed.class);

        // ★ 跑完了，推一格。用户从头到尾**只说过一句话**，所以是 1 不是 2
        assertThat(turnIndexOf(session)).as("批准续跑之后").isEqualTo(1);

        // 两条 checkpoint 各写了什么：挂起那条是 0（那一轮还没跑完），跑完那条是 1。
        // checkpoint、会话行、以及"用户说过几句话"三者是同一个答案
        assertThat(readStream(session.id()).stream()
                .filter(CheckpointCreated.class::isInstance)
                .map(CheckpointCreated.class::cast)
                .map(CheckpointCreated::turnIndex))
                .as("两条 checkpoint 的号").containsExactly(0, 1);
    }

    private int turnIndexOf(Session session) {
        return sessions.findById(session.id()).orElseThrow().turnIndex();
    }

    @Test
    @DisplayName("【轮次】模型调用失败也算一次交互结束 —— 号照样推，改动也有自己的位置")
    void failedTurnStillCountsAsAnInteraction() throws IOException {
        Session session = prepareSession("README.md", "x");
        // 第一次调用正常（于是它写出去一个文件），第二次炸 —— 这一轮就是"改过东西又失败了"。
        // 用 AUTH 是因为它**不可重试**（见 LlmCallException.retryable）：这一轮当场失败。
        // 换成 NETWORK 的话测的就是"退避重试"，收尾那一步根本走不到
        AtomicInteger calls = new AtomicInteger();
        model.onCall(() -> {
            if (calls.incrementAndGet() > 1) {
                throw new LlmCallException(LlmCallException.Kind.AUTH,
                        "密钥无效", null);
            }
        });
        model.script(ScriptedLlm.toolCall("c1", "write_file",
                "{\"path\":\"HALF.md\",\"content\":\"写了一半\"}"));

        TurnResult result = executor.send(session.id(), "改一下");

        assertThat(result).isInstanceOf(TurnResult.Failed.class);
        assertThat(sessions.findById(session.id()).orElseThrow().state())
                .isEqualTo(SessionState.FAILED);

        // 用户等到的是"这一轮失败了" —— 对他来说这次交互已经结束，下一句该是新的一轮。
        // 不推的话界面上会一直写着"第 0 轮"，而他已经说过一句话
        assertThat(turnIndexOf(session)).isEqualTo(1);

        // ★ 而且这一轮有**自己的位置**。能不能回退跟这一轮最后跑成什么样无关：
        //   位置是在一轮开始时落下的。
        //
        //   不提交的话 HALF.md 对 git 来说是未跟踪的，而 reset --hard 不碰未跟踪文件 ——
        //   回滚会以为什么都没发生。这个根因在这个项目里害过两次
        CheckpointCreated checkpoint = readStream(session.id()).stream()
                .filter(CheckpointCreated.class::isInstance)
                .map(CheckpointCreated.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("失败的那一轮没有留下 checkpoint"));
        assertThat(checkpoint.turnIndex()).isEqualTo(1);
        assertThat(TestGit.run(worktreeOf(session),
                "show", "--stat", "--oneline", checkpoint.commitSha())).contains("HALF.md");
    }

    @Test
    @DisplayName("【轮次】用户按 Esc 打断也算一次交互结束 —— 号照样推，改动也有自己的位置")
    void interruptedTurnCountsAsAnInteraction() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(ScriptedLlm.answer("这就去改"));
        model.onCall(() -> runningTurns.interrupt(session.id()));

        assertThat(executor.send(session.id(), "改一下"))
                .isInstanceOf(TurnResult.Interrupted.class);

        // 是他自己叫停的，但那句话已经说出口、模型也已经停下来了 —— 这次交互结束了。
        // 和失败那条同理：下一句话不该还挂着上一轮的号，而这一轮也该有个可回的位置
        assertThat(turnIndexOf(session)).isEqualTo(1);
        assertThat(readStream(session.id()).stream()
                .filter(CheckpointCreated.class::isInstance)).isNotEmpty();
    }

    @Test
    @DisplayName("【幂等】同一个 clientMessageId 发两次，第二次不跑 —— 重试不会花两次钱、改两遍工作区")
    void duplicateRequestDoesNotRunTwice() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(ScriptedLlm.answer("好的"));

        TurnResult first = executor.send(session.id(), "改一下", "req-1");
        TurnResult second = executor.send(session.id(), "改一下", "req-1");

        assertThat(first).isInstanceOf(TurnResult.Completed.class);
        assertThat(second).as("第二次的结果: %s", second)
                .isInstanceOf(TurnResult.Duplicate.class);

        // 模型只被调用了一次 —— 这条才是重点：重复请求没有变成第二次付费调用
        assertThat(model.requests()).hasSize(1);
        // 用户消息也只落了一条：第二次连历史都没进
        assertThat(readStream(session.id()).stream().filter(UserMessage.class::isInstance).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("【幂等】不同的 clientMessageId 各跑各的 —— 别把两句正常的话当成重复")
    void differentRequestIdsBothRun() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(ScriptedLlm.answer("第一句的答复"), ScriptedLlm.answer("第二句的答复"));

        executor.send(session.id(), "第一句", "req-1");
        TurnResult second = executor.send(session.id(), "第二句", "req-2");

        assertThat(second).isInstanceOf(TurnResult.Completed.class);
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    @DisplayName("【留言】领走但没确认的，下一轮还能被捞回来 —— 崩在中间不会丢")
    void unackedNotesAreRedelivered() {
        SessionId sessionId = SessionId.generate();
        notes.enqueue(sessionId, new AgentNoteDelivered(
                SessionId.generate(), UserId.of("li"), "我给 OrderService 加好锁了"));

        // 排队期间看得到它 —— 这就是「我发的留言到哪了」的答案
        assertThat(notes.pendingInQueue(sessionId)).hasSize(1);

        // 第一次领走，但**不确认** —— 模拟"已经取出来、落库途中进程挂了"
        assertThat(notes.drain(sessionId)).hasSize(1);

        // 领走之后它就不在"还排着队"里了 —— 它进了 pending 区，正在被投递。
        // 两者混在一起返回的话，用户看到的会是"它还在等"，而其实下一秒它就成了事实
        assertThat(notes.pendingInQueue(sessionId)).isEmpty();

        // 下一轮还能捞回来。这就是 pending 区的全部意义：
        // 换成原来那种"弹出来就删"，这条留言此时已经永久没了
        List<AgentNoteDelivered> redelivered = notes.drain(sessionId);
        assertThat(redelivered).hasSize(1);
        assertThat(redelivered.getFirst().text()).contains("加好锁了");

        // 确认之后才真的没了
        notes.ack(sessionId, redelivered);
        assertThat(notes.drain(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("【协作】我新建了文件 —— 对方那条会话会收到一条提醒")
    void newFilesAreAnnouncedToTheTeammate() throws IOException {
        Session session = prepareSession("README.md", "x", TestUsers.ALICE);
        model.script(ScriptedLlm.answer("写好了"));

        executor.send(session.id(), "写个鉴权");

        // 这一轮真的新建了文件才有得报 —— 而 agent 这一轮被脚本化成"只说话"，
        // 所以先确认这种情况**不**发（下面再验发的那个）
        assertThat(notes.drain(teammateSessionOf(session))).isEmpty();
    }

    @Test
    @DisplayName("【改动记录】这一轮改了哪些文件，**在收尾时**落进事件流 —— 读的人不用再问 git")
    void theTurnRecordsWhatItChanged() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(
                ScriptedLlm.toolCall("call_1", "write_file",
                        "{\"path\":\"AuthService.java\",\"content\":\"class AuthService {}\\n\"}"),
                ScriptedLlm.answer("写好了"));

        executor.send(session.id(), "写个鉴权");

        // ★ 这一条是**当时算的、记下来的**，不是读的时候现问 git 出来的。
        //   它保的是两件事：读路径不必每读一次就算一遍（也不必每轮重算整条历史），
        //   而且那个提交后来被 gc 掉了也不影响 —— 事实已经记在流里了
        WorkspaceChanges record = events.readAll(session.id()).stream()
                .map(StoredEvent::event)
                .filter(WorkspaceChanges.class::isInstance)
                .map(WorkspaceChanges.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("这一轮没有落下改动的记录"));

        assertThat(record.truncated()).isFalse();
        // ★ 它记的轮次号必须和**同一次收尾那条 checkpoint** 记的是同一个数。
        //   界面上"这一轮改了哪些文件"挂到哪一轮，全看它 —— 两者算的是同一个 `closed`，
        //   传错一个（比如传了推进前的 session）就会整体错一格，而那看起来完全像真的
        assertThat(record.turnIndex())
                .isNotNull()
                .isEqualTo(events.readAll(session.id()).stream()
                        .map(StoredEvent::event)
                        .filter(CheckpointCreated.class::isInstance)
                        .map(CheckpointCreated.class::cast)
                        .reduce((first, second) -> second)
                        .orElseThrow(() -> new AssertionError("这一轮没有落下 checkpoint"))
                        .turnIndex());
        assertThat(record.files()).anySatisfy(file -> {
            // 路径已经是**项目相对**的（git 说的仓库路径在记录之前就翻过一道）
            assertThat(file.path()).isEqualTo("AuthService.java");
            assertThat(file.created()).isTrue();
            assertThat(file.added()).isPositive();
        });
    }

    @Test
    @DisplayName("【改动记录】一轮什么都没改时**不记** —— 记的是这一轮做了什么，不是那个提交做过什么")
    void aTurnThatChangedNothingRecordsNothing() throws IOException {
        Session session = prepareSession("README.md", "x");
        // 第一轮真的写出文件，第二轮只说一句话（不产生新提交）
        model.script(
                ScriptedLlm.toolCall("call_1", "write_file",
                        "{\"path\":\"AuthService.java\",\"content\":\"class AuthService {}\\n\"}"),
                ScriptedLlm.answer("写好了"),
                ScriptedLlm.answer("又说了句别的"));

        executor.send(session.id(), "写个鉴权");
        executor.send(session.id(), "再说一句");

        // ★ 没有改动时交出来的位置还是**上一个 HEAD**，拿它去算 `^!` 得到的是上一轮的那几个
        //   文件 —— 于是同一个文件被每一轮记一遍。判据是"这一轮有没有造出新提交"，
        //   不是"这个 sha 指向的提交改过什么"
        long records = events.readAll(session.id()).stream()
                .filter(stored -> stored.event() instanceof WorkspaceChanges)
                .count();
        assertThat(records).isEqualTo(1);
    }

    @Test
    @DisplayName("【协作】新建的文件会告诉对方；没新建的一轮不会重复报")
    void onlyNewFilesAreAnnouncedOnce() throws IOException {
        Session session = prepareSession("README.md", "x", TestUsers.ALICE);
        // 一次工具调用把文件写出来，第二轮只说句话（不产生新文件）
        model.script(
                ScriptedLlm.toolCall("call_1", "write_file",
                        "{\"path\":\"AuthService.java\",\"content\":\"class AuthService {}\\n\"}"),
                ScriptedLlm.answer("鉴权写好了"),
                ScriptedLlm.answer("又说了句别的"));

        executor.send(session.id(), "写个鉴权");

        SessionId mate = teammateSessionOf(session);
        // **用 drain 领走**，不是 pendingInQueue：留言有三个区（入队 / 领走 / 确认），
        // 而 pendingInQueue 只是"看队列里有什么"，不把它取出来 —— 拿它当"清空"用的话，
        // 第一轮那条会一直躺在那儿，看起来像"又报了一遍"
        List<AgentNoteDelivered> first = notes.drain(mate);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().text()).contains("AuthService.java");
        // 名字**不在正文里** —— 它由读的那一侧按 id 现查。写进正文的话，界面上读出来是
        // "Owner 的 agent 捎来一句：Owner 刚新建了…"，同一个事实说两遍（见 AgentNoteDelivered）
        assertThat(first.getFirst().fromUserId()).isEqualTo(session.ownerId());
        // 领走之后还要**确认** —— drain 只是把它挪进 pending（宁可重投也不能丢），
        // 不 ack 的话下一轮还会被读出来，看起来像"又报了一遍"（见 AgentNotes.drain）
        notes.ack(mate, first);

        // ★ 第二轮什么都没新建 —— **不能再报一遍**。
        //   这一条盯的是一个具体的坑：没有改动时 commit() 返回的是**当前 HEAD**
        //   （见 GitClient.commitStaged），拿它去算 `^!` 会得到"上一个提交引入了什么"，
        //   于是同一个文件被每一轮反复报
        executor.send(session.id(), "再说一句");
        List<AgentNoteDelivered> second = notes.drain(mate);
        assertThat(second).as("第二轮不该再报。收到的是：%s", second).isEmpty();
    }

    /** 队友那条会话 —— 协作提醒发到的是它。 */
    private SessionId teammateSessionOf(Session session) {
        SessionId mate = sessions.findByProject(session.projectId(), 10, 0).stream()
                .filter(candidate -> candidate.ownerId().equals(TestUsers.ALICE))
                .map(Session::id)
                .findFirst()
                .orElseThrow();
        return mate;
    }

    @Test
    @DisplayName("【打断】用户按 Esc 停下那一轮 —— 会话回到等你说话，**不是 FAILED**")
    void userInterruptIsNotAFailure() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(ScriptedLlm.answer("这就去改"));
        // 用户是在这一轮**跑着的时候**按的 Esc，所以钩子挂在模型调用上：
        // 挂在这里才等于"中途"，调 send 之前挂等于"还没开始"
        model.onCall(() -> runningTurns.interrupt(session.id()));

        TurnResult result = executor.send(session.id(), "改一下");

        // 它不是"出错"。从前这条路会落到 TurnResult.Failed 上
        assertThat(result).isInstanceOf(TurnResult.Interrupted.class);

        // 会话停在"等你说话" —— 停下来是常态，不是异常（见 finalStateFor 那段）
        assertThat(sessions.findById(session.id()).orElseThrow().state())
                .isEqualTo(SessionState.WAITING_USER);

        List<Event> stream = readStream(session.id());

        // 状态流里**没有 FAILED 这一格** —— 那正是界面上那行红字加 Java 异常类名的来源
        assertThat(stream)
                .noneMatch(e -> e instanceof SessionStateChanged changed
                        && changed.to() == SessionState.FAILED);

        // 而它仍然是一条**可审计的事实**：原因写着 CANCELLED，
        // 回放的时候"这一轮为什么停"看得见。界面上认的也是这个值
        //（见 sessionStream 里那条 notice），所以两边必须是同一个字符串
        assertThat(stream)
                .anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(SessionStateChanged.class,
                        changed -> {
                            assertThat(changed.to()).isEqualTo(SessionState.WAITING_USER);
                            assertThat(changed.reason()).isEqualTo("CANCELLED");
                        }));
    }

    @Test
    @DisplayName("【换模型】切了之后下一轮就用新的 —— 会话行和事件流两边都对得上")
    void switchingModelTakesEffectOnTheNextTurn() throws IOException {
        Session session = prepareSession("README.md", "x");
        // 两轮分别用两个模型回报 —— 用真实的 LlmResult，好让"服务商回报的模型名"这条也一起验到
        model.script(
                new LlmResult("deepseek-flash", "stop", "第一轮",
                        List.of(), new TokenUsage(10, 5, 15, 0, 0)),
                new LlmResult("deepseek-reasoner", "stop", "第二轮",
                        List.of(), new TokenUsage(10, 5, 15, 0, 0)));

        executor.send(session.id(), "先问一句");

        // 换模型（真实路径是 PUT /api/sessions/{id}/model）
        Session before = sessions.findById(session.id()).orElseThrow();
        switchModel(before, "deepseek-reasoner");

        executor.send(session.id(), "再问一句");

        // 事件落了 —— 换模型是**事实**，要能审计、要能重放
        assertThat(readStream(session.id()))
                .anySatisfy(e -> assertThat(e).isInstanceOf(ModelChanged.class));
        // 会话行也改了（两边同事务，读取快的那一份）
        assertThat(sessions.findById(session.id()).orElseThrow().model().modelId())
                .isEqualTo("deepseek-reasoner");
        // **而且第二轮真的用了新模型** —— 这条才是"下一轮生效"的证明
        assertThat(model.requests().get(1).model()).isEqualTo("deepseek-reasoner");
    }

    @Test
    @DisplayName("【换模型】每条消息带着自己那一轮的模型名 —— 换过之后两条消息的署名不同")
    void eachMessageCarriesItsOwnModel() throws IOException {
        Session session = prepareSession("README.md", "x");
        model.script(
                new LlmResult("deepseek-flash", "stop", "第一轮",
                        List.of(), new TokenUsage(10, 5, 15, 0, 0)),
                new LlmResult("deepseek-reasoner", "stop", "第二轮",
                        List.of(), new TokenUsage(10, 5, 15, 0, 0)));

        executor.send(session.id(), "先问一句");
        Session before = sessions.findById(session.id()).orElseThrow();
        switchModel(before, "deepseek-reasoner");
        executor.send(session.id(), "再问一句");

        List<AssistantMessage> said = readStream(session.id()).stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .toList();

        // 界面上要显示的就是这个：第一句是 flash 说的，第二句是 reasoner 说的。
        // 只把模型名记在轮次上的话，这个区分就丢了
        assertThat(said).extracting(AssistantMessage::model)
                .containsExactly("deepseek-flash", "deepseek-reasoner");
    }

    /**
     * 走服务商那条路换模型。
     *
     * <p>{@code SessionService} 标了 {@code @ConditionalOnWebApplication}（它抛的是 web 层的
     * {@code ResponseStatusException}），而这个测试跑在非 web 上下文里，所以直接用它底下的
     * {@code SessionWriter} —— 同一个落库路径。"会话正忙就 409"那一层在 controller 上，不在这。
     */
    private void switchModel(Session session, String modelId) {
        Session before = sessions.findById(session.id()).orElseThrow();
        ModelConfig next = new ModelConfig(before.model().provider(), modelId,
                before.model().systemPrompt());

        LeaseToken token = leases.tryAcquire(session).orElseThrow();
        try {
            writer.changeModel(before, next, token);
        } finally {
            leases.release(token);
        }
    }

    /**
     * 灌一段超长历史，把上下文顶过压缩阈值。24 万字符 ≈ 6 万 token。
     *
     * <h2>为什么先把模型换成一个认不出的名字</h2>
     * 因为窗口决定阈值，而 DeepSeek 的窗口是 **1,000,000** —— 用它的话阈值是 80 万，
     * 这里得造 320 万字符的历史才顶得过去。而这个测试要验的是**压缩这件事**
     *（摘要取代原文、原文不再送出去、这一轮的用户消息还在），不是窗口的值。
     * 窗口和阈值那笔账由 {@code ContextCompactorTest} 单独盯着。
     *
     * <p>换成认不出的名字之后窗口退回 64,000 的保守默认，阈值 43,904 —— 6 万 token
     * 刚好越过它。**这是刻意的**：测试的 fixture 应该跟着"最小能触发的那个配置"走，
     * 而不是跟着生产配置一起变大。
     */
    private void seedHugeHistory(Session session) {
        switchModel(session, UNKNOWN_MODEL);
        LeaseToken token = leases.tryAcquire(session).orElseThrow();
        try {
            events.append(session.id(), new UserMessage("旧历史：" + "啰嗦".repeat(120_000)), token);
        } finally {
            leases.release(token);
        }
    }

    @Test
    @DisplayName("【回滚与合并的前提】一轮结束时留下一个真的 commit，而且工作区是干净的")
    void everyTurnEndsWithARealCommit() throws IOException {
        Session session = prepareSession("README.md", "codeloom 演示项目\n");
        model.script(
                ScriptedLlm.toolCall("c1", "write_file",
                        "{\"path\":\"NOTES.md\",\"content\":\"这条是 agent 写的\"}"),
                ScriptedLlm.answer("写好了"));

        executor.send(session.id(), "写个 NOTES.md");

        CheckpointCreated checkpoint = readStream(session.id()).stream()
                .filter(CheckpointCreated.class::isInstance)
                .map(CheckpointCreated.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("这一轮没有留下 checkpoint"));

        Path worktree = worktreeOf(session);
        // 那个 sha 是真实存在的提交 —— 否则回滚会 reset 到一个不存在的点
        assertThat(TestGit.run(worktree, "cat-file", "-t", checkpoint.commitSha()).strip()).isEqualTo("commit");
        // **而且 agent 写出来的文件已经被提交进去了。** 不提交的话它是"未跟踪的"，
        // 而合并合的是分支、回滚 reset 的也是已跟踪的内容 ——
        // 两边都会以为什么都没发生。这个根因害过两次
        assertThat(TestGit.run(worktree, "show", "--stat", "--oneline", checkpoint.commitSha()))
                .contains("NOTES.md");
        assertThat(TestGit.run(worktree, "status", "--porcelain")).isBlank();
        // checkpoint 记的是**这一轮结束时**的位置，于是"回滚到下一轮开始前"就是退到它。
        // 它带的号是"到这儿为止完成了几次交互" —— 跑完一轮所以是 1，
        // 和会话行上那个数、以及回滚时写回会话行的值，三者是同一个意思
        assertThat(checkpoint.turnIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("会话已经有执行者 → 收下排队（不是 409），而且一个字都还没落库")
    void busyWhenSomeoneElseHoldsTheLease() throws IOException {
        // 从前这里是 Busy（409，"等它跑完再试"），用户得自己盯着、再点一次；
        // Claude Code 里这时候**照样能发**，只是排着队（见 TurnResult.Queued）。
        //
        // 这一条盯的是"排队期间不落库"：那句话还存在内存里，要等**跑着的那一轮收尾时**
        // 由它落库（追加事件要持有租约）。在那之前写一条没人会处理的用户消息，
        // 只会让审计流里多出一条悬着的记录
        Session session = prepareSession("README.md", "x");
        LeaseToken held = leases.tryAcquire(session).orElseThrow();
        try {
            TurnResult result = executor.send(session.id(), "再跑一轮");

            assertThat(result).as("结果: %s", result)
                    .isEqualTo(new TurnResult.Queued(session.id(), 0));
            assertThat(events.readAll(session.id())).isEmpty();
        } finally {
            leases.release(held);
        }
    }

    @Test
    @DisplayName("【中断】信号发出之后这一轮在工具边界停下 —— 不是把进程掐断")
    void interruptingStopsAtTheNextToolBoundary() throws Exception {
        Session session = prepareSession("README.md", "x");
        model.script(
                ScriptedLlm.toolCall("call_1", "run_command",
                        "{\"command\":\"node -e 'setTimeout(()=>{},1200)'\"}"),
                ScriptedLlm.answer("不该走到这里"));

        TurnResult result;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 中断从另一个线程发出：send 是阻塞的，而且跑在测试线程上（也就在测试事务里），
            // 换个线程发的话它看不到那些没提交的数据 —— 但它要做的只是往登记表里按一下
            pool.submit(() -> {
                // 等这一轮**真的**在跑 —— 固定 sleep(300) 是猜：机器一慢，信号就可能在
                // 这一轮结束之后才到，那时断言的是另一件事（而它会安静地通过）
                Await.until("这一轮已经跑起来", () -> turnHasStarted(session));
                runningTurns.interrupt(session.id());
                return null;
            });
            result = executor.send(session.id(), "跑个慢命令");
        }

        // 停在 CANCELLED 而不是崩掉：取消的检查点在工具边界上，
        // "跑完手上这个再停"最多多等几秒 —— 换来的是不会留下半截状态
        assertThat(result).isInstanceOf(TurnResult.Completed.class);
        assertThat(((TurnResult.Completed) result).outcome().status())
                .isEqualTo(TurnOutcome.Status.CANCELLED);
        // 而"这一轮被取消了"也落进了审计流（原因写在 SessionStateChanged.reason 里）
        assertThat(transitions(session.id()).getLast().reason()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("【中断】没在跑的时候发信号：什么都不发生，也不报错")
    void interruptingAnIdleSessionIsHarmless() throws IOException {
        Session session = prepareSession("README.md", "x");

        // 信号可能晚到一步（那一轮刚好跑完了）。那种情况**不该报错** ——
        // 客户端拿到的 202 本来就只意味着"信号发出去了"
        runningTurns.interrupt(session.id());

        assertThat(turnHasStarted(session)).isFalse();
    }

    @Test
    @DisplayName("【安全约束】中途失去租约 → 立刻停手，且不写收尾状态、不释放别人的锁")
    void losingTheLeaseMidTurnStopsEverything() throws Exception {
        Session session = prepareSession("README.md", "x");
        model.script(
                ScriptedLlm.toolCall("call_1", "run_command",
                        "{\"command\":\"node -e 'setTimeout(()=>{},1200)'\"}"),
                ScriptedLlm.answer("跑完了"));

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 在后台把锁删掉，模拟"租约到期、别的实例接管了"。
            // 必须后台做：send 是阻塞的，而且它跑在测试线程上（也就在测试事务里）
            pool.submit(() -> {
                // 同上面那条：等它真的跑起来，而不是睡一个猜出来的时长。
                // 删早了会在 `tryAcquire` 之前就把锁删掉，那这一轮根本不会开始 ——
                // 于是拿到的不是 LeaseLost 而是 Completed，测试失败得莫名其妙
                Await.until("这一轮已经跑起来", () -> turnHasStarted(session));
                return redis.delete(RedisExecutionLease.keyFor(session.workspaceId()));
            });

            TurnResult result = executor.send(session.id(), "跑个慢命令");

            assertThat(result).isInstanceOf(TurnResult.LeaseLost.class);
        }

        // 关键：**没有**写 WAITING_USER —— 失去执行权之后一个字都不该再写。
        //
        // 注意这里删的是 Redis 的锁，没有推进 MySQL 的 fencing token，所以中间那些写入
        // 其实还能成功。这正是两套机制的分工：Redis 的锁负责"尽快停手"，
        // MySQL 的号负责"就算没停手也写不进去"。这条测试盯的是前者。
        assertThat(sessions.findById(session.id()).orElseThrow().state())
                .isNotEqualTo(SessionState.WAITING_USER);

        // 最后一条状态是"工具回来了，回到推理"，然后就没了 —— 收尾那一步被跳过了
        assertThat(transitions(session.id())).last()
                .extracting(SessionStateChanged::to)
                .isEqualTo(SessionState.THINKING);
    }

    @Test
    @DisplayName("【不提前推】事务还没提交，落库的事件一条都不推出去 —— 只推那半截流式增量")
    void nothingPersistedIsPushedWhileTheTransactionIsStillOpen() throws Exception {
        // 这个类整个跑在测试事务里，而那个事务**永远不会提交**（跑完回滚），
        // 于是它天然是这条不变量的反例现场：事件照常落库，但一条都不该被推出去 ——
        // 推了的话，订阅者会看到一条库里根本不会存在的事实。
        // 「提交之后确实会推」那一半在真提交的 SessionWriterAnnouncementTest 里。
        Session session = prepareSession("README.md", "codeloom 演示项目\n");
        model.script(
                ScriptedLlm.toolCall("call_1", "read_file", "{\"path\":\"README.md\"}"),
                ScriptedLlm.answer("读到了，是一个演示项目"));

        BlockingQueue<EventEnvelope> live = new LinkedBlockingQueue<>();
        EventBus.Subscription subscription = bus.subscribe(session.id(), live::add);
        List<EventEnvelope> received;
        try {
            executor.send(session.id(), "看一下 README");
            received = collectUntilQuiet(live);
        } finally {
            subscription.close();
        }

        // 这一轮确实写了不少（见这一个类里其余的断言），但带 seq 的一条都没推
        assertThat(readStream(session.id())).isNotEmpty();
        assertThat(received).filteredOn(envelope -> envelope.seq() != null).isEmpty();
        // 流式增量照推 —— 它不过事务，也不该等事务（丢的半截字下次刷新就用落库的那份补上）
        assertThat(received).filteredOn(envelope -> envelope.seq() == null)
                .isNotEmpty()
                .allSatisfy(envelope -> assertThat(envelope.seq()).isNull());
    }

    @Test
    @DisplayName("【思考流式】边想边推，而且**走自己那一类事件** —— 不和正文混在一条通道里")
    void reasoningStreamsOnItsOwnChannel() throws Exception {
        Session session = prepareSession("README.md", "codeloom 演示项目\n");
        model.script(ScriptedLlm.answerWithReasoning(
                "先读 README，再决定怎么答", "读到了，是一个演示项目"));

        List<EventEnvelope> received = runAndCollect(session);

        // 思考走 ReasoningDelta，正文走 AssistantDelta —— 界面上是两个区域，
        // 合成一类再靠负载里的标志位区分的话，前端得先解包才知道往哪儿放
        assertThat(received).filteredOn(e -> e.event() instanceof ReasoningDelta)
                .singleElement()
                .satisfies(e -> {
                    assertThat(((ReasoningDelta) e.event()).text()).contains("先读 README");
                    // 易失事件不带续传游标 —— 带了的话客户端会从一个不存在的 seq 接着拉
                    assertThat(e.seq()).isNull();
                });
        assertThat(received).filteredOn(e -> e.event() instanceof AssistantDelta)
                .singleElement()
                .satisfies(e -> assertThat(((AssistantDelta) e.event()).text()).contains("读到了"));
    }

    @Test
    @DisplayName("【上限一致】超长思考在实时流里也被截到同一个上限 —— 否则刷新前后看到的不一样长")
    void oversizedReasoningIsCappedOnTheLiveChannelToo() throws Exception {
        Session session = prepareSession("README.md", "codeloom 演示项目\n");
        // 落库那边截到 MAX_REASONING_CHARS（见 AgentTurn.abbreviateReasoning）。
        // 实时这边要是原样全推，用户会看到"正在打字时有五万字、刷新后只剩一万六"
        String oversized = "想".repeat(AgentTurn.MAX_REASONING_CHARS + 5_000);
        model.script(ScriptedLlm.answerWithReasoning(oversized, "想完了"));

        List<EventEnvelope> received = runAndCollect(session);

        String streamed = received.stream()
                .filter(e -> e.event() instanceof ReasoningDelta)
                .map(e -> ((ReasoningDelta) e.event()).text())
                .collect(Collectors.joining());

        assertThat(streamed).hasSize(AgentTurn.MAX_REASONING_CHARS);
    }

    private List<EventEnvelope> runAndCollect(Session session) throws InterruptedException {
        BlockingQueue<EventEnvelope> live = new LinkedBlockingQueue<>();
        EventBus.Subscription subscription = bus.subscribe(session.id(), live::add);
        try {
            executor.send(session.id(), "看一下 README");
            return collectUntilQuiet(live);
        } finally {
            subscription.close();
        }
    }

    // ------------------------------------------------------------------

    /**
     * 把队列收干，直到连续 200ms 没有新东西 —— 总线是异步的，固定睡一觉要么不够要么白等。
     */
    private static List<EventEnvelope> collectUntilQuiet(BlockingQueue<EventEnvelope> queue)
            throws InterruptedException {
        List<EventEnvelope> all = new ArrayList<>();
        while (true) {
            EventEnvelope envelope = queue.poll(200, TimeUnit.MILLISECONDS);
            if (envelope == null) {
                return all;
            }
            all.add(envelope);
        }
    }

    /** 造一个真仓库 + 真项目 + 真会话。会话的 worktree 由执行器自己去建。 */
    private Session prepareSession(String fileName, String content) throws IOException {
        return prepareSession(fileName, content, null);
    }

    /**
     * @param teammate 非空的话这个项目有两个成员，他名下也有一条会话 ——
     *                 **协作提醒那条路要两个人才跑得起来**（它报的正是"对方新建了什么"）
     */
    private Session prepareSession(String fileName, String content, UserId teammate)
            throws IOException {
        // 所有者必须是**用户表里真有的人**：打 checkpoint 要用它拿 git 署名，
        // 而同步会把别人的提交原样带进这棵树，所以 git log 里只有署名能分辨是谁做的
        seedOwner();
        if (teammate != null) {
            seedTeammate(teammate);
        }

        Path repo = repoRoot.resolve("repo");
        Files.createDirectories(repo);
        TestGit.run(repo, "init", "-q", "-b", "main");
        // 造在**项目根**里，不是工作区根：agent 的相对路径以项目根为准
        //（见 ProjectLayout）。造在外面的话，工具连看都看不到它 ——
        // 而那些断言会变成"什么都没验"
        Path projectRoot = ProjectLayout.rootBelow(repo);
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve(fileName), content, StandardCharsets.UTF_8);
        TestGit.run(repo, "add", "-A");
        TestGit.run(repo, "-c", "user.name=test", "-c", "user.email=test@codeloom.local",
                "commit", "-q", "-m", "init");

        Project project = new Project(ProjectId.generate(), TestUsers.OWNER, "执行器测试项目",
                repo.toRealPath().toString(),
                teammate == null ? Set.of(TestUsers.OWNER) : Set.of(TestUsers.OWNER, teammate));
        projects.save(project);

        Session session = Session.create(SessionId.generate(), project.id(), TestUsers.OWNER, MODEL);
        sessions.save(session);

        // 队友也要有一条会话（还要有它的树）：协作提醒发给的是**他的会话**那个队列
        if (teammate != null) {
            Session other = Session.create(SessionId.generate(), project.id(), teammate, MODEL);
            sessions.save(other);
            worktrees.save(TestSessions.treeOf(other, worktreeOf(other).toString(), null));
        }
        // 这棵树的登记要在执行器动手**之前**就有：租约是对树发号的（见 WorkspaceFence），
        // 而执行器的第一件事就是抢租约。worktree 目录本身由执行器自己去建
        worktrees.save(TestSessions.treeOf(session, worktreeOf(session).toString(), null));
        return session;
    }

    /**
     * 树的目录怎么摆是 {@link LocalWorkspaceManager} 的规矩 —— **测试问它，而不是自己拼一遍**。
     * 自己拼的那一份在布局变了之后会安静地指向一个不存在的目录，而那些断言会变成"什么都没验"。
     */
    private Path worktreeOf(Session session) {
        return new LocalWorkspaceManager(git, WORKSPACES_ROOT).worktreePath(session.workspaceId());
    }

    /** 落一条 owner 的用户行（每个测试一次，跑完随事务回滚）。 */
    private void seedOwner() {
        if (ownerSeeded) {
            return;
        }
        // 用户名带随机尾巴：它也是唯一键，而库里可能还留着上一次跑的那一行。
        // 前缀留着 —— 断言念的是「来自 Owner…」（见 ContextAssembler 那段投影）
        users.save(new User(TestUsers.OWNER, "owner-" + UUID.randomUUID(),
                TestUsers.PASSWORD_HASH, "Owner-" + UUID.randomUUID().toString().substring(0, 8),
                Instant.now()));
        ownerSeeded = true;
    }

    private boolean ownerSeeded;

    /** 队友那个人。名字要是人能读的 —— 投影按 id 查到它之后写给模型看（见 ContextAssembler）。 */
    private void seedTeammate(UserId teammate) {
        if (!teammatesSeeded.add(teammate)) {
            return;
        }
        users.save(new User(teammate, "mate-" + UUID.randomUUID(),
                TestUsers.PASSWORD_HASH, "队友-" + UUID.randomUUID().toString().substring(0, 8),
                Instant.now()));
    }

    private final Set<UserId> teammatesSeeded = new HashSet<>();

    private List<Event> readStream(SessionId sessionId) {
        return events.readAll(sessionId).stream().map(StoredEvent::event).toList();
    }

    private List<SessionStateChanged> transitions(SessionId sessionId) {
        return readStream(sessionId).stream()
                .filter(SessionStateChanged.class::isInstance)
                .map(SessionStateChanged.class::cast)
                .toList();
    }

    /**
     * 这一轮真的开始跑了吗。
     *
     * <h2>为什么问租约，而不是问登记表</h2>
     * 租约是**产品自己就在用的东西**（拿不到它就停手，见 {@code TurnExecutor}），而且它落在
     * Redis 上：测试那个等待线程看不到未提交的数据库行，但看得见 Redis。登记发生在拿租约
     * 之前，所以"租约在"蕴含"登记好了" —— 那正是这几条用例要等的性质。
     *
     * <p>（从前这里问的是 {@code RunningTurns.isRunning()} —— 那个方法生产里没人调，
     * 是为测试开的一扇窗。）
     */
    private boolean turnHasStarted(Session session) {
        return Boolean.TRUE.equals(redis.hasKey(RedisExecutionLease.keyFor(session.workspaceId())));
    }

    /** worktree 是真实文件系统上的东西，测试跑完要清掉，否则每次都在系统临时目录里留一堆。 */
    @AfterAll
    static void deleteWorktrees() {
        TempDirs.deleteRecursively(WORKSPACES_ROOT);
        TempDirs.deleteRecursively(GIT_SANDBOX);
    }
}
