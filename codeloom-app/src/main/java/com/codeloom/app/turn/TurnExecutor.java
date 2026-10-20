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
 * 执行器：把一条会话的一轮从头办完，是系统里唯一同时握着四边的东西。
 *
 * <p>流程：抢租约、起续约看门狗、找或建工作区、读事件流、跑 AgentTurn（回调逐条落库并
 * 同事务推进状态）、收尾、释放租约。必须住在 app：它同时使用 realtime（租约、事件）、
 * workspace（worktree、命令、仓储）和 agent（循环、工具），模块图中只有 app 能看到这三边。
 *
 * <p>三块独立协作者本包内：{@link TurnInputFactory} 决定这一轮喂什么，
 * {@link TurnBroadcaster} 推给正在看的人，{@link TurnWorkspace} 取工作区并把改动提交。
 * 它们与租约和状态机没有共享状态，执行器因此看不见 {@code EventStore}、
 * {@code LlmClientProvider}、{@code CommandPolicy} 等不该操心的东西。
 *
 * <p>三条刻意的顺序：看门狗在开始跑之前启动，否则慢启动（拉历史、建工作区）可能把租约熬过期；
 * 事件逐条落库而不是整轮攒完，崩溃恢复依赖 {@code ToolCallRequested} 在工具开始前已持久；
 * 失去租约后既不写也不释放锁 —— 写入会被 fencing 拒绝，而 release 因 holderId 不匹配本就是
 * 安全的空操作，跳过只是让"这一轮什么都没动"显式可见。
 *
 * <p>用量只记录不拦截：本项目 BYOK，花的是用户自己的钱，不存在平台控制成本这一层；
 * {@link TokenBudget} 只剩单轮，用于防止自旋。崩溃恢复也不自动续跑（见 {@code CrashRecovery}）。
 */
@Component
public class TurnExecutor {

    /** 落进事件的失败原因长度；事件表长期保存，过长的异常信息没人读。 */
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
     * 三件独立的事各有一个协作者，与租约和状态机没有共享状态，可单独阅读和测试（见类注释）。
     */
    private final TurnInputFactory inputFactory;
    private final TurnBroadcaster broadcaster;
    private final TurnWorkspace turnWorkspace;

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
                        TurnWorkspace turnWorkspace) {
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
    }

    /**
     * 用户发了话，跑一轮。
     *
     * @param userMessage 消息正文
     * @return 本轮结果
     */
    public TurnResult send(SessionId sessionId, String userMessage) {
        return send(sessionId, userMessage, null);
    }

    /**
     * 用户发了话，跑一轮；{@code clientMessageId} 用于挡重复请求。
     *
     * <p>幂等检查在抢租约之前：重复请求的答案不是"等会儿再来"，而是"这件事已办过"。
     * 放进 run 里的话，正在跑的会话会把重复请求报成 {@code Busy}，客户端重试只会一直得到
     * {@code Busy}。
     *
     * @param clientMessageId 客户端请求标识；null 表示不查重
     * @return 本轮结果
     */
    public TurnResult send(SessionId sessionId, String userMessage, String clientMessageId) {
        Objects.requireNonNull(userMessage, "userMessage");

        // 审批挂着时照样收下这句话，只是排队（见 TurnResult.Queued）：打成 FAILED 不行，
        // 状态机只允许 AWAITING_APPROVAL → WAITING_USER，而每轮开头要迁到 THINKING，
        // 那样这句话一个字都不会落库。
        // 这也必须在认领幂等键之前：被排队的话尚未处理，记成"已办过"是假的，
        // 用户答完批准再打一遍会撞上"这个请求之前已经处理过了"
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
     * 这一轮还轮不到，先把话排着。{@code ahead} 让调用方说出"前面还有几句"，
     * 用户才知道自己排到了哪里。
     */
    private TurnResult queue(SessionId sessionId, String userMessage) {
        int ahead = pending.size(sessionId);
        pending.enqueue(sessionId, userMessage);
        log.info("会话 {} 这一轮还轮不到，先排着（前面还有 {} 句）", sessionId, ahead);
        return new TurnResult.Queued(sessionId, ahead);
    }

    /**
     * 接着跑一轮，不追加新的用户消息。
     *
     * <p>挂起的工具调用被答复后走这条路（见 {@code ApprovalService}）：答复已作为事件落库、
     * 充当调用结果，模型需要的是把历史原样喂回去；伪造一条用户消息会污染审计流，
     * 也会让模型看到它没收到的话。
     *
     * <p>不抢租约时调用方要先放锁，否则返回 {@link TurnResult.Busy}。
     *
     * @return 本轮结果
     */
    public TurnResult resume(SessionId sessionId) {
        return run(sessionId, Optional.empty());
    }

    // ------------------------------------------------------------------

    private TurnResult run(SessionId sessionId, Optional<String> newUserMessage) {
        // 先取会话再抢租约：租约按会话所在的树发放，而树要从会话推出。
        // 反过来会在会话 id 不存在时先占住整棵树，直到租约到期
        return run(requireSession(sessionId), newUserMessage);
    }

    /**
     * 跑一轮，结束后把排队的消息交付掉。交付分两步且必须在两个租约里：落库在
     * {@code runOnce} 内（追加事件需要租约），接着再跑一轮在外层（此时租约已归还）。
     */
    private TurnResult run(Session session, Optional<String> newUserMessage) {
        Attempt attempt = runOnce(session, newUserMessage);
        if (!attempt.continuesWithQueued()) {
            return attempt.result();
        }
        // 会话刚刚回到"等用户说话"，而排着的那几句已经落库了 —— 立刻接着跑一轮
        // 把它们交给模型；不需要新的用户消息，它已经在历史里了
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
            // 树已有执行者：不阻塞，把新消息排着，等那一轮收尾时交付（见 TurnResult.Queued）。
            // resume 没有新消息可排，只能如实返回 Busy
            return newUserMessage
                    .map(text -> new Attempt(queue(sessionId, text), false))
                    .orElseGet(() -> new Attempt(new TurnResult.Busy(sessionId), false));
        }
        LeaseToken token = acquired.get();

        // 取消信号由登记表发放，而不是由调用方传入：中断请求拿不到执行器内部的局部变量，
        // 只能通过这张表找到这一轮的 token
        CancellationToken cancellation = turns.register(sessionId);
        LeaseWatchdog watchdog = LeaseWatchdog.start(leases, token, cancellation);

        // 跟踪当前会话：回调改不了外部局部变量，而 catch 需要最新状态去写 FAILED。
        // 租约只挡住后来的写者，不会让请求开头的快照变新；等锁期间可能有另一轮推进了
        // turn_index，拿旧快照去 advance/settle 会把号写回去。所以拿锁后先重读会话
        AtomicReference<Session> current = new AtomicReference<>();
        // 声明在 try 外：失败路径也要用它提交本轮已写到磁盘的改动（见 commitQuietly）。
        // 它可能一直是 null —— 连工作区都没建起来时
        Workspace workspace = null;
        try {
            current.set(requireSession(sessionId));
            // 等锁期间会话可能停在等人批。消息仍应排队，不能拿 AWAITING_APPROVAL
            // 去走 advance(THINKING)，那会撞状态机
            if (current.get().state() == SessionState.AWAITING_APPROVAL
                    && newUserMessage.isPresent()) {
                return new Attempt(queue(sessionId, newUserMessage.get()), false);
            }
            Project project = requireProject(current.get().projectId());
            workspace = turnWorkspace.ensure(current.get(), project);

            // FAILED 只能先回 IDLE 再进 THINKING，所以重试是两步
            apply(current, writer.retryFromFailed(current.get(), token));
            apply(current, writer.advance(current.get(), SessionState.THINKING, null, token));

            // 压缩必须赶在用户消息落库之前：水位线取当前最后一条事件，顺序反了会把
            // 用户刚说的话一起压掉，模型看不见本轮指令，表现成答非所问
            compactor.compactIfNeeded(current.get(), ContextCompactor.Trigger.PRESSURE,
                            cancellation, token)
                    .ifPresent(written -> apply(current, written));

            // 留言排在用户消息之前落库：它们发生得更早，模型看到的顺序应是先说的在前
            applyNotes(sessionId, current, token);

            newUserMessage.ifPresent(s -> apply(current, writer.append(current.get(), new UserMessage(s), token)));

            // 上下文溢出时的补救：压一次，然后让循环重发。走 Trigger.OVERFLOW 不看阈值，
            // 因为服务商刚报过错、而估算器可能不准。返回值表示是否真的落了压缩记录；
            // 没落就让循环报原始错误，比假装"压过了仍不行"更诚实（见 ContextRepair）
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
            // 收尾前先提交本轮改动（见 TurnWorkspace.commit）：不提交的话 agent 写出的文件
            // 对 git 是未跟踪的，而回滚和合并都只看分支上的提交，会以为什么都没发生
            TurnWorkspace.Commit commit = turnWorkspace.commit(current.get(), workspace);
            // 收尾：checkpoint、用量、状态、轮次号在同一个事务里。停止原因落进
            // SessionStateChanged.reason，审计流里看得见本轮为什么停。
            // 轮次号是否推进由 finalState 决定，规则在 SessionWriter.completes
            SessionWriter.Written written = writer.finishTurn(current.get(), commit,
                    outcome.status().name(),
                    finalStateFor(outcome), tokensUsed(outcome), token);
            apply(current, written);

            // 本轮新建了文件时提醒项目里其他人的 agent。放在收尾之后：此时已落库、状态也对，
            // 而留言本来就在下一轮开头才被领走（见 AgentNotes）。
            // 只有真的产生了新提交才算，否则拿到的是上一个 HEAD 的新建文件
            if (commit.created()) {
                // 从刚写下的记录里读，不再问一遍 git，避免同一提交的改动一轮算两遍
                announceNewFiles(current.get(), createdIn(written));
            }

            // 排队消息必须在这里落库：追加事件需要租约，而它马上要在 finally 里归还；
            // 过了这一行就只能等下次有人发消息才被看见。是否接着再跑一轮由外层决定：
            // 挂在审批上时不能（状态机只允许 AWAITING_APPROVAL → WAITING_USER），
            // 那几句会留在历史里，等批准后的 resume 带上
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
     * 把别的会话送来的留言落成事件。
     *
     * <p>在此之前它们只是队列里的字符串（发留言的请求不持有租约，落不了库）；到这里才成为
     * 事实，因为本轮持有租约，走的是和其他事件相同的写入路径。
     */
    private void applyNotes(SessionId sessionId, AtomicReference<Session> current, LeaseToken token) {
        List<AgentNoteDelivered> drained = notes.drain(sessionId);
        for (int i = 0; i < drained.size(); i++) {
            try {
                apply(current, writer.append(current.get(), drained.get(i), token));
            } catch (RuntimeException e) {
                // 先确认已成功的几条：否则下一轮 drain 会重新捞出它们，同一句话落两遍
                notes.ack(sessionId, drained.subList(0, i));
                // 剩余（含刚失败的那条）挪回队列等下一轮；用户以为已发出的留言不能因此丢失
                notes.requeue(sessionId, drained.subList(i, drained.size()));
                throw e;
            }
        }
        // 全部落成才确认；在那之前它们留在 pending 里，落一半崩溃也不会让剩余消失
        notes.ack(sessionId, drained);
    }

    /**
     * 把写入结果应用回来，只更新游标。广播不在这里：写入自己在事务提交后宣布
     * （见 {@code SessionWriter.Written}）。
     */
    private void apply(AtomicReference<Session> current, SessionWriter.Written written) {
        current.set(written.session());
    }

    /**
     * 落一条事件并返回存储层分配的 seq。
     *
     * <p>循环拿它当投影坐标（见 {@code AgentTurn.Workbench.append}）：返回假 seq 会让下一轮
     * 按真实 seq 补读时判错，同一事件折两遍或整批跳过，两种都不报错，只是模型上下文悄悄
     * 重复或缺一段。
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
     * 本轮新建了哪些文件，从刚写下的记录里读，不再问 git。同一提交的改动一轮只该算一次
     * （见 {@code WorkspaceChanges}）：算两遍不仅慢，还可能让界面显示的改动和提醒内容不一致。
     * 路径是项目相对的，也是给人看的形状。
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
     * 本轮新建了文件时，通知项目里其他人的 agent。
     *
     * <p>需要它是因为两边的树分开：A 和 B 各自让 agent 写同一块功能时，B 那边根本看不到 A 的
     * 新文件，只会在合并时撞上。三条边界：只报新建（改已有文件可能只是改个 typo，全报会变成
     * 噪声）；只提醒不拦（是否重复做由人判断，平台不替人分配工作）；自动发送、不经确认
     * （等 A 想起来说一句时，B 的 agent 早已做完一轮设计）。
     *
     * <p>发给对方轮次最多的那条会话：服务端不知道他当前打开哪条（会话表没有时间列），
     * 而轮次最多的是主力会话；刚建好还没用过的会话不可能是他正在做事的那条。
     */
    private void announceNewFiles(Session session, List<String> created) {
        if (created.isEmpty()) {
            return;
        }

        Project project = projects.findById(session.projectId()).orElse(null);
        if (project == null) {
            return;
        }
        // 正文不带名字：名字由读取方按 id 现查（界面前缀、投影里的"[来自 X 的 agent 的留言]"），
        // 写进正文会重复，且改名后永远对不上（见 AgentNoteDelivered）
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

    /** 这个人在这个项目里轮次最多的会话（见 {@link #announceNewFiles}）。 */
    private Optional<SessionId> busiestSessionOf(UserId owner, ProjectId project) {
        return sessions.findByProject(project, SESSION_SCAN, 0).stream()
                .filter(candidate -> candidate.ownerId().equals(owner))
                .max(Comparator.comparingInt(Session::turnIndex))
                .map(Session::id);
    }

    /**
     * 查找主力会话时一次拉多少条。取前 200 条，不翻页：会话数量很小（每人几条到
     * 几十条），分页只会多一个没人会调的循环；超过这个数时只在按 id 排序的前 200 条里挑。
     */
    private static final int SESSION_SCAN = 200;

    /**
     * 把排队中的用户消息落库；调用方必须持有本轮租约。
     *
     * <p>落成普通的 {@code UserMessage}，不另造事件：语义同样是"用户说了这句话"，
     * 区别只是交付时机，而那由它落在哪一轮决定（排在后面，下一轮才可见）。
     * {@code TurnStates.after(UserMessage)} 为空，因此不会改变会话状态 ——
     * 会话挂在审批上时也不会被推走。
     *
     * @return 是否交付了消息
     */
    private boolean deliverQueued(AtomicReference<Session> current, LeaseToken token) {
        List<String> queued = pending.drain(current.get().id());
        for (String text : queued) {
            apply(current, writer.append(current.get(), new UserMessage(text), token));
        }
        return !queued.isEmpty();
    }

    /**
     * 尽力把会话记成 {@code FAILED}，但绝不让记录失败盖掉真正的原因。
     *
     * <p>失败时最该被看见的是"为什么失败"，而不是"记录失败也失败了"；后者作为 suppressed
     * 挂在原异常上，需要时可见。
     */
    private TurnResult fail(AtomicReference<Session> current, RuntimeException cause, LeaseToken token,
                            LeaseWatchdog watchdog, Workspace workspace) {
        if (watchdog.leaseLost()) {
            return new TurnResult.LeaseLost(token.sessionId());
        }

        // 先把本轮已写到磁盘的改动提交掉，给它一个可回的位置；不提交的话这些文件是未跟踪的，
        // reset --hard 不会碰，回滚会以为什么都没发生。失败和取消都要走这一步
        TurnWorkspace.Commit commit = commitQuietly(current, workspace);

        // 用户取消不是失败。LlmCallException 已单列 CANCELLED，但流式读取以异常抛出，
        // 到这里会变成普通错误：会话 FAILED、界面一行红字加异常类名。
        // 因此按正常收尾走：回到 WAITING_USER，事件记录谁停了它，而不是异常原文
        if (cause instanceof LlmCallException llm && llm.isCancelled()) {
            if (current.get() != null) {
                try {
                    // reason 写 CANCELLED，与正常收尾的 outcome.status().name() 同值，
                    // 前端按它识别；另起中文文案会让前端靠文案比对，改一次就断。
                    // 用 abortTurn 而不是 advance：前者还打 checkpoint、推轮次号
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
                // 失败同样意味着一次交互结束，也有自己的位置
                apply(current, writer.abortTurn(current.get(), commit,
                        SessionState.FAILED, reason, token));
            } catch (RuntimeException transitionFailure) {
                cause.addSuppressed(transitionFailure);
            }
        }
        return new TurnResult.Failed(reason);
    }

    /**
     * 尽力提交本轮已写到磁盘的改动；提交不了就返回 null，不抛异常。
     *
     * <p>理由同 {@link #fail}：正在处理"这一轮为什么失败"，"提交也失败了"不能盖掉它。
     * 拿不到工作区时同样返回 null，此时确实没有可回的点，
     * {@code SessionWriter.abortTurn} 会跳过 checkpoint 只收尾。
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
     * 本轮结束后会话停在哪。只有挂着等人批例外，停在 {@code AWAITING_APPROVAL}，答复到达后
     * 再推到 {@code WAITING_USER} 并续跑；其余一律 {@code WAITING_USER}，这是常态而非异常。
     */
    private static SessionState finalStateFor(TurnOutcome outcome) {
        return outcome.status() == TurnOutcome.Status.AWAITING_APPROVAL
                ? SessionState.AWAITING_APPROVAL
                : SessionState.WAITING_USER;
    }

    /**
     * 把本轮用量翻译成事件。
     *
     * <p>{@code model} 记录响应里回报的模型，而不是请求时写的：别名或路由可能让两者不同，
     * 账上要记真正跑的那个。模型未开口时它等于请求名，两种情况都成立。
     */
    private static TurnTokensUsed tokensUsed(TurnOutcome outcome) {
        TokenUsage usage = outcome.usage();
        // 上下文两项来自最后一次调用而非本轮累计（见 TurnOutcome#lastCallUsage）；
        // 窗口按服务商回报的模型查，别名映射后的窗口可能与请求名不同。
        // 一次都没调成时给 null 而不是 0：0 是像真值的假值（"上下文是 0"不可能发生）。
        // 判断在 TokenUsage.isKnown —— 服务商没报用量时同样不可用
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
     * 续约看门狗：按租约给出的节奏续期，续不上就取消本轮。
     *
     * <p>必须当场停，不能跑完再说：后续写入会被 fencing token 拒绝，但工作区里的文件改动
     * 不受 fencing 保护，继续跑就是在一条已不属于自己的会话上改代码 —— 事件被挡住、文件却
     * 真被改了，是最难收拾的状态。它整段时间都在睡眠，跑在虚拟线程上。
     *
     * <p>不是 private：验证"续约失败即放弃本轮"需要塞入一个续约会失败或抛异常的租约，
     * 而在本包内做得到。
     */
    static final class LeaseWatchdog {

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
                boolean renewed;
                try {
                    renewed = leases.renew(token);
                } catch (RuntimeException e) {
                    // 续约自身抛异常与"续约被拒"同义：已不知道是否还持锁（例如 Redis 中途挂掉）。
                    // 放过去会让线程安静死掉、leaseLost 永远为假，本轮以为锁还在而跑完；
                    // 写入有 fencing 挡着，但工作区改动没有任何东西挡
                    log.warn("会话 {} 的租约续不上，按失去执行权处理", token.sessionId(), e);
                    renewed = false;
                }
                if (renewed) {
                    continue;
                }
                // 已失去执行权；取消后循环会在下一个工具边界停下
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
