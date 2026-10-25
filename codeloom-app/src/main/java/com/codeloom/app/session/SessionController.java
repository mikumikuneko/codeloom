package com.codeloom.app.session;

import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.app.approval.ApprovalService;
import com.codeloom.app.note.AgentNotes;
import com.codeloom.app.turn.RunningTurns;
import com.codeloom.app.turn.TurnExecutor;
import com.codeloom.app.turn.TurnResult;
import com.codeloom.app.web.PageLimits;
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
 * 会话接口：建、查、列、发消息、中断、换模型、审批、留言、读事件、丢弃、回滚。
 *
 * <p>{@code POST /messages} 在没有一轮在跑时阻塞到这一轮结束，然后返回最终结论；已有
 * 一轮在跑时只进内存排队并立刻返回 202。模型调用和工具执行本身都有超时，不引入持久
 * 任务队列。进度不从该响应看：同时挂着的 SSE {@code GET /api/sessions/{id}/stream}
 * 实时输出。
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

    /**
     * 建一条会话。
     *
     * @param request 供应商与模型；两者都必须存在
     * @return 新会话视图
     */
    @PostMapping("/api/projects/{projectId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView create(Principal principal,
                              @PathVariable String projectId,
                              @RequestBody CreateSessionRequest request) {
        User me = currentUser.require(principal);
        // 是成员就能建：每个成员一条自己的会话，这是"两个人各跑各的"的模型
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return sessions.view(sessions.create(me, project, request.toModelConfig()));
    }

    /**
     * 项目下的会话，分页。
     *
     * @param offset 跳过多少条；负数按 0 处理（MySQL 的 {@code OFFSET} 不接受负数）
     * @param limit  这一页最多多少条；夹在 1 到 {@link PageLimits#MAX} 之间
     * @return 这一页会话
     */
    @GetMapping("/api/projects/{projectId}/sessions")
    public List<SessionView> list(Principal principal,
                                  @PathVariable String projectId,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = PageLimits.DEFAULT_PARAM) int limit) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return sessions.listFor(project, PageLimits.limit(limit), PageLimits.offset(offset));
    }

    /**
     * 单条会话；队友的会话也可见，这是实时观战的前提。
     *
     * @return 会话视图
     */
    @GetMapping("/api/sessions/{sessionId}")
    public SessionView one(Principal principal, @PathVariable String sessionId) {
        User me = currentUser.require(principal);
        return sessions.view(access.requireVisible(me.id(), SessionId.of(sessionId)));
    }

    /**
     * 发一句话，让它跑一轮。
     *
     * <p>要求驱动权而不是可见权：看得见不等于能驱动。允许成员驱动别人的会话，等于让 A
     * 用自己的提示词改 B 的工作区，审计里也说不清这一轮是谁让它跑的。
     *
     * @param request 消息正文与可选的幂等键
     * @return 最终结论；排队时为 202 加排队位置
     */
    @PostMapping("/api/sessions/{sessionId}/messages")
    public ResponseEntity<?> send(Principal principal,
                                  @PathVariable String sessionId,
                                  @RequestBody SendMessageRequest request) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));

        TurnResult result = executor.send(session.id(), request.text(), request.clientMessageId());

        // sealed 类型必须穷尽处理，新增结果时编译不过。下面几个码都不是"服务器错了"，
        // 客户端该做的是换个时机重试
        return switch (result) {
            case TurnResult.Completed completed -> ResponseEntity.ok(TurnView.of(completed.outcome()));
            // 202 而不是 409：消息已被受理，只是还没轮到它，客户端应显示"排队中"
            case TurnResult.Queued queued -> ResponseEntity.accepted()
                    .body(new QueuedView(queued.ahead()));
            case TurnResult.Busy busy -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完");
            // Duplicate 的 409 文案必须与 Busy 不同，否则客户端会把去重当成普通的忙而反复重试
            case TurnResult.Duplicate duplicate -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "这个请求之前已经处理过了（同一个 clientMessageId）。别再重试，"
                            + "去 GET /api/sessions/{id}/events 看上次跑出了什么");
            case TurnResult.LeaseLost lost -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "执行权已被另一实例接管，请稍后重试");
            case TurnResult.Failed failed -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, failed.reason());
            // 200 而不是 5xx：用户主动停下这一轮，不是服务器错误，也不该重试；
            // 会话回到等用户指示，事件里记着是谁停的
            case TurnResult.Interrupted interrupted -> ResponseEntity.ok().build();
        };
    }

    /**
     * 一句话排上队了。
     *
     * @param ahead 前面还排着几句（不含自己）；0 表示下一个就是它
     */
    public record QueuedView(int ahead) {
    }

    /**
     * 打断正在跑的那一轮。
     *
     * <p>返回 202 而不是 200：中断是信号，不是命令。取消检查点在模型调用前和工具调用前，
     * 信号发出与真正停下之间有一段不确定时间，这一轮也可能刚好跑完；200 会让客户端以为
     * 已经停了。只有能驱动这一轮的人才能打断。
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
     * 换这条会话用的模型，下一轮生效。
     *
     * <p>要驱动权：它影响往后所有轮次，与发消息驱动一轮同级。正在跑的那一轮已经拿到配置
     * 快照，不会中途换；因此会话正忙时返回 409，而不是排队等一个不确定的时机。
     *
     * <p>不默认任何供应商或模型：猜错会让用户在下一次提问时才发现用的还是旧的。
     */
    @PutMapping("/api/sessions/{sessionId}/model")
    public SessionView changeModel(Principal principal,
                                   @PathVariable String sessionId,
                                   @RequestBody ChangeModelRequest request) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));
        if (request.modelId() == null || request.modelId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出要换成哪个模型");
        }
        if (request.provider() == null || request.provider().isBlank()) {
            // 不默认沿用旧的供应商：那会变成"名字换了，请求还发到原来那家"
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出要换成哪一家");
        }
        return sessions.view(
                sessions.switchModel(session, ProviderId.of(request.provider()), request.modelId()));
    }

    /**
     * 批或拒一次挂起的工具调用，然后让会话接着跑。
     *
     * <p>要驱动权，即只有会话所有者能批：B 用自己的密钥和提示词驱动自己的 agent，A 不知道
     * 它在做什么，替它点"同意"等于替 B 的选择负责。
     *
     * <p>返回 202：答复已记下，但那一轮还没跑完，随后会被续跑。
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
            // 不默认任何一边：默认批是放行一个本要人点头的操作，默认拒是替用户拒绝；
            // 两者都不该猜
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "必须明确说批还是不批（approved 为 true 或 false）");
        }
        // 记 id 而不是用户名：审批记录要回答"谁放行了这个危险操作"，而用户名会变；
        // 名字由界面按 id 现查（见 ToolApprovalResolved）
        approvals.resolve(session.id(), callId, request.approved(), me.id(),
                request.reason());
    }

    /**
     * 这条会话上还排着队、尚未投递的留言，用来回答"我发的留言到哪了"。
     *
     * <p>发送时拿到的是 202，从"收到"到"投递给 agent"之间隔着一条队列；没有这个接口，
     * 那段等待完全不可见。
     *
     * @return 排队中的留言
     */
    @GetMapping("/api/sessions/{sessionId}/notes")
    public List<NoteView> pendingNotes(Principal principal, @PathVariable String sessionId) {
        User me = currentUser.require(principal);
        Session session = access.requireVisible(me.id(), SessionId.of(sessionId));
        return notes.pendingInQueue(session.id()).stream().map(NoteView::of).toList();
    }

    /**
     * 捎一句话到对方的会话。返回 202：留言先进队列，对方的 agent 下一轮才看得到，
     * 已经在跑的这一轮不受影响。
     */
    @PostMapping("/api/sessions/{sessionId}/notes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendNote(Principal principal,
                         @PathVariable String sessionId,
                         @RequestBody SendNoteRequest request) {
        User me = currentUser.require(principal);
        // 发起方必须是你自己的会话：留言会以"某某的 agent"的身份进入对方上下文，
        // 不能借别人的会话名义说话
        Session from = access.requireDriver(me.id(), SessionId.of(request.fromSessionId()));
        // 收件方只要看得见：捎句话是协作，不是让别人的 agent 替我干活
        //（后者是 /messages 要求驱动权的原因）
        Session to = access.requireVisible(me.id(), SessionId.of(sessionId));

        notes.enqueue(to.id(), new AgentNoteDelivered(from.id(), me.id(), request.text()));
    }

    /**
     * 这条会话的事件流，按 seq 升序，是一次性的 REST 快照。
     *
     * <p>界面不用它：SSE 连接时会自己补历史，断线重连靠 {@code Last-Event-ID} 接着补。
     * 这里留给需要静态事件列表的调用方（导出、调试、测试），分页参数与 SSE 不同。
     *
     * <p>每一条与 SSE 帧的 {@code data:}、Redis Pub/Sub 的消息体使用同一份
     * {@code EventEnvelopeCodec} 输出格式；否则同一事件在多实例联调时会出现两种形状。
     *
     * @param afterSeq 只返回这个 seq 之后的事件
     * @param limit    最多返回多少条；夹在 1 到 {@link PageLimits#MAX} 之间
     * @return 事件 JSON 列表
     */
    @GetMapping("/api/sessions/{sessionId}/events")
    public List<JsonNode> events(Principal principal,
                                 @PathVariable String sessionId,
                                 @RequestParam(defaultValue = "0") long afterSeq,
                                 @RequestParam(defaultValue = PageLimits.DEFAULT_PARAM) int limit) {
        User me = currentUser.require(principal);
        Session session = access.requireVisible(me.id(), SessionId.of(sessionId));
        // 上限保护服务端，防止一次拉回无限多；下限也保护语义：limit=0 会被误读成"没有事件"。
        // 与别的列表共用 PageLimits，同一个 ?limit= 在各端点含义一致
        int bounded = PageLimits.limit(limit);
        return eventStore.readAfter(session.id(), afterSeq, bounded).stream()
                .map(stored -> envelopeJson(EventEnvelope.of(stored)))
                .toList();
    }

    /**
     * 丢弃这条会话：对话没了，代码留着。
     *
     * <p>工作区属于「人 + 项目」而不是会话（见 {@code WorkspaceId}），所以新开一条会话会从
     * 原地继续。事件流是真删，它打过的 checkpoint 一并消失，代码仍在树的分支上；
     * 队友引用过的 {@code anchorEventSeq} 会成为悬空引用。
     *
     * <p>要驱动权，只能丢自己的对话；会话正在跑时返回 409。返回 204，客户端删掉本地那一条。
     */
    @DeleteMapping("/api/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(Principal principal, @PathVariable String sessionId) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));
        sessions.discard(session);
    }

    /**
     * 回滚到一个 checkpoint。
     *
     * <p>要驱动权而不是可见权：回滚会改工作区里的代码，与驱动一轮同级。它只动自己那棵树，
     * 不碰主干和别人的树，因此除"会话正在执行中"（409）外没有别的拒绝理由；
     * 目标不是这条会话的 checkpoint 时为 400。
     *
     * @param request 目标 checkpoint 的事件序号
     * @return 回滚后的会话
     */
    @PostMapping("/api/sessions/{sessionId}/rewind")
    public SessionView rewind(Principal principal,
                              @PathVariable String sessionId,
                              @RequestBody RewindRequest request) {
        User me = currentUser.require(principal);
        Session session = access.requireDriver(me.id(), SessionId.of(sessionId));
        if (request.toCheckpointSeq() == null) {
            // 用包装类型并显式检查，而不是 long：字段缺失会被读成 0，
            // 那看起来像真值，却不是任何一条 checkpoint
            throw new IllegalArgumentException("要回滚到哪一条 checkpoint：请求里缺 toCheckpointSeq");
        }
        // 传 id：事后要查得出是谁下的手，而用户名会变；名字由界面按 id 现查
        return sessions.view(sessions.rewind(session, request.toCheckpointSeq(), me.id()));
    }

    private JsonNode envelopeJson(EventEnvelope envelope) {
        // 直接取对象树，避免"编码成字符串再解析回来"的开销；嵌进响应后仍是对象，
        // 不是带转义反斜杠的字符串，可直接用 jq 查看
        return envelopeCodec.writeToNode(envelope);
    }

    // ------------------------------------------------------------------

    /**
     * 建会话只需要"哪一家、哪个模型"。
     *
     * <p>temperature、maxTokens、systemPrompt 都不收：温度不发送（见 {@code ModelConfig}），
     * 输出上限是模型的属性，系统提示词由平台整体覆盖。收了却忽略比不收更坏，
     * 会让"用户能配这个"看起来像个功能（见 {@code SystemPrompt}）。
     *
     * @param provider 供应商 id
     * @param modelId  模型 id
     */
    public record CreateSessionRequest(String provider, String modelId) {

        ModelConfig toModelConfig() {
            // 系统提示词先留空，由 SessionService 在建会话时写入平台那一段
            return new ModelConfig(ProviderId.of(provider), modelId, null);
        }
    }

    /**
     * 发一句话让它跑一轮。
     *
     * @param text            消息正文
     * @param clientMessageId 客户端生成的请求标识，用于去重；重试时必须复用同一个值，
     *                        每次新生成会让这层保护失效。null 表示跳过检查
     */
    public record SendMessageRequest(String text, String clientMessageId) {
    }

    /**
     * 换这条会话用的模型。
     *
     * <p>供应商和模型一起给：模型列表是向某一家要来的，换供应商必然连模型一起换。
     * 未配置密钥的供应商由 {@link SessionService#switchModel} 当场挡掉。
     *
     * @param provider 换成哪一家，决定用哪把密钥、走哪个端点
     * @param modelId  模型 id，例如 {@code deepseek-chat}
     */
    public record ChangeModelRequest(String provider, String modelId) {
    }

    /**
     * @param toCheckpointSeq 目标 checkpoint 在事件流里的 seq，从
     *                        {@code GET /api/sessions/{id}/events} 获取；不是 commit sha，
     *                        一轮没有改动时两条 checkpoint 会共用 sha，无法定位
     */
    public record RewindRequest(Long toCheckpointSeq) {
    }

    /**
     * 给这条会话捎一句话。
     *
     * @param fromSessionId 发起者的会话，必须属于发起者本人；留言会以
     *                      "某某的 agent"的身份进入对方上下文
     * @param text          留言正文
     */
    public record SendNoteRequest(String fromSessionId, String text) {
    }

    /**
     * 排队中的一条留言；没有投递时间，因为它还没有投递。
     *
     * @param fromSessionId 发起会话 id
     * @param fromUserId    发起者 id，不是用户名；名字由读取方按 id 现查
     * @param text          留言正文
     */
    public record NoteView(String fromSessionId, String fromUserId, String text) {

        static NoteView of(AgentNoteDelivered note) {
            return new NoteView(note.fromSessionId().value(), note.fromUserId().value(), note.text());
        }
    }

    /**
     * 对一次挂起调用的答复。
     *
     * @param approved 用包装类型：漏传时表示"没有说明怎么办"，而不是静默按 false（拒绝）处理
     * @param reason   拒绝理由；批准时可以为空，拒绝时是模型唯一的线索
     */
    public record ApprovalRequest(Boolean approved, String reason) {
    }
}
