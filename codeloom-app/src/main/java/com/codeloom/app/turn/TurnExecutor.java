package com.codeloom.app.turn;

import com.codeloom.agent.llm.LlmCallException;
import com.codeloom.agent.llm.TokenUsage;
import com.codeloom.agent.loop.AgentTurn;
import com.codeloom.agent.loop.ContextRepair;
import com.codeloom.agent.loop.TokenBudget;
import com.codeloom.agent.loop.TurnInput;
import com.codeloom.agent.loop.TurnOutcome;
import com.codeloom.agent.model.ModelCapabilitiesResolver;
import com.codeloom.app.context.ContextCompactor;
import com.codeloom.app.note.AgentNotes;
import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.event.WorkspaceChanges;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.port.TurnRequestRepository;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.FileChange;
import com.codeloom.domain.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 执行器：把「一条会话的一轮」从头到尾办完的地方。系统里唯一同时握着四边的东西。
 *
 * <pre>
 *   抢租约 → 起续约看门狗 → 找/建 worktree → 读事件流 → 跑 AgentTurn
 *          → （回调里逐条落库 + 同事务推进状态）→ 收尾状态 → 释放租约
 * </pre>
 *
 * <h2>为什么它必须住在 app</h2>
 * 它要同时用四个模块：租约与事件（realtime）、worktree 与命令（workspace）、
 * 循环与工具（agent）、会话与项目仓储（workspace）。按现在的模块图，
 * {@code app} 是唯一合法能同时看到这四边的地方 —— 别处都会需要新增一条依赖边。
 *
 * <h2>它现在**不**干哪些事</h2>
 * 有三块独立成协作者（都在本包）：{@link TurnInputFactory}（这一轮喂什么给模型）、
 * {@link TurnBroadcaster}（推给正在看的人）、{@link TurnWorkspace}
 * （拿到 worktree、把改动提交成 commit）。它们和执行器的租约/状态机
 * **没有共享状态** —— 上面那张流程图的每一步都留在这里，那三件事只是被它调用一下；
 * 于是执行器**看不见** {@code EventStore}、{@code LlmClientProvider}、
 * {@code CommandPolicy} 这些它本来就不该操心的东西。
 *
 * <h2>三条容易做错、这里刻意做对的</h2>
 * <ol>
 *   <li><b>续约看门狗在跑之前就起。</b> 中途才起的话，一个慢的启动阶段
 *       （拉历史、建 worktree）就可能把租约熬过期，然后整轮都在一个已不属于自己的
 *       会话上跑。
 *   <li><b>事件逐条落库，不是整轮攒完再写。</b> 见
 *       {@link AgentTurn#run(TurnInput, java.util.function.ToLongFunction)} ——
 *       崩溃恢复靠的就是「{@code ToolCallRequested} 在工具开始跑之前已经持久」。
 *   <li><b>失去租约之后一个字都不写，也不释放锁。</b> 写入会被 fencing 拒绝；
 *       而释放之所以也跳过，是因为那时候锁已经不属于我们了 ——
 *       不过要说清楚：{@code release} 的 Lua 脚本会比对 holderId，对不上就返回 0，
 *       所以<b>那条路本来就是安全的空操作</b>，跳过它不是为了防什么，
 *       而是让"这一轮什么都没动"在代码里显式可见。
 * </ol>
 *
 * <h2>用量：只记，不拦</h2>
 * 每一轮的用量都会作为 {@link TurnTokensUsed} 落库（见 {@link #tokensUsed}），
 * 所以"这条会话花了多少、跑的是哪个模型"翻事件流查得到。
 *
 * <p><strong>刻意没有</strong>"累计超限就拦住"那一层：本项目是 BYOK ——
 * 用户填自己的 API key，**花的是他自己的钱**，所以"平台为了控制成本拦用户"这个主体
 * 根本不存在。{@link TokenBudget} 因此只剩单轮那一层，那是**结论不是缺口**：
 * 单轮那层管的是自旋（模型陷进"读文件→改文件→读文件"），和谁付钱无关。
 *
 * <p>崩溃恢复**不自动续跑**：那个开关已经删掉了（见 {@code CrashRecovery}）。
 * 本项目部署形态是宿主机 + 人在键盘前，"接着跑"用户自己说一句就行。
 */
@Component
public class TurnExecutor {

    /** 落进事件的失败原因长度。事件表要长期保存，异常信息再长也没人读。 */
    private static final int REASON_MAX_CHARS = 200;

    private static final Logger log = LoggerFactory.getLogger(TurnExecutor.class);

    private final SessionRepository sessions;
    private final ProjectRepository projects;
    private final ExecutionLease leases;
    private final SessionWriter writer;
    private final RunningTurns turns;
    private final ContextCompactor compactor;
    private final PendingMessages pending;
    private final AgentNotes notes;
    /** 挡住"同一个请求被处理两次"，见 {@link TurnResult.Duplicate}。 */
    private final TurnRequestRepository requests;

    /**
     * 三件各自独立的事，各自一个协作者 —— 见类注释「它现在不干哪些事」。
     * 它们和执行器的租约/状态机**没有共享状态**，各自能被单独读、单独测。
     */
    private final TurnInputFactory inputFactory;
    private final TurnBroadcaster broadcaster;
    private final TurnWorkspace turnWorkspace;
    private final UserRepository users;

    public TurnExecutor(SessionRepository sessions,
                        ProjectRepository projects,
                        ExecutionLease leases,
                        SessionWriter writer,
                        RunningTurns turns,
                        ContextCompactor compactor,
                        PendingMessages pending,
                        AgentNotes notes,
                        TurnRequestRepository requests,
                        TurnInputFactory inputFactory,
                        TurnBroadcaster broadcaster,
                        TurnWorkspace turnWorkspace,
                        UserRepository users) {
        this.sessions = sessions;
        this.projects = projects;
        this.leases = leases;
        this.writer = writer;
        this.turns = turns;
        this.compactor = compactor;
        this.pending = pending;
        this.notes = notes;
        this.requests = requests;
        this.inputFactory = inputFactory;
        this.broadcaster = broadcaster;
        this.turnWorkspace = turnWorkspace;
        this.users = users;
    }

    /** 用户发了话，跑一轮。 */
    public TurnResult send(SessionId sessionId, String userMessage) {
        return send(sessionId, userMessage, null);
    }

    /**
     * 用户发了话，跑一轮；{@code clientMessageId} 用来挡重复请求。
     *
     * <p>幂等检查放在**抢租约之前**：连"忙不忙"都不该问 —— 重复请求的答案不是
     * "等会儿再来"，而是"这件事已经办过了"。放进 run 里的话，一个正在跑的会话
     * 会把重复请求报成 {@code Busy}，客户端就会去重试，而重试只会一直得到 {@code Busy}。
     */
    public TurnResult send(SessionId sessionId, String userMessage, String clientMessageId) {
        Objects.requireNonNull(userMessage, "userMessage");

        // ★ 审批挂着的时候**照样收下这句话**，只是排队 —— 见 TurnResult.Queued。
        //   不能把它打成 FAILED：状态机只允许 AWAITING_APPROVAL → WAITING_USER，
        //   而每一轮开头那一步是迁到 THINKING，那样用户那句话一个字都不落库。
        //
        //   这一句放在**认领幂等键之前**：被排队的这句话还没被处理过，
        //   把它记成"这件事已经办过了"是假的 —— 用户答完批准再打一遍同样的话，
        //   就会撞上一句"这个请求之前已经处理过了"，而它明明没有被处理
        Session early = requireSession(sessionId);
        if (early.state() == SessionState.AWAITING_APPROVAL) {
            return queue(sessionId, userMessage);
        }

        if (clientMessageId != null && !requests.claim(sessionId, clientMessageId)) {
            log.info("会话 {} 收到重复请求 {}，这一轮不跑", sessionId, clientMessageId);
            return new TurnResult.Duplicate(sessionId);
        }
        return run(early, Optional.of(userMessage));
    }

    /**
     * 这一轮还轮不到，先把话排着。
     *
     * <p>{@code ahead} 是它前面还排着几句 —— 调用方拿它说"你前面还有 2 句"，
     * 而不是干巴巴一句"已收下"。用户排了五句的时候，那两句话的区别就是他知不知道
     * 自己排到哪儿了。
     */
    private TurnResult queue(SessionId sessionId, String userMessage) {
        int ahead = pending.size(sessionId);
        pending.enqueue(sessionId, userMessage);
        log.info("会话 {} 这一轮还轮不到，先排着（前面还有 {} 句）", sessionId, ahead);
        return new TurnResult.Queued(sessionId, ahead);
    }

    /**
     * 接着跑一轮，**不追加新的用户消息**。
     *
     * <p>挂起的工具调用被答复之后走这条路（见 {@code ApprovalService}）：那条答复已经
     * 作为事件落库、并充当了那次调用的结果，所以模型需要的是"把历史原样喂回去接着跑"，
     * 而不是一条新的用户消息 —— 伪造一条会污染审计流（看起来像用户说了话），
     * 也会让模型看到一句它本来没收到的话。
     *
     * <p>不抢租约的话调用方要自己先放锁，否则这里是 {@link TurnResult.Busy}。
     */
    public TurnResult resume(SessionId sessionId) {
        return run(sessionId, Optional.empty());
    }

    // ------------------------------------------------------------------

    private TurnResult run(SessionId sessionId, Optional<String> newUserMessage) {
        // **先取会话，再抢租约**：租约是按会话所在的**那棵树**抢的（见 ExecutionLease），
        // 而"哪棵树"要从会话推出来。
        //
        // 顺序反过来还有第二个好处：会话 id 不存在时在这里就失败了，
        // 而不是**先占住一棵树、再发现没这条会话** —— 后者会为一次无效请求
        // 把整棵树锁住到租约到期
        return run(requireSession(sessionId), newUserMessage);
    }

    /**
     * 跑一轮，跑完之后**顺手把排着队的话交付掉**。
     *
     * <p>交付分两步，而且两步必须分在两个租约里：**落库**在 {@code runOnce} 里面
     *（追加事件要持有租约），**接着再跑一轮**在外面（那时候租约已经还回去了）。
     */
    private TurnResult run(Session session, Optional<String> newUserMessage) {
        Attempt attempt = runOnce(session, newUserMessage);
        if (!attempt.continuesWithQueued()) {
            return attempt.result();
        }
        // 会话刚刚回到"等用户说话"，而排着的那几句已经落库了 —— 立刻接着跑一轮
        // 把它们交给模型。**不需要新的用户消息**：它已经在历史里了
        return run(requireSession(session.id()), Optional.empty());
    }

    /** 一轮的结果，外加"要不要接着把排队的交付掉"。 */
    private record Attempt(TurnResult result, boolean continuesWithQueued) {
    }

    /** 会话已经读出来的那一份。{@code send} 那条路要先看一眼状态，不该为此读第二遍。 */
    private Attempt runOnce(Session session, Optional<String> newUserMessage) {
        SessionId sessionId = session.id();

        Optional<LeaseToken> acquired = leases.tryAcquire(session);
        if (acquired.isEmpty()) {
            // 这棵树已经有执行者了 —— **不阻塞**，但也不再让用户自己去重试：
            // 把这句话排着，等那一轮收尾时交付 —— 见 TurnResult.Queued
            //
            // resume 那条路没有新消息可排 —— 它本来就是"接着跑"，
            // 被挡住只能如实说 Busy
            return newUserMessage
                    .map(text -> new Attempt(queue(sessionId, text), false))
                    .orElseGet(() -> new Attempt(new TurnResult.Busy(sessionId), false));
        }
        LeaseToken token = acquired.get();

        // 取消信号由**登记表**发放，而不是由调用方传进来：这样"用户点了中断"那个请求
        // 才有东西可够 —— 它拿不到执行器内部的局部变量，只能通过这张表找到这一轮的 token
        CancellationToken cancellation = turns.register(sessionId);
        LeaseWatchdog watchdog = LeaseWatchdog.start(leases, token, cancellation);

        // 用它跟踪"当前会话"。回调改不了外部局部变量，而 catch 里需要**最新**的那个 ——
        // 拿旧状态去写 FAILED 会把审计流里的迁移记错
        AtomicReference<Session> current = new AtomicReference<>(session);
        // 声明在 try 外面：失败那条路也要用它去提交这一轮已经落到磁盘上的改动
        // （见 fail 里的 commitQuietly）。**它可能一直是 null** —— 连工作区都没建起来时
        Workspace workspace = null;
        try {
            Project project = requireProject(session.projectId());
            workspace = turnWorkspace.ensure(session, project);

            // FAILED 只能先回 IDLE 再进 THINKING（状态机的约束），所以重试是两步
            apply(current, writer.retryFromFailed(current.get(), token));
            apply(current, writer.advance(current.get(), SessionState.THINKING, null, token));

            // 压缩必须赶在用户这条消息**落库之前**。水位线取的是"当前最后一条事件"，
            // 顺序反过来就会把用户刚说的话一起压掉 —— 模型于是看不见这一轮的指令，
            // 表现成"它答非所问"。这是整套里最容易写反的一处
            compactor.compactIfNeeded(current.get(), ContextCompactor.Trigger.PRESSURE,
                            cancellation, token)
                    .ifPresent(written -> apply(current, written));

            // 留言排在用户这条消息**之前**落库：它们是更早发生的（一直在队列里等着），
            // 而且这样模型看到的顺序就是"谁先说的在前"
            applyNotes(sessionId, current, token);

            newUserMessage.ifPresent(s -> apply(current, writer.append(current.get(), new UserMessage(s), token)));

            // 上下文装不下时的补救：**压一次**，然后让循环重发。
            //
            // 走的是 Trigger.OVERFLOW —— 不看阈值（provider 刚给过答案，而我们那个估算器
            // 恰恰是可能错的那一个）。返回"有没有真的落一条压过的记录"：
            // 没落就说明什么都没修动，那时让循环把**原始那个错误**报出去，
            // 比假装"压过了、还是不行"更诚实（见 ContextRepair）
            ContextRepair repairAfterOverflow = () -> compactor
                    .compactIfNeeded(current.get(), ContextCompactor.Trigger.OVERFLOW,
                            cancellation, token)
                    .map(written -> {
                        apply(current, written);
                        return true;
                    })
                    .orElse(false);

            TurnOutcome outcome = new AgentTurn(
                    broadcaster.liveListener(sessionId), repairAfterOverflow).run(
                    inputFactory.create(current.get(), workspace, cancellation),
                    event -> applyOne(current, writer, event, token));

            if (watchdog.leaseLost()) {
                return new Attempt(new TurnResult.LeaseLost(sessionId), false);
            }
            // **一轮结束前把这一轮的改动提交掉**，然后才收尾。见 TurnWorkspace.commit 的注释：
            // 不提交的话，agent 写出来的文件对 git 来说是未跟踪的，而回滚和合并
            // 看到的都是"分支上的提交" —— 于是那两件事都会以为什么都没发生
            TurnWorkspace.Commit commit = turnWorkspace.commit(current.get(), workspace);
            // 收尾：checkpoint + 这一轮的用量 + 状态回到等用户 + 轮次号，一个事务。
            // **停下来的原因**落进 SessionStateChanged.reason，于是"这轮为什么停"
            // （打转了？被取消了？验证没过？）在审计流里看得见。
            //
            // 轮次号推不推**不在这里判**：它由 finalState 推出来（挂着等人批的那一轮
            // 没跑完，不推；批准之后接着跑完时才推），规则在 SessionWriter.completes 那一处
            SessionWriter.Written written = writer.finishTurn(current.get(), commit,
                    outcome.status().name(),
                    finalStateFor(outcome), tokensUsed(outcome), token);
            apply(current, written);

            // ★ 协作提醒：这一轮**新建**了文件的话，告诉项目里其他人的 agent。
            //   放在收尾之后：那时候这轮已经落了库、状态也对，而留言本来就是
            //   "下一轮开头才被领走"的东西（见 AgentNotes），早一句晚一句都不影响。
            //
            //   **只有真的产生了新提交才算数**：这一轮什么都没改时，交出来的位置还是上一个
            //   HEAD（见 TurnWorkspace.Commit.created），拿它去算新建文件会得到上一轮的那几个
            if (commit.created()) {
                // 新建了哪些文件**从刚写下的那条记录里读**（见 createdIn）——
                // 不再问一遍 git，免得同一个提交的改动一轮里算两遍
                announceNewFiles(current.get(), createdIn(written));
            }

            // ★ 交付排队中的那几句。**必须在这里落库** —— 追加事件要持有租约，
            //   而它马上要在 finally 里还回去。过了这一行它们就只能干等着
            //   下一次有人发消息时才被看见，而用户已经在界面上等着了。
            //
            //   接着要不要**再跑一轮**由外层决定：挂在审批上时不能
            //   （状态机只允许 AWAITING_APPROVAL → WAITING_USER）。
            //   那种情况下这几句静静待在历史里，等用户答完批准、resume 那一轮带上它们
            boolean delivered = deliverQueued(current, token);

            return new Attempt(new TurnResult.Completed(outcome),
                    delivered && current.get().state() == SessionState.WAITING_USER);
        } catch (RuntimeException e) {
            return new Attempt(fail(current, e, token, watchdog, workspace), false);
        } finally {
            watchdog.stop();
            turns.unregister(sessionId, cancellation);
            if (!watchdog.leaseLost()) {
                leases.release(token);
            }
        }
    }

    /**
     * 把别的会话送来的留言领进来，落成事件。
     *
     * <p>它们在此之前只是**队列里的字符串**（见 {@code AgentNotes} 的类注释：发留言的
     * 请求不持有租约，落不了库），到这里才成为事实 —— 因为此刻这一轮正持有租约，
     * 走的是和其他事件完全一样的那条写入路径。
     */
    private void applyNotes(SessionId sessionId, AtomicReference<Session> current, LeaseToken token) {
        List<AgentNoteDelivered> drained = notes.drain(sessionId);
        for (int i = 0; i < drained.size(); i++) {
            try {
                apply(current, writer.append(current.get(), drained.get(i), token));
            } catch (RuntimeException e) {
                // 已经落成功的那几条**先确认掉**：不确认的话下一轮 drain 会把它们
                // 重新捞出来，同一句话在事件流里落两遍
                notes.ack(sessionId, drained.subList(0, i));
                // 剩下这些（含刚好失败的那个）挪回队列等下一轮。
                // 用户以为已经发出去的留言，不能因为这一轮出了点事就永远到不了
                notes.requeue(sessionId, drained.subList(i, drained.size()));
                throw e;
            }
        }
        // 全部落成才确认。在那之前它们一直躺在 pending 里，
        // 所以"落了一半就崩"不会让剩下的那些消失
        notes.ack(sessionId, drained);
    }

    /**
     * 把一次写入的结果应用回来：更新游标，然后广播。
     *
     * <p>广播**在这里**而不是在 {@code SessionWriter} 里面 —— 这个方法被调用时，
     * 那个事务已经提交了。"提交之后才推送"这件事，是靠这个调用顺序保证的。
     */
    private void apply(AtomicReference<Session> current, SessionWriter.Written written) {
        current.set(written.session());
        written.events().forEach(broadcaster::publish);
    }

    /**
     * 落一条事件，**并把存储层分配的 seq 还回去**。
     *
     * <p>循环拿它当投影的坐标（见 {@code AgentTurn.Workbench.append}）：喂假的进去，
     * 下一轮按真实 seq 补读时就会判错 —— 同一个事件被折两遍、或者整批被跳过，
     * 而两种都不报错，只是模型看到的上下文悄悄地重复或漏掉一截。
     */
    private long applyOne(AtomicReference<Session> current, SessionWriter writer,
                          PersistentEvent event, LeaseToken token) {
        SessionWriter.Written written = writer.append(current.get(), event, token);
        apply(current, written);
        return written.events().getLast().seq();
    }

    private Session requireSession(SessionId id) {
        return sessions.findById(id).orElseThrow(
                () -> new IllegalArgumentException("会话不存在：" + id));
    }

    private Project requireProject(ProjectId id) {
        return projects.findById(id).orElseThrow(
                () -> new IllegalStateException("会话所属的项目不存在：" + id));
    }

    /**
     * 这一轮新建了哪些文件 —— **从刚写下的那条记录里读**，不回头再问一遍 git。
     *
     * <p>同一个提交的改动一轮里只该算一次（见 {@code WorkspaceChanges}）：算两遍不只慢，
     * 还让"界面上显示的改动"和"提醒对方的那句话"有机会不一致 —— 而它们是同一件事。
     *
     * <p>路径是**项目相对**的（记录里就是那个形状），正好也是给人看的那个形状。
     */
    private static List<String> createdIn(SessionWriter.Written written) {
        return written.events().stream()
                .map(StoredEvent::event)
                .filter(WorkspaceChanges.class::isInstance)
                .map(WorkspaceChanges.class::cast)
                .flatMap(record -> record.files().stream())
                .filter(FileChange::created)
                .map(FileChange::path)
                .toList();
    }

    /**
     * 这一轮**新建**了文件的话，告诉项目里其他人的 agent。
     *
     * <h2>为什么需要它</h2>
     * A 让 agent 写鉴权、B 也让 agent 写鉴权 —— 两边各写一份，合并的时候才撞上。
     * 而两边的树是**分开的**：B 那边 {@code AuthService.java} 根本不存在，
     * 所以没有任何现成的机制能让他知道。这一条补的就是那个信息差。
     *
     * <h2>三条边界</h2>
     * <ul>
     *   <li><b>只报新建</b>（见 {@link FileChange#created}）：新建一个文件几乎总意味着
     *       "我要做一块新东西"，而改一个已有文件可能只是修个 typo。全报的话，
     *       对方连收十轮就把它当背景噪声了</li>
     *   <li><b>只提醒，不拦</b>：该不该重复做是**人**的判断。平台替两个人决定
     *       "这块归谁"是越界的，而这个产品的平等协作正是建立在"谁都不替谁做主"上</li>
     *   <li><b>不经用户确认，自动发</b>：这正是它有用的地方 —— 等 A 想起来说一句
     *       "我在做鉴权"，B 的 agent 早就写完一圈设计了</li>
     * </ul>
     *
     * <h2>发给哪条会话</h2>
     * 发给对方**轮次最多的那条**。服务器不知道他"当前"开着哪一条（会话表里没有
     * 时间列），而轮次最多是他的主力会话 —— 这是个近似，但比"最新建的"准：
     * 刚建出来还没用过的那条，不可能是他正在做事的会话。
     */
    private void announceNewFiles(Session session, List<String> created) {
        if (created.isEmpty()) {
            return;
        }

        Project project = projects.findById(session.projectId()).orElse(null);
        if (project == null) {
            return;
        }
        // 正文里**不带名字**：名字由读的那一侧按 id 现查 —— 界面那条前缀、投影里那句
        // "[来自 X 的 agent 的留言]"。写进正文等于同一句话里说两遍（界面上就是
        // "小明 的 agent 捎来一句：小明 刚新建了…"），而且改过名之后永远对不上
        //（见 AgentNoteDelivered）
        String text = "刚新建了 " + String.join("、", created)
                + "（还没合进主干）。如果你正在做的事和这是一块，先跟他说一声，别各写一份。";

        for (UserId member : project.members()) {
            if (member.equals(session.ownerId())) {
                continue;
            }
            busiestSessionOf(member, project.id()).ifPresent(target ->
                    notes.enqueue(target, new AgentNoteDelivered(session.id(), session.ownerId(), text)));
        }
    }

    /** 这个人在这个项目里轮次最多的那条会话。见 {@link #announceNewFiles} 里那段说明。 */
    private Optional<SessionId> busiestSessionOf(UserId owner, ProjectId project) {
        return sessions.findByProject(project, SESSION_SCAN, 0).stream()
                .filter(candidate -> candidate.ownerId().equals(owner))
                .max(Comparator.comparingInt(Session::turnIndex))
                .map(Session::id);
    }

    /**
     * 找"他那条主力会话"时一次拉多少条。
     *
     * <p>直接拉全量：会话表的注释里写着这个数量级很小（一个人的会话是几条到几十条），
     * 而分页只会让这段代码多一个没人会去调的循环。
     */
    private static final int SESSION_SCAN = 200;

    /**
     * 把排队中的用户消息落库。**调用方必须持有这一轮的租约。**
     *
     * <p>落成**普通的 {@code UserMessage}**，不用另造一种事件：它的语义本来就一样 ——
     * "用户说了这句话"。区别只在于它什么时候被交给模型，而那是靠"它落在哪一轮"
     * 决定的（排在后面，所以下一轮才看得见）。
     *
     * <p>{@code TurnStates.after(UserMessage)} 是空的，所以它**不会动会话状态** ——
     * 这一条要紧：会话正挂在审批上时落这条，不会把它从 AWAITING_APPROVAL 推走。
     *
     * @return 交付了几条
     */
    private boolean deliverQueued(AtomicReference<Session> current, LeaseToken token) {
        List<String> queued = pending.drain(current.get().id());
        for (String text : queued) {
            apply(current, writer.append(current.get(), new UserMessage(text), token));
        }
        return !queued.isEmpty();
    }

    /**
     * 尽力把会话记成 {@code FAILED}，但**绝不因此把真正的原因盖掉**。
     *
     * <p>失败时最该被看见的是"为什么失败"，而不是"我连记录失败都失败了"。
     * 后者作为 suppressed 挂在原异常上，需要时看得到，平时不抢眼。
     */
    private TurnResult fail(AtomicReference<Session> current, RuntimeException cause, LeaseToken token,
                            LeaseWatchdog watchdog, Workspace workspace) {
        if (watchdog.leaseLost()) {
            return new TurnResult.LeaseLost(token.sessionId());
        }

        // 这一轮**已经落到磁盘上的改动先提交掉**，好让它有一个可回的位置 ——
        // 不提交的话那些文件对 git 来说是未跟踪的，而 reset --hard 不碰未跟踪文件，
        // 回滚会以为什么都没发生。失败和用户取消都要走这一步
        TurnWorkspace.Commit commit = commitQuietly(current, workspace);

        // **用户取消不是失败。**
        //
        // LlmCallException 自己就把 CANCELLED 单列了一类，注释写着"不是失败，上层不该
        // 当错误报给用户"—— 但流式读取那条路是**抛异常**出来的，到了这儿就成了普通错误：
        // 会话被打成 FAILED、界面上一行红字加一个 Java 异常类名。
        //
        // 所以这里按**正常收尾**走：回到等用户指示（同 finalStateFor 里那条"会话停在那儿
        // 是常态"），事件里记的是谁停了它，不是异常原文。
        if (cause instanceof LlmCallException llm && llm.isCancelled()) {
            if (current.get() != null) {
                try {
                    // reason 写 **{@code CANCELLED}**，和正常收尾那条路（{@code finishTurn}
                    // 传的 {@code outcome.status().name()}）是同一个值 —— 界面上要认的就是它。
                    // 这里另起一句中文的话，前端就得靠文案去比对，那种判据改动一次就断
                    //
                    // 走 abortTurn 而不是 advance：后者只移状态机，前者顺带打 checkpoint、
                    // 推轮次号 —— 用户按了停止，这次交互对他来说是结束了（见 SessionWriter.completes）
                    apply(current, writer.abortTurn(current.get(), commit,
                            SessionState.WAITING_USER, TurnOutcome.Status.CANCELLED.name(), token));
                } catch (RuntimeException transitionFailure) {
                    cause.addSuppressed(transitionFailure);
                }
            }
            return new TurnResult.Interrupted();
        }

        String reason = abbreviate(cause.toString());
        if (current.get() != null) {
            try {
                // 同上：失败也是一次交互的结束，同样有自己的位置
                apply(current, writer.abortTurn(current.get(), commit,
                        SessionState.FAILED, reason, token));
            } catch (RuntimeException transitionFailure) {
                cause.addSuppressed(transitionFailure);
            }
        }
        return new TurnResult.Failed(reason);
    }

    /**
     * 尽力把这一轮已经落到磁盘上的改动提交掉。**提交不了就放弃，返回 null。**
     *
     * <p>不抛异常是刻意的，理由和 {@link #fail} 那条一样：正在处理的是"这一轮为什么失败"，
     * 而"我连提交都失败了"绝不能盖掉它 —— 那会让人对着一句 git 报错查半天，
     * 而真正的原因写在 suppressed 里没人看。
     *
     * <p>拿不到工作区（连建都没建起来）时同样返回 null。那种情况下这个位置确实没有
     * 可回的点，{@code SessionWriter.abortTurn} 会跳过 checkpoint 只收尾。
     */
    private TurnWorkspace.Commit commitQuietly(AtomicReference<Session> current, Workspace workspace) {
        if (workspace == null || current.get() == null) {
            return null;
        }
        try {
            return turnWorkspace.commit(current.get(), workspace);
        } catch (RuntimeException e) {
            log.warn("会话 {} 收尾时提交失败，这一轮不会有 checkpoint", current.get().id(), e);
            return null;
        }
    }

    /**
     * 这一轮跑完之后，会话停在哪。
     *
     * <p>只有"挂着等人批"是例外 —— 它没有收尾完毕，所以停在 {@code AWAITING_APPROVAL}；
     * 答复到了再由那条路径把它推到 {@code WAITING_USER} 并续跑。
     * 其余情况一律是 {@code WAITING_USER}（会话停在那里是常态，不是异常）。
     */
    private static SessionState finalStateFor(TurnOutcome outcome) {
        return outcome.status() == TurnOutcome.Status.AWAITING_APPROVAL
                ? SessionState.AWAITING_APPROVAL
                : SessionState.WAITING_USER;
    }

    /**
     * 把这一轮的用量翻译成事件。
     *
     * <p>{@code model} 记的是**响应里回报的**那个，不是请求时写的那个 —— 两者可能不同
     * （别名、路由），而账上该记的是真正跑了的那个。模型还没开口时
     * {@code outcome.model()} 就等于请求里的名字，所以两种情况都说得通。
     */
    private static TurnTokensUsed tokensUsed(TurnOutcome outcome) {
        TokenUsage usage = outcome.usage();
        // 上下文那两项来自**最后一次调用**，不是这一轮的累计 —— 见 TurnOutcome#lastCallUsage。
        // 窗口按**服务商回报的模型**查（和 model 那一列同源），不是请求时写的那个：
        // 别名会被映射，而两个名字的窗口可能不一样
        //
        // **一次都没调成的时候给 null，不给 0**：那两样是"没有读数"，
        // 而 0 是个像真值的假值（"上下文是 0"不可能发生）。判断在 TokenUsage 自己身上
        // （isKnown）—— 服务商没报用量时，那次调用的数同样是不可用的
        TokenUsage last = outcome.lastCallUsage();
        Integer contextTokens = last.isKnown() ? last.inputTokens() : null;
        Integer contextWindow = last.isKnown()
                ? ModelCapabilitiesResolver.resolve(outcome.model()).contextWindow()
                : null;
        return new TurnTokensUsed(usage.inputTokens(), usage.outputTokens(),
                usage.reasoningTokens(), usage.cachedInputTokens(),
                contextTokens, contextWindow, outcome.model());
    }

    private static String abbreviate(String value) {
        return value.length() <= REASON_MAX_CHARS
                ? value
                : value.substring(0, REASON_MAX_CHARS) + "…";
    }

    // ------------------------------------------------------------------

    /**
     * 续约看门狗：按租约给的节奏续期，续不上就**取消这一轮**。
     *
     * <p>为什么续不上必须当场停，而不是"跑完再说"：后续写入确实会被 fencing token 拒绝，
     * 但**工作区里的文件改动不会被拒绝** —— 继续跑下去，就是在一条已经不属于自己的会话上
     * 改代码。事件被挡住了，文件却真的被改了，那是最难收拾的一种状态。
     *
     * <p>跑在虚拟线程上：它整段时间都在睡，正是虚拟线程最擅长的负载。
     */
    private static final class LeaseWatchdog {

        private static final long STOP_TIMEOUT_MS = 2_000;

        private final AtomicBoolean leaseLost = new AtomicBoolean(false);
        private final Thread thread;

        static LeaseWatchdog start(ExecutionLease leases, LeaseToken token,
                                   CancellationToken cancellation) {
            return new LeaseWatchdog(leases, token, cancellation);
        }

        private LeaseWatchdog(ExecutionLease leases, LeaseToken token, CancellationToken cancellation) {
            Duration interval = leases.renewInterval();
            this.thread = Thread.ofVirtual()
                    .name("lease-renew-" + token.sessionId())
                    .start(() -> renewLoop(leases, token, cancellation, interval));
        }

        private void renewLoop(ExecutionLease leases, LeaseToken token,
                               CancellationToken cancellation, Duration interval) {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (leases.renew(token)) {
                    continue;
                }
                // 已经失去执行权。取消的话，循环会在下一个边界上停下来
                //（每一轮模型调用前、每一个工具调用前都检查）
                leaseLost.set(true);
                cancellation.cancel();
                return;
            }
        }

        /** 本轮中途是否失去了执行权。决定收尾时能不能写、能不能释放锁。 */
        boolean leaseLost() {
            return leaseLost.get();
        }

        void stop() {
            thread.interrupt();
            try {
                thread.join(STOP_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
