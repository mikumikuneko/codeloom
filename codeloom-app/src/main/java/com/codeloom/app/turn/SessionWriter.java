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
 * 「追加一条事件」与「推进它背后的那些行」的**唯一实现点**，两件事在同一个事务里。
 *
 * <p><b>落库之后的宣布也从这里走</b>（见 {@link Written}）：全项目只有这一个类往事件流里写，
 * 所以"谁落库、谁负责让它被看到"这条义务有一个落点，而不是散在调用方的自觉里 ——
 * 从前散着的时候，回滚、换模型、两条同步一次都没发过，而且不报错。
 *
 * <p>"背后的那些行"是两类，分得很清楚：
 * <ul>
 *   <li>**会话行** —— {@code state}、{@code turn_index}、模型配置。它们是会话的属性。
 *   <li>**工作区行** —— {@code head_commit}。它是**树的**属性，因为 HEAD 属于那棵树。
 *       同一个人的两条会话会写同一行，这正是"换会话不换代码"的落点。
 * </ul>
 *
 * <h2>为什么它必须是一个独立的 bean</h2>
 * 因为 Spring 的 {@code @Transactional} 只在**跨 bean 调用**时生效 —— 执行器在自己内部
 * 调自己的方法是拿不到代理的，事务不会开。而这两件事必须绑定：
 * {@code Session} 的类注释里写着「每追加一条 {@code SessionStateChanged}，
 * 都要在同一次事务里保存这个聚合，否则两者会对不上」。把事务边界放在一个独立 bean 上，
 * 是让那条约束有一个**能指着看**的落点，而不是散落在调用方的自觉里。
 *
 * <h2>为什么执行器自己不开一整轮的事务</h2>
 * 一整轮放进一个事务，意味着这些行的排它锁被持有到整轮结束。而租约可能在那期间到期、
 * 别的实例正要接管 —— 它会被这把我们自己都不再持有的锁堵住，
 * 最后以锁超时收场，而真实原因（"那条会话的执行者卡住了"）反而看不出来。
 * 所以这里的每次调用都是**一小段**事务，一次一条事件。
 */
@Component
public class SessionWriter {

    private static final Logger log = LoggerFactory.getLogger(SessionWriter.class);

    /**
     * 一条 {@code WorkspaceChanges} 里最多记几个文件。
     *
     * <p>它保的是**这条事件别把事件流撑得过大**：
     * 一次格式化把整个仓库改一遍是完全可能的，而那条事件的正文会跟着流进每一个读它的地方。
     * 超了就只记前一批，并把 {@code truncated} 记成 true —— 界面如实说"没记全"。
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
     * 一次写入的产物：推进后的会话，以及**这次真正落库的事件**。
     *
     * <h2>构造它就是宣布它 —— 所以这个构造函数是私有的</h2>
     * "落库的那几条事件必须送到正在看的人那里"这条义务挂在**构造**上
     * （交给 {@link EventAnnouncer}，由它等到事务提交）：谁能新建一个 {@code Written}，
     * 谁就已经把它交了出去。于是这个类里**没有"忘了推"这个状态** ——
     * 想返回一次写入的结果，就绕不过构造函数。
     *
     * <p>它是个内部类而不是 {@code record}，唯一的原因就是这个：私有构造函数得够得着
     * 外层那个 {@code announcer}。
     *
     * <p>{@link #events()} 于是**不再是一条义务**，只是"这次写了哪些"这个事实本身 ——
     * 有人要拿它的 seq 当投影坐标（见 {@code TurnExecutor.applyOne}），
     * 有人要从里面找这一轮新建的文件（见 {@code TurnExecutor.createdIn}）。
     */
    public final class Written {

        private final Session session;
        private final List<StoredEvent> events;

        private Written(Session session, List<StoredEvent> appended) {
            this.session = session;
            this.events = List.copyOf(appended);
            announcer.announce(this.events);
        }

        public Session session() {
            return session;
        }

        /** 这次真正落库的事件，按落库顺序。 */
        public List<StoredEvent> events() {
            return events;
        }
    }

    /**
     * 一条会话的开头：{@code SessionStarted} 和第 0 个 checkpoint，一次落下来。
     *
     * <p>两件事必须同一次写入 —— 它们是同一句话的"这棵树上来了条新会话"和"从哪儿开始数"。
     *
     * <p><b>第 0 个 checkpoint 为什么不能省</b>：**每轮结束才打下一个 checkpoint**，
     * 没有它的话"撤销第一轮的全部改动"就没有可回的点，而那是回滚最常用的那一次。
     * 它记的是这棵树**当时**的位置：同一个人的第二条会话，第 0 个点落在第一条会话干完之后
     * 的地方，所以"撤销这一条会话的全部改动"退到的正是"它开始说话之前" ——
     * 换会话本来就不该把代码退回去。
     *
     * <p>它不碰会话状态：这两条事件在 {@code TurnStates} 里都不映射迁移。
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
     * 追加一条事件；如果它引起状态变化，同事务把会话聚合一起推进。
     *
     * <p>顺序是「先记事实，再改状态」：{@code SessionStateChanged} 排在触发它的那条事件后面，
     * 回放时读起来就是「发生了什么 → 于是状态变成什么」。
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
     * 把一个不是由某条业务事件触发的状态迁移落下来：一轮开始进 {@code THINKING}、
     * 一轮结束进 {@code WAITING_USER} 或 {@code FAILED}。
     *
     * @param reason 人类可读的原因，用于排障。落进 {@code SessionStateChanged}，
     *               于是「这轮为什么停」在审计流里看得见，而不是只能猜
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
     * 一轮收尾：记下这一轮的产出位置、这一轮花了多少、状态回到 {@code finalState}、轮次号推进一格。
     *
     * <h2>这几件事必须在一个事务里</h2>
     * <ul>
     *   <li><b>checkpoint</b>：{@code commitSha} 是这一轮改完之后工作区的位置，
     *       它同时是"回滚到下一轮开始前"的那个目标
     *   <li><b>用量</b>：这一轮的账单。它和"这一轮结束"是同一件事的两面 ——
     *       分两次写就会出现"跑完了却没记账"，而那种缺口事后补不回来
     *   <li><b>工作区的 head_commit</b>：那棵树上那个指针要跟着走，否则界面上显示的
     *       "当前代码在哪个 commit"是旧的
     *   <li><b>轮次号</b>：它存在的理由就是让 checkpoint 与对话能互相对上，所以
     *       checkpoint 记下的那个数**必须**是这里算出来的那个（见下）
     * </ul>
     * 分开写的话，中间任何一步失败都会留下"checkpoint 记了但轮次号没推"这种状态 ——
     * 而那个错不会报异常，只会让对齐关系悄悄错位。
     *
     * @param commit     这一轮的产出位置，以及它是不是这一轮新造出来的（见
     *                   {@link TurnWorkspace.Commit}）；**可空** —— 提交失败、或者调用方
     *                   手上根本没有工作区时没有可回的点，那两种情况下只收尾、不打 checkpoint
     * @param finalState 这一轮停在哪：正常结束是 {@code WAITING_USER}，
     *                   挂着等人批是 {@code AWAITING_APPROVAL}。**轮次号推不推由它决定**
     *                   ——见 {@link #completes}
     * @param reason     这一轮为什么停下来，落进 {@code SessionStateChanged}，审计时看得见
     */
    @Transactional
    public Written finishTurn(Session session, TurnWorkspace.Commit commit, String reason,
                              SessionState finalState, TurnTokensUsed tokens, LeaseToken token) {
        Objects.requireNonNull(tokens, "tokens");
        return settle(session, commit, finalState, reason, List.of(tokens), token);
    }

    /**
     * 一轮**没能正常收尾**时的结算。三种情况走这里：
     *
     * <ul>
     *   <li>模型调用炸了（{@code FAILED}）
     *   <li>用户按了停止（回 {@code WAITING_USER}，原因写 {@code CANCELLED}）
     *   <li>用户**拒绝**了一次挂起的调用、而且没留下指示 —— 那一轮就此停住
     *       （见 {@code ApprovalService}：Claude Code 在这一支上直接中止整个回合）
     * </ul>
     *
     * <p>和 {@link #finishTurn} 的唯一区别是**没有账单** —— 那一轮没有 {@code TurnOutcome}，
     * 用量无从得知，而记一笔 0 是假账。
     *
     * <h2>为什么它也要打 checkpoint</h2>
     * 因为"能不能回退"跟这一轮最后跑成什么样**无关**：位置是在一轮**开始时**就落下的，
     * 于是那一轮被打断、或者炸了，代码照样退得回去。
     *
     * <p>我们不那样做还有个具体的洞：打断之后 agent 写的那些文件**没有被提交**，
     * 于是对 git 来说是未跟踪的，而 {@code reset --hard} 不碰未跟踪文件 ——
     * 回滚会以为什么都没发生（{@code TurnWorkspace.commit} 的注释里，"未跟踪"这个根因
     * 已经害过两次）。给这一轮一个位置，就把那个洞堵上了：半成品照样可退。
     *
     * <p>轮次号按同一条规则处理（见 {@link #completes}）：用户等到的是一次失败、
     * 或者是他自己叫停的，**这次交互对他就已经结束了**，下一句话该是新的一轮。
     *
     * @param commit 这一轮改动提交出来的位置；**可空** —— 提交失败时给 null（见
     *               {@code TurnExecutor.commitQuietly}），或者调用方手上根本没有工作区
     *               （审批那条路就是），那种情况下这个位置确实没有可回的点
     */
    @Transactional
    public Written abortTurn(Session session, TurnWorkspace.Commit commit, SessionState finalState,
                             String reason, LeaseToken token) {
        return settle(session, commit, finalState, reason, List.of(), token);
    }

    /**
     * 一轮的结算 —— {@link #finishTurn} 和 {@link #abortTurn} 共用的那一段。
     *
     * <p>放在一处是因为**顺序是有讲究的**：checkpoint 必须在轮次号算出来之后写
     * （否则两者记的不是同一个数），状态迁移必须和工作区指针在同一个事务里
     * （否则中间失败会留下"checkpoint 记了但指针没动"这种不报错的错位）。
     * 两份各写一遍的话，这些讲究迟早会有一份走样。
     *
     * @param commit  这一轮的产出位置，以及它是不是这一轮新造出来的；**可空**
     * @param between 除了 checkpoint 和状态之外还要落的事件（正常收尾是那一轮的账单，
     *                失败收尾没有）—— 顺序是「checkpoint → 它 → 状态变化」
     */
    private Written settle(Session session, TurnWorkspace.Commit commit, SessionState finalState,
                           String reason, List<PersistentEvent> between, LeaseToken token) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(finalState, "finalState");

        // **轮次号先算出来**，因为 checkpoint 和会话行记的必须是同一个数 ——
        // 否则同一次交互的两条 checkpoint（挂起、跑完）会记下两个不同的数
        Session closed = completes(finalState) ? session.nextTurn() : session;

        List<StoredEvent> appended = new ArrayList<>();
        if (commit != null) {
            appended.add(events.append(session.id(),
                    new CheckpointCreated(commit.sha(), closed.turnIndex()), token));
            // **这一轮改了哪些文件：在写的时候算一次**，算完落进流里。
            // 之后读的人（回滚界面、会话流）照着读就行 —— 不再问 git、也不受它变没变的影响。
            // 见 {@code WorkspaceChanges} 的类注释
            //
            // **只有这一轮真的造出了提交才算**：没有改动时 {@code commit.sha()} 还是上一个
            // HEAD，拿它算出来的是**上一轮**的文件，于是同一个文件被每一轮反复报一遍
            //（见 TurnWorkspace.Commit.created）
            if (commit.created()) {
                // ★ 传的是 `closed`，不是 `session` —— 这条事件和上面那条 checkpoint
                //   必须记**同一个号**（那个号在 223 行算出来，见那段注释）。
                //   从前传 `session` 看不出问题，只是因为这条事件当时压根没记号；
                //   界面上"这一轮改了哪些文件"要挂到哪一轮，全看这个数
                changesOf(closed, commit.sha()).ifPresent(record ->
                        appended.add(events.append(session.id(), record, token)));
            }
        }
        for (PersistentEvent event : between) {
            appended.add(events.append(session.id(), event, token));
        }

        // 状态可能**已经是**它了：挂起时，ToolApprovalRequested 那条事件已经把会话推到了
        // AWAITING_APPROVAL，这里再推一次就是自迁移，而状态机明确拒绝自迁移 ——
        // 不只是事件不该落，`withState` 内部那一层校验也会直接抛。
        // 这不算丢信息：那一轮为什么停，审计里有 ToolApprovalRequested 说得很清楚
        if (finalState != session.state()) {
            appended.add(events.append(session.id(),
                    new SessionStateChanged(session.state(), finalState, reason), token));
            closed = closed.withState(finalState);
        }

        // 代码位置记在**工作区**那一行上，不是会话行上：HEAD 是树的属性，
        // 而同一棵树可能正被同一个人的几条会话轮着用
        if (commit != null) {
            workspaces.save(workspaces.require(session.workspaceId()).withHeadCommit(commit.sha()));
        }

        sessions.save(closed);
        return new Written(closed, appended);
    }

    /**
     * 这一轮**跑完了**吗 —— 判据只有一条：它停在哪儿。
     *
     * <h2>一轮 = 用户说一句 + 模型答完</h2>
     * 这是给人看的单位（见 {@code CheckpointCreated} 的注释），所以"轮次"只在**一次完整交互
     * 结束**时推进一格。挂在 {@code AWAITING_APPROVAL} 上的那一轮不算：用户确实说过一句话，
     * 但模型还在等他点那个批准，这次交互还没答完 —— 批准之后接着跑完的那一刻才推。
     *
     * <p>另外两种收场**都算跑完**，只是结束得不体面：模型调用失败（{@code FAILED}）、
     * 用户按了停止（{@code WAITING_USER} + 原因 {@code CANCELLED}）。用户都已经等到结果了，
     * 而下一句话本来就该是新的一轮。
     *
     * <p>判据用 {@code finalState} 而不是"这一轮有没有追加用户消息"：
     * 挂起那条路上确实追加了消息，可它没跑完；而批准之后的续跑没有追加消息，可它跑完了。
     */
    private static boolean completes(SessionState finalState) {
        return finalState != SessionState.AWAITING_APPROVAL;
    }

    /**
     * 这一次提交引入了哪些改动，**项目相对路径**。
     *
     * <h2>为什么问 git，而不是数模型调了几次写文件</h2>
     * 因为命令也会改文件（格式化器、代码生成、{@code sed -i}、编译产物）——
     * 只数文件工具的话，一轮里跑过 {@code mvn spotless:apply} 改了十二个文件，
     * 界面上会说"这一轮没改什么"。
     *
     * <h2>算不出来时返回空 —— 也就是**不记这条事件**</h2>
     * 算不出来（git 挂了、工作区不在了）就放弃这一轮的记录：**如实"没有记录"**，
     * 而不是记一条"什么都没改"—— 那是在编。代价是两者在界面上长得一样，
     * 这一条写在 {@link WorkspaceChanges} 上。
     *
     * <p>**"这一轮什么都没改"不归这里管**：那种轮次连问都不会问一次，见 {@link #settle}。
     *
     * <p><b>它有界</b>：任何异常都吞在这一层，收尾照常进行 —— 记不下这一轮的改动，
     * 不该让整轮失败 —— 算不出来就放弃这一轮的记录。
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
     * 把崩溃时悬着的工具调用收成"被打断"，一条一个事件。
     *
     * <h2>为什么这里**不**用 {@link #append}</h2>
     * 状态机把 {@code ToolInterrupted} 映射成回到 {@code THINKING}，那条映射服务的是
     * "这一轮还在跑，回去接着想"。而崩溃恢复时**没有那一轮** —— 照那条映射写下去，
     * 会话会挂在一个**没有任何执行者**的"正在跑"上面，而且不报错。
     *
     * <p>恢复的立场是"只补事实、不接着跑"（见那篇决策），所以这里只落事件，不碰状态。
     *
     * <p>这条和 {@link #startSession} 是同一个形状：**落一条事实，但状态机不该因此动**。
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
     * 回滚：记一条 {@code SessionRewound}，同事务把**代码位置**和**对话位置**一起拨回去。
     *
     * <p>三样东西必须在同一个事务里：代码位置（工作区行的 {@code head_commit}）、
     * 对话位置（会话行的 {@code turn_index}）、以及"发生过回滚"这个事实。
     * 漏掉轮次号，checkpoint 与对话的对应就悄悄错位了 —— 而那是按 turn 找代码时才会发现的
     * 那种错。
     *
     * <h2>共享的树让这一步的含义变了，这件事得说清楚</h2>
     * 树属于**人 + 项目**，所以**回滚退掉的是整棵树** —— 包括同一个人的**其他会话**
     * 在那之后提交的东西。那些提交不是消失了（sha 还在，按 sha 还能找回来），
     * 而是从当前分支上退了下来。
     *
     * <p>这是"同一棵树上不区分是哪条会话改的"的直接后果，也是刻意的：
     * 对同一个人来说"撤销"本来就该整块撤销。
     *
     * <p>**别人的**提交也会被退掉（同步把它们带进来过），但那不伤害任何人 ——
     * 理由写在 {@code SessionService.rewind} 的注释里，那里也是那道旧检查被删掉的地方。
     *
     * @param toTurnIndex     回滚之后写在会话行上的轮次（从哪儿继续数就靠它）。
     *                        **它不进事件，也不是定位键** —— 理由见 {@link SessionRewound}
     * @param toCheckpointSeq 退回到的那条 checkpoint 在事件流里的序号。**它是"退到哪儿"
     *                        的正式记录**（投影靠它找回对话边界），sha 只回答"代码在哪"
     * @param byUserId         谁下的手。**记 id 而不是用户名** —— 追责要的是身份，
     *                         而用户名会变（见 {@link SessionRewound}）
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
     * 记下一次同步：主干的最新状态被拉进了这棵树的工作区。
     *
     * <p>形状和 {@link #rewind} 一样 —— <strong>事件和 {@code head_commit} 那一列在同一个事务里</strong>。
     * 不同步推进的话，回滚、checkpoint、下一次合并会全都按一个错的位置去算，而那种错
     * **不会报错**，只会让结果莫名其妙。
     *
     * @param fromHead 同步前的 HEAD。**由调用方从 git 读出来传进来**，不从这个聚合的行里取 ——
     *                 行在空项目起步时是 null，而 git 那边这时候可能已经有个真实位置了
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
     * 换模型：落一条 {@code ModelChanged}，同事务把会话行上的模型改掉。
     *
     * <p>和状态迁移同一个模式（{@link #advance} 也是"事件 + 列"）：**事件保证可审计、
     * 可重放，列保证读取快**——每一轮开始都要读模型，不能让每次跑一轮都去扫事件流。
     *
     * <p>两者必须在同一个事务里：只落事件不改列，下一轮还用旧模型；只改列不落事件，
     * 重放时"这一轮为什么换了个模型"就没了答案。而那个错不会报异常，只会让两边悄悄错开。
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
     * 迁到 {@code IDLE} 再进 {@code THINKING}，供失败重试用。
     *
     * <p>为什么不能一步从 {@code FAILED} 到 {@code THINKING}：状态机里 {@code FAILED}
     * 只能回 {@code IDLE}（见 {@link SessionState}）。那条约束是刻意的 ——
     * 「重试」在语义上就是"回到空闲、重新开始"，而不是"从错误状态直接继续跑"。
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
