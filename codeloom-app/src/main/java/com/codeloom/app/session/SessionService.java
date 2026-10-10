package com.codeloom.app.session;

import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.app.note.AgentNotes;
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
 * 会话的用例：建、查、列、丢。
 *
 * <h2>「建会话」也要抢一次租约</h2>
 * 建会话要往事件流写第 0 条（{@code SessionStarted}），而 {@code EventStore.append}
 * 要求一个 {@code LeaseToken} —— fencing 的落点。这里没有例外：**事实来源上不存在
 * "这条不用记账"** —— 只要有一条路径能不带 token 写事件，fencing 就不再是
 * "所有写入都受租约保护"，而那正是它有效的全部理由。代价是建会话多两次 Redis 往返。
 *
 * <h2>「建会话」不等于「建一棵树」</h2>
 * 树挂在「用户 × 项目」上（见 {@link WorkspaceId}），所以同一个人在同一项目里的第二条
 * 会话**复用**第一条建出来的树 —— 换一段对话不该让上一轮的改动消失。
 * 见 {@link com.codeloom.app.workspace.WorkspaceProvisioner#ensure}。
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
                          SessionProjections projections) {
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
    }

    public Session create(User owner, Project project, ModelConfig model) {
        // **先确认这个端点上配过密钥，再动手建。**
        // 每家密钥独立，选中一个没配过密钥的模型是最常见的误操作。不在这里拦住，
        // 用户会先建出会话、发出第一句话，直到模型调用才收到 401 ——
        // 那时事件流和租约都已经动过了
        if (clients.findClient(owner.id(), model).isEmpty()) {
            throw new IllegalArgumentException(
                    "你还没有配置 " + Providers.displayNameOf(model.provider()) + " 的 API Key，"
                            + "所以选不了它上面的模型。先去「供应商」页把它配上 —— "
                            + "每家模型服务的密钥是各自独立的，配了 DeepSeek 不等于配了 Kimi。");
        }

        // **系统提示词由平台定，用户不参与**：用户选的是端点和模型，这里一律用平台上那一段，
        // 不看请求带了什么。见 SystemPrompt 的类注释。
        ModelConfig effective = model.withSystemPrompt(SystemPrompt.TEXT);

        Session session = Session.create(SessionId.generate(), project.id(), owner.id(), effective);
        // 树在"这个人拿到项目访问权"时就已建好（建项目 / 接受邀请，见 WorkspaceProvisioner），
        // 这里只取用，不再新建
        Workspace workspace = provisioner.ensure(owner.id(), project);

        sessions.save(session);
        appendStarted(session, workspace);
        return session;
    }

    /** 找第一句话时最多往前看多少条事件 —— 它在头几条里（建会话那几条 + 用户开口那句）。 */
    private static final int FIRST_MESSAGE_SCAN = 50;

    /** 列表那一行装不下更长的：更长的正文进了列表也是被 CSS 截掉，白传几 KB。 */
    private static final int FIRST_MESSAGE_CHARS = 80;

    /** 项目下的会话。**分页是仓储层的事** —— 见 {@code SessionRepository.findByProject}。 */
    public List<SessionView> listFor(Project project, int limit, int offset) {
        // 一次取回本项目下所有工作区再按 id 索引。逐个查是 N+1，而 N 的上限是成员数（≤2）
        Map<WorkspaceId, Workspace> trees = new HashMap<>();
        for (Workspace tree : stored.findByProject(project.id())) {
            trees.put(tree.workspaceId(), tree);
        }
        return sessions.findByProject(project.id(), limit, offset).stream()
                .map(session -> SessionView.of(session, treeOf(trees, session), firstMessage(session)))
                .toList();
    }

    public SessionView view(Session session) {
        return SessionView.of(session, workspaceOf(session), firstMessage(session));
    }

    /**
     * 这条会话里**用户说的第一句话** —— 历史列表拿它区分"这是哪一条"。
     *
     * <p>用第一句话而不是"谁在说"：同一栏里列的是同一个人的会话，说话人区分不了它们，
     * 而**开头那句**正是人自己记得住的。还没人说过话（或者那些事件已经读不到）就返回 null。
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

    public List<CheckpointView> checkpoints(Session session) {
        return sessionCheckpoints.of(session).stream().map(CheckpointView::of).toList();
    }

    /**
     * 回滚到某个 checkpoint：整棵树 reset + 对话截断 + 留痕，三件事一起。
     *
     * <h2>目标是「哪一条 checkpoint」，不是「哪个 commit」</h2>
     * 调用方送的是那条 checkpoint 在事件流里的**序号**。同一轮什么都没改时，
     * 两条 checkpoint 会同 sha —— 拿 sha 去反推只能猜，而猜错的方向恰好是退过头
     * （见 {@code SessionCheckpoints} 的类注释）。
     *
     * <h2>为什么退的是整棵树</h2>
     * 一棵树属于**一个人**（见 {@code WorkspaceId}），回滚在他自己那条分支上做 ——
     * 主干、对方的工作区都不在这条路径上。所以对他自己来说，**"撤销"就该整块撤销**：
     * 包括他另一条会话在那之后提交的东西，也包括同步把主干合进来的那一段。
     *
     * <p>（参考实现 Claude Code 按会话只回滚它动过的文件，不是更对，是它**没有分支可用**：
     * 一个目录上所有会话共用一份文件，只能按文件圈范围。我们有每个人的分支，
     * 所以"我这棵树回到那一刻"表达得出来，也更好懂。）
     *
     * <h2>为什么必须先拿到租约</h2>
     * 两重作用。一是**挡住"正在跑的时候回滚"** —— 那会让 agent 和回滚同时改同一份工作区，
     * 结果是一堆谁也说不清的半成品文件。二是拿到租约会把 fencing token 往前推，
     * 于是**即便真有一个正在跑的轮次，它接下来的写入也会被拒** ——
     * 那是比"别让它跑"更硬的保证，因为它不依赖对方配合。
     *
     * <h2>它不碰任何人，所以无需检查"会不会吃掉别人的代码"</h2>
     * 把主干同步进来确实会在这棵树上带一批别人的提交，但把它们从我的分支上退掉，
     * 它们在主干里、在自己那棵树里都一点没少，我下次同步就拿回来了。
     * 曾经的 409 检查（目标之后有别人的提交就拒绝）守的是这个不存在的伤害，
     * 代价却是**同步过一次之后再也回不到同步前** —— 已删除。
     *
     * @param byUserId 谁下的手。**是 id 不是用户名** —— 事后要查得出是谁，而用户名会变
     *                 （唯一不等于它是身份，见 {@link com.codeloom.domain.event.SessionRewound SessionRewound}）；名字由读的那一侧现查
     */
    public Session rewind(Session session, long targetCheckpointSeq, UserId byUserId) {
        // 只允许回到**这条会话真的打过**的点，规则与"合到某个点"共用一份
        //（见 SessionCheckpoints 的类注释）。
        //
        // 键是**序号**而不是 sha：回滚要的是一个对话位置，而"这一轮什么都没改"
        // 会让它和上一条 checkpoint 同 sha（见 CheckpointCreated 的类注释）。
        // 用 sha 反推"第一条"会把回滚退到会话刚建好的一刻 —— 用户选的是
        // "退到第三句之前"，看到的却是整个对话被清空
        SessionCheckpoints.Pinned target = sessionCheckpoints.requireAt(session, targetCheckpointSeq);
        CheckpointCreated checkpoint = target.checkpoint();

        Workspace workspace = workspaceOf(session);

        LeaseToken token = leases.tryAcquire(session).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完再回滚"));
        try {
            workspaces.resetTo(workspace, checkpoint.commitSha());
            return writer.rewind(session, checkpoint.commitSha(), checkpoint.turnIndex(),
                    target.seq(), byUserId, token).session();
        } finally {
            leases.release(token);
        }
    }

    /**
     * 换这条会话用的模型。
     *
     * <p>要**抢一次租约**，两个理由：写事件需要它（fencing 保护，见 {@code EventStore}），
     * 而且**正在跑的时候不该换** —— 那一轮已经拿到配置快照了，中途改靶会让
     * "这一轮到底用的哪个模型"说不清。
     *
     * <h2>换供应商也走这里</h2>
     * 界面上"这一轮交给谁"是一个下拉，换家必然连地址一起换，所以地址也收。
     * 系统提示词原样保留 —— 它是这条会话出生时平台给的那一段，换模型不该换掉它。
     *
     * <p>和 {@link #create} 走同一条路径：换成一家没配过密钥的必须当场拦住，
     * 否则这条会话会被改成下一轮根本跑不动的配置，而报错离用户点的那一下已经很远。
     */
    public Session switchModel(Session session, ProviderId provider, String modelId) {
        ModelConfig current = session.model();
        ModelConfig next = new ModelConfig(provider, modelId, current.systemPrompt());

        if (clients.findClient(session.ownerId(), next).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "你还没有配置 " + Providers.displayNameOf(provider) + " 的 API Key，所以换不过去。"
                            + "先去「供应商」页把它配上 —— "
                            + "每家模型服务的密钥是各自独立的，配了一家不等于配了另一家。");
        }

        LeaseToken token = leases.tryAcquire(session).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完再换模型"));
        try {
            return writer.changeModel(session, next, token).session();
        } finally {
            leases.release(token);
        }
    }

    /**
     * 丢弃这条会话：**对话没了，代码留着**。
     *
     * <h2>为什么"删对话"和"删树"是两件事</h2>
     * 树属于「人 + 项目」而不是某条会话（见 {@link WorkspaceId}）—— 所以丢掉一段对话
     * 不会让上一轮的改动消失，新开一条会话就从**原地**继续。反过来说，代码在树的分支上，
     * 所以**这次丢弃不动 git 一根毫毛**。
     *
     * <h2>真删，而且代价要说清楚</h2>
     * 事件流是**真删**（见 {@link com.codeloom.domain.port.EventDiscard} 里那份理由），
     * 于是两件事跟着没了：
     * <ul>
     *   <li>它打过的 checkpoint —— 事后没法再按这条会话回滚了。代码不会丢，
     *       丢的是"退回到某一天的状态"这个能力；
     *   <li>队友如果引用过它的某条事件（聊天室的 {@code anchorEventSeq}），
     *       那个引用会指向一个不存在的 seq。服务端不解析锚点，所以不会报错 ——
     *       前端会显示成"引用的内容已经不存在了"，而那是诚实的。
     * </ul>
     *
     * <h2>为什么必须抢一次租约</h2>
     * 两个理由，第二个是硬的：别让**正在跑的一轮**被删 —— 一边删、一边在写，谁也说不清结果；
     * 更具体地说，那一轮结束时 {@code SessionWriter} 会 {@code sessions.save(...)}，
     * 而那是 upsert —— 行没了它就把这条会话**重新插回来**，于是一次"丢弃"得到一个
     * 没有历史的空壳会话。拿着租约就堵住了这条路：跑着的时候根本删不了。
     * 抢租约还会把 fencing token 往前推，所以即便真有个僵尸写入者，它接下来的写入也会被拒。
     */
    public void discard(Session session) {
        LeaseToken token = leases.tryAcquire(session).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完再丢弃"));
        try {
            erasure.erase(session.id());
        } finally {
            leases.release(token);
        }
        // 留言队列放在事务**之后**清：事务失败时它们还在，用户再点一次就行；
        // 反过来先清队列的话，一次失败会连那些还没投递的留言一起丢掉，
        // 而那次失败从外面完全看不出来
        notes.discard(session.id());
        // 内存里那条投影也一起放下：会话都没了，留着它就是把一段已经删掉的对话
        // 继续搁在内存里。**不清理也不会错**（有上限、迟早被挤掉），但没必要留着
        projections.forget(session.id());
    }

    private void appendStarted(Session session, Workspace workspace) {
        // 此刻这棵树必然空着（会话刚建、谁也还没见过这个 id）。拿不到租约就直接炸：
        // 那意味着它已经被人占着，继续往下走会在一条不属于我们的会话上留痕
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
     * <p>建会话时就已经建好了（见 {@link com.codeloom.app.workspace.WorkspaceProvisioner#ensure}），所以找不到意味着
     * 服务端状态不对 —— 500，不是 400。
     */
    private Workspace workspaceOf(Session session) {
        return stored.find(session.workspaceId()).orElseThrow(() -> new IllegalStateException(
                "会话 " + session.id() + " 所在的工作区不在库里：" + session.workspaceId()
                        + "。它本该在建会话时就写好 —— 现在找不到，只可能是那一行被删过。"));
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
