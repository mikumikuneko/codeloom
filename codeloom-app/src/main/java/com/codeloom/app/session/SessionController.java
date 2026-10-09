package com.codeloom.app.session;

import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.app.approval.ApprovalService;
import com.codeloom.app.note.AgentNotes;
import com.codeloom.app.turn.RunningTurns;
import com.codeloom.app.turn.TurnExecutor;
import com.codeloom.app.turn.TurnResult;
import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.User;
import com.codeloom.realtime.event.EventEnvelopeCodec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;

/**
 * 会话接口：建、查、列、发消息。
 *
 * <h2>{@code POST /messages} 是**阻塞**的</h2>
 * 它一直等到这一轮跑完才返回 —— 不为它引入任务队列，而"跑一轮"本来就有天然的超时
 *（模型调用和工具执行都有上限）。
 *
 * <p>代价是客户端可能等几十秒，所以进度**不从这个响应里看**：同时挂着
 * {@code GET /api/sessions/{id}/stream} 那条 SSE，模型打的字、跑的每个工具
 * 都在那儿实时出来；这个响应只负责最后给个结论。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionController {

    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final SessionService sessions;
    private final EventStore eventStore;
    private final TurnExecutor executor;
    private final RunningTurns turns;
    private final AgentNotes notes;
    private final ApprovalService approvals;
    private final EventEnvelopeCodec envelopeCodec = new EventEnvelopeCodec();

    public SessionController(CurrentUser currentUser,
                             ProjectAccess access,
                             SessionService sessions,
                             EventStore eventStore,
                             TurnExecutor executor,
                             RunningTurns turns,
                             AgentNotes notes,
                             ApprovalService approvals) {
        this.currentUser = currentUser;
        this.access = access;
        this.sessions = sessions;
        this.eventStore = eventStore;
        this.executor = executor;
        this.turns = turns;
        this.notes = notes;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------

    @PostMapping("/api/projects/{projectId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView create(Principal principal,
                              @PathVariable String projectId,
                              @RequestBody CreateSessionRequest request) {
        User me = currentUser.require(principal);
        // 是成员就能建 —— 每个成员一条自己的会话，这正是"两个人各跑各的"那个模型
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return sessions.view(sessions.create(me, project, request.toModelConfig()));
    }

    /**
     * 项目下的会话，**分页**。理由和「我的项目」一样：这个列表没有上限。
     *
     * @param offset 跳过多少条；负数按 0 处理（MySQL 的 {@code OFFSET} 不接受负数）
     * @param limit  这一页最多多少条
     */
    @GetMapping("/api/projects/{projectId}/sessions")
    public List<SessionView> list(Principal principal,
                                  @PathVariable String projectId,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "50") int limit) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return sessions.listFor(project, Math.clamp(limit, 1, MAX_PAGE), Math.max(0, offset));
    }

    @GetMapping("/api/sessions/{sessionId}")
    public SessionView one(Principal principal, @PathVariable String sessionId) {
        User me = currentUser.require(principal);
        // 看得见就行：队友的会话要能看到（那是"实时观战"的前提）
        return sessions.view(access.requireVisible(me.id(), SessionId.of(sessionId)));
    }

    /**
     * 发一句话，让它跑一轮。
     *
     * <p>用的是 {@code requireDriver} 而不是 {@code requireVisible}：
     * **看得见不等于能驱动**。允许成员驱动别人的会话，等于让 A 用自己的提示词
     * 去改 B 的工作区，而且审计流里再也说不清"这轮是谁让它跑的"。
     */
    @PostMapping("/api/sessions/{sessionId}/messages")
    public ResponseEntity<?> send(Principal principal,
                                  @PathVariable String sessionId,
                                  @RequestBody SendMessageRequest request) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));

        TurnResult result = executor.send(session.id(), request.text(), request.clientMessageId());

        // 密封的类型在这里必须被穷尽处理 —— 新增一种结果时这个方法编译不过。
        // 而下面这几个码是有讲究的：都不是"服务器错了"，客户端该做的是**换个时机重试**
        return switch (result) {
            case TurnResult.Completed completed -> ResponseEntity.ok(TurnView.of(completed.outcome()));
            // ★ **202，不是 409。** 这句话被受理了，只是还轮不到它 ——
            //   客户端该做的是把它显示成"排队中"，而不是报错、更不是让用户重试
            case TurnResult.Queued queued -> ResponseEntity.accepted()
                    .body(new QueuedView(queued.ahead()));
            case TurnResult.Busy busy -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完");
            // Busy 说"等会儿再来"，这个说"别再来了" —— 两者的 409 文案必须不一样，
            // 否则客户端会把去重当成一次普通的忙而反复重试
            case TurnResult.Duplicate duplicate -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "这个请求之前已经处理过了（同一个 clientMessageId）。别再重试，"
                            + "去 GET /api/sessions/{id}/events 看上次跑出了什么");
            case TurnResult.LeaseLost lost -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "执行权已被另一实例接管，请稍后重试");
            case TurnResult.Failed failed -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, failed.reason());
            // ★ **200，不是 5xx。** 用户按 Esc 把这一轮停下了 —— 那不是服务器出错，
            //   也不是"换个时机重试"。会话照常回到等用户指示，事件里记的是谁停了它
            case TurnResult.Interrupted interrupted -> ResponseEntity.ok().build();
        };
    }

    /**
     * 一句话排上队了。
     *
     * @param ahead 它前面还排着几句（不含自己）。0 表示下一个就是它 ——
     *              界面上据此说"前面还有 N 句"，而不是干巴巴一句"已收下"
     */
    public record QueuedView(int ahead) {
    }

    /**
     * 打断正在跑的那一轮。
     *
     * <p>返回 **202** 而不是 200：中断是一个**信号**，不是一条命令。取消的检查点在
     * 工具边界上（每一轮模型调用前、每个工具调用前），所以"信号发出去了"和
     * "它停了"之间隔着一段不确定的时间 —— 而且这一轮也可能已经刚好跑完了。
     * 声称 200 会让客户端以为已经停了。
     *
     * <p>只要**驱动权**：能驱动一轮的人才能打断它。
     */
    @PostMapping("/api/sessions/{sessionId}/interrupt")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void interrupt(Principal principal, @PathVariable String sessionId) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));
        turns.interrupt(session.id());
    }

    // ------------------------------------------------------------------

    /**
     * 换这条会话用的模型。
     *
     * <p>要**驱动权**：它影响这条会话**往后所有轮次**，和"发消息驱动一轮"是同一个级别。
     *
     * <p>**下一轮生效**：当前正在跑的那一轮已经拿到配置快照了，不会中途改靶 ——
     * 否则"这一轮用的是哪个模型"就说不清了。所以这个接口**在会话正忙时会返回 409**，
     * 而不是排队等 —— 等到了也是个不确定的时机，不如让用户看到"现在不行"。
     */
    @PutMapping("/api/sessions/{sessionId}/model")
    public SessionView changeModel(Principal principal,
                                   @PathVariable String sessionId,
                                   @RequestBody ChangeModelRequest request) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));
        if (request.modelId() == null || request.modelId().isBlank()) {
            // 不默认任何模型：猜错的话，用户会在下一次提问时才发现"怎么用的还是上一个"
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出要换成哪个模型");
        }
        if (request.provider() == null || request.provider().isBlank()) {
            // 同理不默认任何一家：留着旧的那家会变成"名字换了，请求还发到原来那家"
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出要换成哪一家");
        }
        return sessions.view(
                sessions.switchModel(session, ProviderId.of(request.provider()), request.modelId()));
    }

    /**
     * 批或者拒一次挂起的工具调用，然后让这条会话接着跑。
     *
     * <p>要**驱动权**，也就是**只有会话所有者**能批。这不是保守 —— 是"平等协作"的直接
     * 推论：B 用自己的 key、自己的提示词驱动自己的 agent，A 既不知道它在干什么、
     * 也不知道为什么，让 A 来点这个"同意"等于让 A 替 B 的选择负责。
     *
     * <p>返回 **202**：答复记下了，而那一轮**还没跑完** —— 它随后会被续跑。
     */
    @PostMapping("/api/sessions/{sessionId}/approvals/{callId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resolveApproval(Principal principal,
                                @PathVariable String sessionId,
                                @PathVariable String callId,
                                @RequestBody ApprovalRequest request) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));
        if (request.approved() == null) {
            // 不默认任何一边：默认"批"是放行一个本来要人点头的操作，
            // 默认"拒"是替用户拒绝掉他可能想批准的事 —— 两样都不该猜
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "必须明确说批还是不批（approved 为 true 或 false）");
        }
        // 记的是 **id** 不是用户名：审批记录要能回答"这个危险操作是谁放行的"，
        // 而用户名会变（唯一不等于它是身份，见 ToolApprovalResolved）。名字由界面按 id 现查
        approvals.resolve(session.id(), callId, request.approved(), me.id(),
                request.reason());
    }

    /**
     * 这条会话上**还排着队、没投递出去**的留言。
     *
     * <p>存在的理由只有一个：让人答得出「我发的留言到哪了」。
     * 发出去时拿到的是 202（"收到了"），但从"收到"到"投递给 agent"之间隔着
     * 一条队列 —— 没有这个接口的话，那段等待是**完全不可见**的。
     */
    @GetMapping("/api/sessions/{sessionId}/notes")
    public List<NoteView> pendingNotes(Principal principal, @PathVariable String sessionId) {
        User me = currentUser.require(principal);
        Session session = access.requireVisible(me.id(), SessionId.of(sessionId));
        return notes.pendingInQueue(session.id()).stream().map(NoteView::of).toList();
    }

    /**
     * 捎一句话到对方的会话里。**202 不是 200**：这句话先进队列，
     * 对方的 agent 下一轮才看得到 —— 这一轮已经在跑的那些不受影响。
     */
    @PostMapping("/api/sessions/{sessionId}/notes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendNote(Principal principal,
                         @PathVariable String sessionId,
                         @RequestBody SendNoteRequest request) {
        User me = currentUser.require(principal);
        // 发起方必须**是你自己的会话**：留言会以"某某的 agent"的身份出现在对方的
        // 上下文里，所以不能借别人的会话名义说话
        Session from = access.requireDriver(me.id(), SessionId.of(request.fromSessionId()));
        // 收件方只要看得见 —— 捎句话是协作，不是"让别人的 agent 替我干活"
        // （那是 /messages 要驱动权的原因）
        Session to = access.requireVisible(me.id(), SessionId.of(sessionId));

        notes.enqueue(to.id(), new AgentNoteDelivered(from.id(), me.id(), request.text()));
    }

    /**
     * 这条会话的事件流，按 seq 升序。
     *
     * <p>用途是**初次加载和回放**：SSE 那条只推"从现在起发生的事"，
     * 打开页面时该显示的历史得从这儿拉。
     *
     * <p>返回的每一条就是 {@code EventEnvelopeCodec} 编出来的那个 JSON ——
     * 和 SSE 帧的 {@code data:}、以及 Redis Pub/Sub 的消息体**是同一份格式**。
     * 让三条路径共用一份，是因为"同一个事件在两处长得不一样"这种问题
     * 只会在多实例联调时才冒出来，而那时候谁也不会先怀疑格式。
     */
    @GetMapping("/api/sessions/{sessionId}/events")
    public List<JsonNode> events(Principal principal,
                                 @PathVariable String sessionId,
                                 @RequestParam(defaultValue = "0") long afterSeq,
                                 @RequestParam(defaultValue = "200") int limit) {
        User me = currentUser.require(principal);
        Session session = access.requireVisible(me.id(), SessionId.of(sessionId));
        // 上限是保护服务端的：一个客户端不该能要求一次拉回无限多的事件。
        // 下限也是：limit=0 会让调用方以为"没有事件"，而那是另一回事
        int bounded = Math.clamp(limit, 1, MAX_EVENTS_PER_REQUEST);
        return eventStore.readAfter(session.id(), afterSeq, bounded).stream()
                .map(stored -> envelopeJson(EventEnvelope.of(stored)))
                .toList();
    }

    /**
     * 丢弃这条会话：**对话没了，代码留着**。
     *
     * <h2>为什么这里不是"删掉那么暴力"</h2>
     * 工作区属于「人 + 项目」而不是某条会话（见 {@code WorkspaceId}），所以丢掉一段对话
     * 不会让上一轮的改动消失 —— 新开一条会话就从原地继续。这正是这个动作存在的理由：
     * 收拾对话列表，不必拿代码陪葬。
     *
     * <h2>代价说清楚</h2>
     * 事件流是**真删**，于是它打过的 checkpoint 也一并没了（事后没法再按它回滚，
     * 但代码在树的分支上，一点没少），队友引用过的那条事件也成了悬空引用。
     *
     * <h2>权限与时机</h2>
     * 要**驱动权**：只能丢自己的对话（别人的能看、不能删）。正在跑的时候是 409 ——
     * 一边删一边写会得到一个谁也说不清的结果。
     *
     * <p>返回 204：没有内容可回，客户端照着删掉本地那一条就行。
     */
    @DeleteMapping("/api/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(Principal principal, @PathVariable String sessionId) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));
        sessions.discard(session);
    }

    @GetMapping("/api/sessions/{sessionId}/checkpoints")
    public List<CheckpointView> checkpoints(Principal principal, @PathVariable String sessionId) {
        User me = currentUser.require(principal);
        return sessions.checkpoints(access.requireVisible(me.id(), SessionId.of(sessionId)));
    }

    /**
     * 回滚到一个 checkpoint。
     *
     * <p>要**驱动权**而不是可见权：回滚会改工作区里的代码，
     * 和"发消息驱动一轮"是同一个级别的权限。
     *
     * <p>它只动**自己那棵树**，不碰主干也不碰别人 —— 所以除了"会话正在执行中"（409），
     * 没有别的拒绝理由。目标不是这条会话自己打过的一条 checkpoint 则是 400。
     */
    @PostMapping("/api/sessions/{sessionId}/rewind")
    public SessionView rewind(Principal principal,
                              @PathVariable String sessionId,
                              @RequestBody RewindRequest request) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));
        if (request.toCheckpointSeq() == null) {
            // 用包装类型 + 显式检查，而不是 `long`：字段缺了会被读成 0，
            // 那是个"看着像真值"的位置，而它并不是任何一条 checkpoint（见 SessionRewound）
            throw new IllegalArgumentException("要回滚到哪一条 checkpoint：请求里缺 toCheckpointSeq");
        }
        // 传的是 **id**：事后要查得出是谁下的手，而用户名会变（见 SessionRewound）。
        // 名字由界面按 id 现查
        return sessions.view(sessions.rewind(session, request.toCheckpointSeq(), me.id()));
    }

    private JsonNode envelopeJson(EventEnvelope envelope) {
        // 直接拿对象树（而不是"编成字符串再解析回来"）—— 一次拉上千条事件时，
        // 那一来一回的转换是白花的。嵌进响应里仍然是一个**对象**，
        // 而不是一串被转义过反斜杠的字符串，排查时能直接 jq 剥开
        return envelopeCodec.writeToNode(envelope);
    }

    // ------------------------------------------------------------------

    private static final int MAX_EVENTS_PER_REQUEST = 1000;

    /** 列表类接口一页最多多少条。和事件流那个上千的上限不同：这里拦的是"没上限"。 */
    private static final int MAX_PAGE = 200;

    /**
     * 建会话要带的东西：**只有"哪一家、哪个模型"**。
     *
     * <p>temperature、maxTokens、systemPrompt 三项都不收，因为**没有一项是用户该给的**：
     * 温度我们不发送（见 {@code ModelConfig}），输出上限是模型的属性，
     * 系统提示词是平台定的那一段（服务层面把它整个覆盖掉，收了等于没收）。
     *
     * <p>收了但忽略比不收更坏 —— 它让"用户能配这个"看起来像个功能，见 {@code SystemPrompt}
     * 的类注释。
     */
    public record CreateSessionRequest(String provider, String modelId) {

        ModelConfig toModelConfig() {
            // 系统提示词先留空：平台那一段由 SessionService 在建会话时写进去
            return new ModelConfig(ProviderId.of(provider), modelId, null);
        }
    }

    /**
     * 发一句话让它跑一轮。
     *
     * @param clientMessageId 客户端生成的请求标识，用来挡重复。
     *                        **重试时必须复用同一个值** —— 每次重试都新生成一个的话，
     *                        这层保护等于不存在。不传（null）则跳过检查，那是给
     *                        "不介意重复"的调用方留的口子
     */
    public record SendMessageRequest(String text, String clientMessageId) {
    }

    /**
     * 换这条会话用的模型。
     *
     * <p>供应商和模型名一起给 —— 界面上那两个下拉本来就是一体的：模型列表是问某一家
     * 要来的，换家必然连模型一起换。
     *
     * <p>要防的是**换成一家没配过密钥的** —— 那个由
     * {@link SessionService#switchModel} 当场挡掉，而不是靠不给这个字段。
     *
     * @param provider 换成哪一家（决定用哪把密钥、走哪个端点）
     * @param modelId  比如 {@code deepseek-chat}
     */
    public record ChangeModelRequest(String provider, String modelId) {
    }

    /**
     * @param toCheckpointSeq 回滚到**哪一条** checkpoint —— 它在事件流里的 seq。
     *                        调用方从 {@code GET /api/sessions/{id}/events} 那条流里拿到
     *                        （回滚面板本来就在读那条流）。**不是那个 commit sha**：
     *                        一轮什么都没改时两条 checkpoint 会同 sha，用它定不了位，
     *                        见 {@code CheckpointCreated} 的类注释
     */
    public record RewindRequest(Long toCheckpointSeq) {
    }

    /**
     * 给这条会话捎一句话。
     *
     * <p>{@code fromSessionId} 必填，而且必须是**发起者自己的**会话 ——
     * 留言会以"某某的 agent"的身份进入对方的上下文，不能借别人的会话名义说话。
     */
    public record SendNoteRequest(String fromSessionId, String text) {
    }

    /**
     * 排队中的一条留言。**没有投递时间** —— 它还没投递。
     *
     * <p>{@code fromUserId} 是 **id**，和事件里那条规则一致（见 {@code AgentNoteDelivered}）：
     * 用户名由读的那一侧按 id 现查。
     */
    public record NoteView(String fromSessionId, String fromUserId, String text) {

        static NoteView of(AgentNoteDelivered note) {
            return new NoteView(note.fromSessionId().value(), note.fromUserId().value(), note.text());
        }
    }

    /**
     * 对一次挂起调用的答复。
     *
     * @param approved 用包装类型而不是 {@code boolean}：漏传这个字段时应该是
     *                 "你没说要怎么办"，而不是被静默当成 {@code false}（拒绝）
     * @param reason   拒绝的理由。批准时可以为空；拒绝时它是模型唯一的线索
     */
    public record ApprovalRequest(Boolean approved, String reason) {
    }
}
