package com.codeloom.app.turn;

import com.codeloom.app.project.ProjectLayout;
import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.ModelChanged;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.SessionStarted;
import com.codeloom.domain.event.SessionStateChanged;
import com.codeloom.domain.event.SessionSynced;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolInterrupted;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.event.WorkspaceChanges;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionState;
import com.codeloom.domain.session.TurnStates;
import com.codeloom.domain.workspace.FileChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 追加事件并推进背后数据行的唯一实现点，两件事在同一事务里。
 *
 * <p>落库后的宣布也从这里走（见 {@link Written}）：全项目只有这个类写事件流，
 * "谁落库谁负责让它被看到"因此有唯一落点。宣布时机见 {@link EventAnnouncer}。
 *
 * <p>背后的行分两类：会话行（{@code state}、{@code turn_index}、模型配置）是会话的属性；
 * 工作区行的 {@code head_commit} 是树的属性，同一人的两条会话写同一行，
 * 这是"换会话不换代码"的落点。
 *
 * <p>必须是独立 bean：{@code @Transactional} 只在跨 bean 调用时生效，而事件与行必须绑定。
 * 执行器也不开整轮事务：那会把行锁持有到整轮结束，租约到期、别的实例接管时会被一把已不属于
 * 自己的锁堵住，以锁超时收场并掩盖真实原因。因此每次调用是一小段事务，一次一条事件。
 */
@Component
public class SessionWriter {

    private static final Logger log = LoggerFactory.getLogger(SessionWriter.class);

    /**
     * 一条 {@code WorkspaceChanges} 最多记录的文件数，防止事件被撑得过大：一次格式化可能改动
     * 整个仓库，而事件正文会流经每个读取方。超出时只记前一批，{@code truncated} 为 true。
     */
    private static final int MAX_RECORDED_FILES = 500;

    private final EventStore events;
    private final SessionRepository sessions;
    private final WorkspaceRepository workspaces;
    private final WorkspaceManager trees;
    private final EventAnnouncer announcer;

    public SessionWriter(EventStore events, SessionRepository sessions, WorkspaceRepository workspaces,
                         WorkspaceManager trees, EventAnnouncer announcer) {
        this.events = events;
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.trees = trees;
        this.announcer = announcer;
    }

    /**
     * 一次写入的产物：推进后的会话，以及这次真正落库的事件。
     *
     * <p>构造即宣布：构造函数把事件交给 {@link EventAnnouncer}（由它等到事务提交）。
     * 谁新建 {@code Written}，谁就已经完成了宣布，因此本类不存在"忘了推"的状态。
     * 用内部类而非 record，是为了私有构造函数能够到外层的 {@code announcer}。
     *
     * <p>{@link #events()} 只是"这次写了哪些"这个事实：调用方拿 seq 当投影坐标
     * （见 {@code TurnExecutor.applyOne}），或从中找本轮新建的文件
     * （见 {@code TurnExecutor.createdIn}）。
     */
    public final class Written {

        private final Session session;
        private final List<StoredEvent> events;

        private Written(Session session, List<StoredEvent> appended) {
            this.session = session;
            this.events = List.copyOf(appended);
            announcer.announce(this.events);
        }

        /** @return 推进后的会话 */
        public Session session() {
            return session;
        }

        /** @return 这次真正落库的事件，按落库顺序 */
        public List<StoredEvent> events() {
            return events;
        }
    }

    /**
     * 一条会话的开头：{@code SessionStarted} 和第 0 个 checkpoint 一次写入。
     *
     * <p>两件事必须同时：它们是"这棵树上来了新会话"和"从哪儿开始数"。
     * 第 0 个 checkpoint 不能省：checkpoint 只在每轮结束才打，没有它就无法撤销第一轮的
     * 全部改动，而那是回滚最常用的场景。它记录这棵树当时的位置：同一人的第二条会话，
     * 第 0 个点落在第一条会话结束处，所以撤销这条会话会退到它开始说话之前，
     * 换会话不会退回代码。
     *
     * <p>这两条事件都不映射状态迁移，因此不改变会话状态。
     *
     * @param workspace 会话所在的工作区
     * @return 写入结果
     */
    @Transactional
    public Written startSession(Session session, Workspace workspace, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(workspace, "workspace");
        List<StoredEvent> appended = new ArrayList<>();
        appended.add(events.append(session.id(), new SessionStarted(
                workspace.branch(), workspace.path().toString(), workspace.headCommit()), token));
        appended.add(events.append(session.id(),
                new CheckpointCreated(workspace.headCommit(), 0), token));
        return new Written(session, appended);
    }

    /**
     * 追加一条事件；如果它引起状态变化，在同一事务里推进会话聚合。
     *
     * <p>先记事实再改状态：{@code SessionStateChanged} 排在触发事件之后，回放时是
     * "发生了什么，于是状态变成什么"。
     *
     * @param event 要落库的事件
     * @return 写入结果
     */
    @Transactional
    public Written append(Session session, PersistentEvent event, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(event, "event");
        List<StoredEvent> appended = new ArrayList<>();
        appended.add(events.append(session.id(), event, token));

        // 并行工具调用会连着来两条 ToolCallRequested，那第二次的状态就是"已经在那儿了"，
        // 而状态机不允许自迁移（IDLE→IDLE 之类一律拒绝）—— 这里提前挡掉，不产生迁移事件
        Optional<SessionState> next = TurnStates.after(event);
        if (next.isEmpty() || next.get() == session.state()) {
            return new Written(session, appended);
        }
        return change(session, next.get(), null, token, appended);
    }

    /**
     * 落下一个不由业务事件触发的状态迁移：一轮开始进 {@code THINKING}，结束进
     * {@code WAITING_USER} 或 {@code FAILED}。
     *
     * @param next   目标状态；与当前相同时不写事件
     * @param reason 人类可读的原因，落进 {@code SessionStateChanged}，让"这轮为什么停"
     *               在审计流里可见
     * @return 写入结果
     */
    @Transactional
    public Written advance(Session session, SessionState next, String reason, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(next, "next");
        if (next == session.state()) {
            return new Written(session, List.of());
        }
        return change(session, next, reason, token, new ArrayList<>());
    }

    /**
     * 一轮收尾：记录产出位置、用量，把状态切到 {@code finalState}，并按规则推进轮次号。
     *
     * <p>这几件事必须在同一事务里：checkpoint 记录的是本轮结束后工作区的位置，也是下一次
     * 回滚的目标；用量和"这一轮结束"是同一件事的两面，分两次写会留下永远补不回来的缺口；
     * 工作区的 {@code head_commit} 必须跟着走，否则界面显示的代码位置是旧的；checkpoint
     * 记录的轮次号必须与这里算出的一致，否则 checkpoint 与对话会悄悄错位而不报异常。
     *
     * @param commit     本轮产出位置及是否新造出提交（见 {@link TurnWorkspace.Commit}）；
     *                   可为 null：提交失败或根本没有工作区时没有可回的点，只收尾、不打 checkpoint
     * @param finalState 停在 {@code WAITING_USER}，或挂着等人批的 {@code AWAITING_APPROVAL}；
     *                   轮次号是否推进由它决定（见 {@link #completes}）
     * @param reason     停止原因，落进 {@code SessionStateChanged}
     * @return 写入结果
     */
    @Transactional
    public Written finishTurn(Session session, TurnWorkspace.Commit commit, String reason,
                              SessionState finalState, TurnTokensUsed tokens, LeaseToken token) {
        Objects.requireNonNull(tokens, "tokens");
        return settle(session, commit, finalState, reason, List.of(tokens), token);
    }

    /**
     * 一轮未能正常收尾时的结算。三种情况：模型调用失败（{@code FAILED}）；用户停止
     * （回 {@code WAITING_USER}，原因写 {@code CANCELLED}）；用户拒绝挂起的调用且没有留下
     * 指示，该轮就此停住（见 {@code ApprovalService}）。
     *
     * <p>与 {@link #finishTurn} 的唯一区别是没有用量：这一轮没有 {@code TurnOutcome}，
     * 记 0 是假账。
     *
     * <p>{@code commit} 不为 null 时仍要打 checkpoint："能不能回退"与这一轮结果无关。
     * 不打的话，打断后 agent 写出的文件是未跟踪的，而 {@code reset --hard} 不碰未跟踪
     * 文件，回滚会以为什么都没发生；给这一轮一个位置，半成品也可回退。{@code commit}
     * 为 null（提交失败或没有工作区）时没有可回的点，只收尾、不打 checkpoint。轮次号
     * 按同一规则处理（见 {@link #completes}）：失败或被用户叫停都意味着这次交互结束，
     * 下一句该是新的一轮。
     *
     * @param commit 本轮提交出来的位置；可为 null，提交失败或没有工作区时确实没有可回的点
     * @return 写入结果
     */
    @Transactional
    public Written abortTurn(Session session, TurnWorkspace.Commit commit, SessionState finalState,
                             String reason, LeaseToken token) {
        return settle(session, commit, finalState, reason, List.of(), token);
    }

    /**
     * {@link #finishTurn} 与 {@link #abortTurn} 共用的结算逻辑。
     *
     * <p>放在一处是因为顺序有讲究：checkpoint 必须在轮次号算出之后写，否则两者记录的不是
     * 同一个数；状态迁移必须与工作区指针在同一事务里，否则中途失败会留下"checkpoint 记了、
     * 指针没动"这种不报错的错位。
     *
     * @param commit  本轮产出位置及是否新造出提交；可为 null
     * @param between 除 checkpoint 与状态外还要落的事件（正常收尾是账单，失败收尾没有），
     *                顺序为 checkpoint、它、状态变化
     */
    private Written settle(Session session, TurnWorkspace.Commit commit, SessionState finalState,
                           String reason, List<PersistentEvent> between, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(finalState, "finalState");

        // 轮次号先算出来：checkpoint 和会话行记录的必须是同一个数，
        // 否则同一次交互的两条 checkpoint（挂起、跑完）会写下不同的号
        Session closed = completes(finalState) ? session.nextTurn() : session;

        List<StoredEvent> appended = new ArrayList<>();
        if (commit != null) {
            appended.add(events.append(session.id(),
                    new CheckpointCreated(commit.sha(), closed.turnIndex()), token));
            // 本轮改了哪些文件在写入时算一次并落进流里，之后读的人照读即可，
            // 不再问 git，也不受它后续变化影响（见 WorkspaceChanges）。
            // 只有真的造出新提交才算：没有改动时 sha 还是上一个 HEAD，
            // 算出来的是上一轮的改动，同一文件会被每轮反复报（见 TurnWorkspace.Commit.created）
            if (commit.created()) {
                // 传 closed 而不是 session：这条事件必须与上面的 checkpoint 记同一个轮次号，
                // 界面上"这一轮改了哪些文件"挂到哪一轮全看这个数
                changesOf(closed, commit.sha()).ifPresent(record ->
                        appended.add(events.append(session.id(), record, token)));
            }
        }
        for (PersistentEvent event : between) {
            appended.add(events.append(session.id(), event, token));
        }

        // 状态可能已经是它：挂起时 ToolApprovalRequested 已把会话推到 AWAITING_APPROVAL，
        // 再推一次就是状态机拒绝的自迁移，withState 内部校验也会抛。
        // 这不丢信息：为什么停，审计里有 ToolApprovalRequested
        if (finalState != session.state()) {
            appended.add(events.append(session.id(),
                    new SessionStateChanged(session.state(), finalState, reason), token));
            closed = closed.withState(finalState);
        }

        // 代码位置记在工作区行上，不是会话行：HEAD 是树的属性，
        // 同一棵树可能被同一个人的几条会话轮着使用
        if (commit != null) {
            workspaces.save(workspaces.require(session.workspaceId()).withHeadCommit(commit.sha()));
        }

        sessions.save(closed);
        return new Written(closed, appended);
    }

    /**
     * 这一轮是否算跑完，判据只有它停在哪儿。
     *
     * <p>一轮 = 用户说一句 + 模型答完，这是给人看的单位，所以只在完整交互结束时推进一格。
     * 停在 {@code AWAITING_APPROVAL} 不算：用户说过话，但模型还在等批准，交互尚未答完，
     * 批准后继续跑完时才推进。失败（{@code FAILED}）和用户停止（{@code CANCELLED}）都算跑完，
     * 因为用户已经等到结果，下一句该是新的一轮。
     *
     * <p>用 {@code finalState} 而不是"有没有追加用户消息"判断：挂起路径确实追加了消息但没跑完，
     * 批准后的续跑没有追加消息却跑完了。
     */
    private static boolean completes(SessionState finalState) {
        return finalState != SessionState.AWAITING_APPROVAL;
    }

    /**
     * 这次提交引入了哪些改动，路径为项目相对；算不出来时返回空，即那一轮不记这条事件。
     *
     * <p>为什么问 git 而不是统计文件工具、为什么宁可没有记录也不记"什么都没改"，
     * 见 {@link WorkspaceChanges}。"这一轮什么都没改"不会走到这里（见 {@link #settle}）。
     *
     * <p>异常全部吞在本层，收尾照常进行：记不下改动不该让整轮失败。
     */
    private Optional<WorkspaceChanges> changesOf(Session session, String commitSha) {
        Optional<Workspace> workspace = trees.find(session.workspaceId());
        if (workspace.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<FileChange> files = new ArrayList<>();
            for (FileChange change : trees.changesIntroducedBy(workspace.get().path(), commitSha)) {
                // git 说的是仓库里的路径（{@code untitled/HelloWorld.java}），
                // 界面说的是项目里的路径（{@code HelloWorld.java}）—— 见 ProjectLayout。
                // 项目外面那些（平台自己的文件）不进这条记录
                String shown = ProjectLayout.toProjectPath(change.path());
                if (shown != null) {
                    files.add(new FileChange(shown, change.added(), change.deleted(),
                            change.binary(), change.created()));
                }
            }
            if (files.isEmpty()) {
                return Optional.empty();
            }
            boolean truncated = files.size() > MAX_RECORDED_FILES;
            return Optional.of(new WorkspaceChanges(commitSha,
                    truncated ? List.copyOf(files.subList(0, MAX_RECORDED_FILES)) : files,
                    truncated, session.turnIndex()));
        } catch (RuntimeException e) {
            log.warn("这一轮（提交 {}）的改动没能记下来，这一轮将没有改动的记录：{}",
                    commitSha, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 把崩溃时悬着的工具调用收成"被打断"，一条调用一个事件。
     *
     * <p>不能用 {@link #append}：状态机把 {@code ToolInterrupted} 映射回 {@code THINKING}，
     * 那是给"这一轮还在跑"用的；崩溃恢复时没有那一轮，照映射写会让会话挂在一个没有任何
     * 执行者的"正在跑"状态上，且不报错。恢复只补事实、不接着跑，所以这里只落事件、不碰状态，
     * 与 {@link #startSession} 同形。
     *
     * @param callIds 执行到一半的调用 id
     * @return 写入结果
     */
    @Transactional
    public Written interruptUnfinished(Session session, List<String> callIds, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        List<StoredEvent> appended = new ArrayList<>();
        for (String callId : callIds) {
            appended.add(events.append(session.id(), new ToolInterrupted(callId), token));
        }
        return new Written(session, appended);
    }

    /**
     * 回滚：记一条 {@code SessionRewound}，同事务把代码位置和对话位置一起拨回。
     *
     * <p>三样必须在同一事务：工作区行的 {@code head_commit}、会话行的 {@code turn_index}，
     * 以及"发生过回滚"这个事实。漏掉轮次号会让 checkpoint 与对话悄悄错位。
     *
     * <p>树属于「人 + 项目」，所以回滚退掉整棵树，包括同一人其他会话之后提交的内容；
     * 那些提交仍可按 sha 找回，只是从当前分支退下。同步带进来的别人的提交也会退掉，
     * 但不伤害任何人，理由见 {@code SessionService.rewind}。
     *
     * @param toCommitSha     回滚后的代码位置
     * @param toTurnIndex     回滚后写在会话行上的轮次；不进事件，也不是定位键
     * @param toCheckpointSeq 目标 checkpoint 的事件序号，是"退到哪儿"的正式记录；
     *                        sha 只回答代码在哪
     * @param byUserId        发起者 id，不是用户名；追责要身份，而用户名会变
     * @return 写入结果
     */
    @Transactional
    public Written rewind(Session session, String toCommitSha, int toTurnIndex, long toCheckpointSeq,
                          UserId byUserId, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        StoredEvent appended = events.append(session.id(),
                new SessionRewound(toCommitSha, toCheckpointSeq, byUserId),
                token);

        workspaces.save(workspaces.require(session.workspaceId()).withHeadCommit(toCommitSha));
        Session rewound = session.withTurnIndex(toTurnIndex);
        sessions.save(rewound);
        // 构造 Written 那一刻就宣布了：回滚是"有人动了我正在看的那棵树"，看的人该立刻看到
        return new Written(rewound, List.of(appended));
    }

    /**
     * 记下一次同步：主干的最新状态被拉进这棵树的工作区。
     *
     * <p>与 {@link #rewind} 同形：事件和 {@code head_commit} 必须在同一事务里。否则回滚、
     * checkpoint 和下一次合并都会按错的位置计算，且不报错。
     *
     * @param fromHead 同步前的 HEAD，由调用方从 git 读取后传入；不要取聚合行里的值，
     *                 那一列可能落后或为空，而 git 的 HEAD 才是此刻的事实
     * @param toHead   同步后的 HEAD
     * @return 写入结果
     */
    @Transactional
    public Written sync(Session session, String fromHead, String toHead, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        StoredEvent appended = events.append(session.id(),
                new SessionSynced(fromHead, toHead), token);

        workspaces.save(workspaces.require(session.workspaceId()).withHeadCommit(toHead));
        // 构造 Written 那一刻就宣布了：对方的代码刚进了这棵树，正在看的人该立刻知道
        return new Written(session, List.of(appended));
    }

    /**
     * 换模型：落一条 {@code ModelChanged}，同事务更新会话行的模型。
     *
     * <p>与状态迁移同一模式：事件保证可审计、可重放，列保证每轮开始读模型不必扫事件流。
     * 两者必须同事务：只落事件不改列，下一轮仍用旧模型；只改列不落事件，重放时没有答案；
     * 这种错不会抛异常，只会让两边悄悄错开。
     *
     * @param model 新配置
     * @return 写入结果
     */
    @Transactional
    public Written changeModel(Session session, ModelConfig model, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(model, "model");
        StoredEvent appended = events.append(session.id(),
                new ModelChanged(session.model().modelId(), model.modelId()), token);
        Session advanced = session.withModel(model);
        sessions.save(advanced);
        return new Written(advanced, List.of(appended));
    }

    /**
     * 把 {@code FAILED} 迁回 {@code IDLE}，供失败重试；调用方随后再迁到 {@code THINKING}。
     *
     * <p>不能一步从 {@code FAILED} 到 {@code THINKING}：状态机只允许 {@code FAILED} 回
     * {@code IDLE}（见 {@link SessionState}）。重试在语义上就是回到空闲、重新开始。
     *
     * @return 写入结果；当前不是 {@code FAILED} 时不写事件
     */
    @Transactional
    public Written retryFromFailed(Session session, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        if (session.state() != SessionState.FAILED) {
            return new Written(session, List.of());
        }
        return change(session, SessionState.IDLE, "用户重试", token, new ArrayList<>());
    }

    private Written change(Session session, SessionState next, String reason, LeaseToken token,
                           List<StoredEvent> appended) {
        appended.add(events.append(session.id(),
                new SessionStateChanged(session.state(), next, reason), token));
        Session advanced = session.withState(next);
        sessions.save(advanced);
        return new Written(advanced, appended);
    }
}
