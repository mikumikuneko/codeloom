package com.codeloom.app.session;

import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.app.note.AgentNotes;
import com.codeloom.app.turn.PendingMessages;
import com.codeloom.app.turn.SessionProjections;
import com.codeloom.app.turn.SessionWriter;
import com.codeloom.app.workspace.WorkspaceProvisioner;
import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.llm.Providers;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.User;
import com.codeloom.domain.workspace.WorkspaceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话的用例：建、查、列、丢、回滚、换模型。
 *
 * <p>建会话也要抢租约：写第一条 {@code SessionStarted} 时 {@code EventStore.append}
 * 要求 {@code LeaseToken}。事实来源上不存在"这条不用记账"的写入，否则 fencing 就不再覆盖
 * 所有写入。代价是建会话多两次 Redis 往返。
 *
 * <p>建会话不等于建树：树挂在「用户 × 项目」上（见 {@link WorkspaceId}），同一人在同一项目
 * 的第二条会话复用第一条的树，换对话不会让上一轮的改动消失（见
 * {@link com.codeloom.app.workspace.WorkspaceProvisioner#ensure}）。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionService {

    private final SessionRepository sessions;
    private final WorkspaceRepository stored;
    private final WorkspaceProvisioner provisioner;
    private final WorkspaceManager workspaces;
    private final EventStore events;
    private final ExecutionLease leases;
    private final SessionWriter writer;
    private final LlmClientProvider clients;
    /** 名字不叫 {@code checkpoints}：这个类上已经有个叫 {@code checkpoints(Session)} 的方法了。 */
    private final SessionCheckpoints sessionCheckpoints;
    private final SessionErasure erasure;
    private final AgentNotes notes;
    private final SessionProjections projections;
    private final PendingMessages pending;

    public SessionService(SessionRepository sessions,
                          WorkspaceRepository stored,
                          WorkspaceProvisioner provisioner,
                          WorkspaceManager workspaces,
                          EventStore events,
                          ExecutionLease leases,
                          SessionWriter writer,
                          LlmClientProvider clients,
                          SessionCheckpoints sessionCheckpoints,
                          SessionErasure erasure,
                          AgentNotes notes,
                          SessionProjections projections,
                          PendingMessages pending) {
        this.sessions = sessions;
        this.stored = stored;
        this.provisioner = provisioner;
        this.workspaces = workspaces;
        this.events = events;
        this.leases = leases;
        this.writer = writer;
        this.clients = clients;
        this.sessionCheckpoints = sessionCheckpoints;
        this.erasure = erasure;
        this.notes = notes;
        this.projections = projections;
        this.pending = pending;
    }

    /**
     * 建一条会话；创建者的树必须已经存在，这里只取用。
     *
     * <p>先确认该供应商配过密钥再建：密钥按供应商独立，选中没配密钥的模型是最常见的误操作；
     * 不在这里拦住，用户会在模型调用时才收到 401，而事件流和租约已经动过。
     * 系统提示词由平台决定，一律覆盖为 {@link SystemPrompt#TEXT}。
     *
     * @param owner   会话所有者
     * @param project 项目
     * @param model   用户选择的供应商与模型
     * @return 新会话；{@code SessionStarted} 已写入事件流
     * @throws ResponseStatusException 400，该供应商没有配置密钥
     */
    public Session create(User owner, Project project, ModelConfig model) {
        requireConfiguredKey(owner.id(), model, "所以选不了它上面的模型。先去「供应商」页把它配上");

        // 系统提示词由平台定：用户选的只有端点和模型，请求里带的提示词一律覆盖
        ModelConfig effective = model.withSystemPrompt(SystemPrompt.TEXT);

        Session session = Session.create(SessionId.generate(), project.id(), owner.id(), effective);
        // 树在拿到项目访问权时已建好（建项目 / 接受邀请）；ensure 幂等，缺失时补建
        Workspace workspace = provisioner.ensure(owner.id(), project);

        sessions.save(session);
        appendStarted(session, workspace);
        return session;
    }

    /** 找第一句话时最多往前看多少条事件；它在建会话那几条加用户开口那句之内。 */
    private static final int FIRST_MESSAGE_SCAN = 50;

    /** 列表一行装不下更长的正文，更长的部分进了列表也会被截掉。 */
    private static final int FIRST_MESSAGE_CHARS = 80;

    /**
     * @param project 项目
     * @param limit   这一页最多多少条
     * @param offset  跳过多少条
     * @return 项目下的会话；分页由仓储层完成，见 {@code SessionRepository.findByProject}
     */
    public List<SessionView> listFor(Project project, int limit, int offset) {
        // 一次取回本项目所有工作区再按 id 索引，避免逐个查；N 的上限是成员数（≤2）
        Map<WorkspaceId, Workspace> trees = new HashMap<>();
        for (Workspace tree : stored.findByProject(project.id())) {
            trees.put(tree.workspaceId(), tree);
        }
        return sessions.findByProject(project.id(), limit, offset).stream()
                .map(session -> SessionView.of(session, treeOf(trees, session), firstMessage(session)))
                .toList();
    }

    /**
     * @param session 会话
     * @return 会话视图
     */
    public SessionView view(Session session) {
        return SessionView.of(session, workspaceOf(session), firstMessage(session));
    }

    /**
     * 这条会话里用户说的第一句话，历史列表用它区分"这是哪一条"。
     *
     * <p>同一栏列的是同一个人的会话，"谁在说"区分不了它们，而开头那句正是人记得住的。
     * 还没人说过话，或那些事件已经读不到时返回 null。
     */
    private String firstMessage(Session session) {
        for (StoredEvent stored : events.readAfter(session.id(), 0, FIRST_MESSAGE_SCAN)) {
            if (stored.event() instanceof UserMessage(String text1)) {
                String text = text1.strip();
                return text.length() <= FIRST_MESSAGE_CHARS
                        ? text
                        : text.substring(0, FIRST_MESSAGE_CHARS) + "…";
            }
        }
        return null;
    }

    /**
     * 回滚到某个 checkpoint：整棵树 reset、对话截断、留痕，三件事一起。
     *
     * <p>定位用 checkpoint 的事件序号而不是 commit：一轮什么都没改时两条 checkpoint 同 sha，
     * 拿 sha 反推会退过头，甚至把整段对话清空。回滚的是整棵树，包括本人另一条会话之后
     * 提交的内容和同步带进来的内容；它们仍留在主干和对方那棵树里，下次同步会拿回来。
     *
     * <p>必须先拿到租约：既挡住正在跑的轮次同时改工作区，也会推进 fencing token，
     * 使僵尸写入者的后续写入被拒。因此除"会话正在执行中"外没有其他拒绝理由。
     *
     * <p>理由见 {@code docs/decisions/architecture/2026-09-29-rewind-the-whole-tree.md}。
     *
     * @param targetCheckpointSeq 目标 checkpoint 在事件流里的 seq
     * @param byUserId            发起者的 id，不是用户名；事后要查得出是谁，而用户名会变
     * @return 回滚后的会话
     * @throws ResponseStatusException 409，会话正在执行中
     * @throws IllegalArgumentException 目标序号不是这条会话的 checkpoint；调用方映射为 400
     */
    public Session rewind(Session session, long targetCheckpointSeq, UserId byUserId) {
        // 只允许回到这条会话真的打过的点，规则与"合到某个点"共用
        SessionCheckpoints.Pinned target = sessionCheckpoints.requireAt(session, targetCheckpointSeq);
        CheckpointCreated checkpoint = target.checkpoint();

        Workspace workspace = workspaceOf(session);

        LeaseToken token = leases.tryAcquire(session).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完再回滚"));
        try {
            workspaces.resetTo(workspace, checkpoint.commitSha());
            // 请求开头那份会话可能已过期（等锁期间可能跑完了一轮）；回滚只改 turn_index 和位置，
            // 不能把旧的 state/model 写回去
            return writer.rewind(requireSession(session.id()), checkpoint.commitSha(),
                    checkpoint.turnIndex(), target.seq(), byUserId, token).session();
        } finally {
            leases.release(token);
        }
    }

    /**
     * 换这条会话用的模型，下一轮生效。
     *
     * <p>要抢租约：写事件需要它，而且正在跑的那一轮已经拿到配置快照，中途换会让
     * "这一轮用的哪个模型"说不清。供应商与模型一起换，供应商决定密钥和请求地址；
     * 系统提示词原样保留，它是会话创建时平台写入的那一段。
     *
     * <p>与 {@link #create} 一样，换成没有配置密钥的供应商必须当场拦住，否则报错会等到
     * 下一轮模型调用才出现。
     *
     * <p>理由见 {@code docs/decisions/architecture/2026-09-28-switching-model-is-an-event.md}。
     *
     * @param provider 换成哪家供应商
     * @param modelId  模型 id
     * @return 换模型后的会话
     * @throws ResponseStatusException 400 该供应商没有配置密钥；409 会话正在执行中
     */
    public Session switchModel(Session session, ProviderId provider, String modelId) {
        ModelConfig current = session.model();
        ModelConfig next = new ModelConfig(provider, modelId, current.systemPrompt());

        requireConfiguredKey(session.ownerId(), next, "所以换不过去。先去「供应商」页把它配上");

        LeaseToken token = leases.tryAcquire(session).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完再换模型"));
        try {
            // 请求开头的会话快照可能已过期：等锁期间另一轮可能推进了 state/turn_index。
            // changeModel 会把这些字段一起写回，所以必须从锁后的新状态出发
            return writer.changeModel(requireSession(session.id()), next, token).session();
        } finally {
            leases.release(token);
        }
    }

    /**
     * 这个供应商配过密钥没有；建会话和换模型问的是同一个事实。
     *
     * <p>不在这里拦，用户会到模型调用时才收到 401，而那时事件流和租约已经动过。
     * 两个入口都回 400：用户要做的是同一件事。
     *
     * @param action 当前动作自己的那半句话
     */
    private void requireConfiguredKey(UserId owner, ModelConfig model, String action) {
        if (clients.findClient(owner, model).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "你还没有配置 " + Providers.displayNameOf(model.provider()) + " 的 API Key，"
                            + action + " —— 每家模型服务的密钥是各自独立的，"
                            + "配了一家不等于配了另一家。");
        }
    }

    /**
     * 丢弃这条会话：对话没了，代码留着。
     *
     * <p>树属于「人 + 项目」而不是会话（见 {@link WorkspaceId}），所以丢弃不会让上一轮的改动
     * 消失，新会话从原地继续，也不动 git。事件流是真删（见
     * {@link com.codeloom.domain.port.EventDiscard}），因此它打过的 checkpoint 一起消失，
     * 队友引用过的 {@code anchorEventSeq} 会指向不存在的 seq；服务端不解析锚点，前端会显示
     * "引用的内容已经不存在了"。
     *
     * <p>必须抢租约：正在跑的一轮结束时 {@code SessionWriter} 会 upsert 会话行，行没了会被
     * 重新插回来，得到没有历史的空壳；持有租约则跑着的时候删不了，并推进 fencing token。
     *
     * <p>理由见 {@code docs/decisions/architecture/2026-09-30-discard-really-deletes.md}。
     *
     * @param session 要丢弃的会话
     * @throws ResponseStatusException 409，会话正在执行中
     */
    public void discard(Session session) {
        LeaseToken token = leases.tryAcquire(session).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完再丢弃"));
        try {
            erasure.erase(session.id());
        } finally {
            leases.release(token);
        }
        // 内存队列在事务之后清：事务失败时它们还在，用户再点一次即可；先清则会连未投递的
        // 留言一起丢掉，而那次失败从外面看不出来
        notes.discard(session.id());
        // 投影放下即可；不清理也不会错（有上限，会被挤掉），但没必要留着已删除对话
        projections.forget(session.id());
        // 排队留言同理，而且会话没了，它们等不到任何一轮
        pending.forget(session.id());
    }

    private void appendStarted(Session session, Workspace workspace) {
        // 新建的会话所在这棵树必然空着。拿不到租约直接失败：说明树已被占用，
        // 继续写入会在一条不属于我们的会话上留痕
        LeaseToken token = leases.tryAcquire(session).orElseThrow(() -> new IllegalStateException(
                "刚创建的会话所在这棵树不该已经有人持有租约：" + session.workspaceId()));
        try {
            writer.startSession(session, workspace, token);
        } finally {
            leases.release(token);
        }
    }

    /**
     * 这条会话所在的那棵树。
     *
     * <p>建会话时已经建好（见 {@link com.codeloom.app.workspace.WorkspaceProvisioner#ensure}），
     * 找不到意味着服务端状态错误，是 500 而不是 400。
     */
    private Workspace workspaceOf(Session session) {
        // 与别处查库共用 WorkspaceRepository.require 的判据与文案：
        // "找不到"永远是 500，原因是建会话时写好的那一行被删过
        return stored.require(session.workspaceId());
    }

    /**
     * 拿锁之后重新读会话 —— 租约只保证"接下来没有第二个写者"，
     * 不保证调用方手里那份请求开始时的快照还是新的。
     */
    private Session requireSession(SessionId id) {
        return sessions.findById(id).orElseThrow(() ->
                new IllegalStateException("会话不存在：" + id));
    }

    private static Workspace treeOf(Map<WorkspaceId, Workspace> trees, Session session) {
        Workspace tree = trees.get(session.workspaceId());
        if (tree == null) {
            throw new IllegalStateException(
                    "会话 " + session.id() + " 所在的工作区不在库里：" + session.workspaceId());
        }
        return tree;
    }
}
