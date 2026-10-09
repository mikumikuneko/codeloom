package com.codeloom.app.merge;

import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 合并与冲突裁决。
 *
 * <p>路径挂在会话下面（{@code /api/sessions/{id}/merge}）而不是项目下面，是因为
 * "合并"这个动作的主语是**一条会话的产出** —— 虽然它改的是主干。
 * 只有会话的所有者能触发它（{@code requireDriver}）：把别人的会话合进主干，
 * 等于替他们决定"这些工作可以进主干了吗"。
 *
 * <p>冲突裁决过程中的那几步（列冲突、裁决、放弃）**也挂在同一条会话下面**：
 * 待裁决的合并是由这条会话的合并发起的，跟着它走比另开一个"当前合并"的资源更直白。
 * 代价是客户端得记着"我正在裁决的是哪条会话的合并"—— 而它本来就该知道。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MergeController {

    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final ProjectRepository projects;
    private final MergeService merges;
    private final SessionSyncService syncs;

    public MergeController(CurrentUser currentUser,
                           ProjectAccess access,
                           ProjectRepository projects,
                           MergeService merges,
                           SessionSyncService syncs) {
        this.currentUser = currentUser;
        this.access = access;
        this.projects = projects;
        this.merges = merges;
        this.syncs = syncs;
    }

    /**
     * 把这条会话的产出合进主干，**并验证主干**。
     *
     * <p>有冲突时返回 **409** 而不是 200 —— 那确实不是一次成功的合并，
     * 而且客户端必须据此进入裁决流程。冲突清单跟着 409 的响应体一起给，
     * 免得客户端还要再问一次。
     *
     * <p><strong>验证没过也是 200。</strong> 合并本身成功了；验证结论说的是另一件事
     * （"主干现在可能坏了"）。把它变成错误码会让人以为合并没做成 ——
     * 于是去重试合并，而代码其实已经在主干上了。要看的是响应里的 {@code verification}。
     *
     * <p>请求体可以整个不给（合到这条会话的最新），也可以给一个 {@code toCommitSha}
     * 指定合到**某个 checkpoint** —— 那就是"小步合并"：不攒完整个会话再合，做一段合一段。
     */
    @PostMapping("/api/sessions/{sessionId}/merge")
    public MergeOutcome merge(Principal principal,
                              @PathVariable String sessionId,
                              @RequestBody(required = false) MergeRequest request) {
        Session session = driverSession(principal, sessionId);
        return merges.mergeIntoMain(session, requireProject(session),
                request == null ? null : request.toCommitSha());
    }

    /**
     * 把**主干**的最新状态拉进这条会话的工作区 —— 合并的反方向，也叫"先同步再合"里的那一步。
     *
     * <p>为什么它会大幅减少撞车：对方的改动进了我的工作区之后，**我的 agent 读得到** ——
     * 它就有机会自己发现"已经有个同职责的类了"。而这件事**只有在动手之前做才有效**，
     * 所以它是一个可以单独点的动作，不是合并的内部步骤。
     *
     * <p>有冲突时返回 **409**，和合并一样：会话的工作区里留下一个未完成的合并，
     * 接着走裁决流程。裁决那几个端点会**自己找到它** —— 客户端不需要记着"我在裁决哪一个"。
     */
    @PostMapping("/api/sessions/{sessionId}/sync")
    public SyncOutcome sync(Principal principal, @PathVariable String sessionId) {
        Session session = driverSession(principal, sessionId);
        return syncs.sync(session);
    }

    /** 待裁决的冲突，连同两侧内容。 */
    @GetMapping("/api/sessions/{sessionId}/conflicts")
    public ConflictsView conflicts(Principal principal, @PathVariable String sessionId) {
        Session session = driverSession(principal, sessionId);
        return merges.conflicts(session, requireProject(session));
    }

    /**
     * 裁决一个文件的冲突。最后一个裁决完之后合并会自动完成。
     *
     * <p>三种裁决方式，二选一地给参数：
     * <pre>
     *   {"path":"main.java","side":"main"}                    // 整份取主干那侧
     *   {"path":"main.java","side":"session"}                 // 整份取会话那侧
     *   {"path":"main.java","content":"…人合好的全文…"}        // 自己给出结果
     * </pre>
     *
     * <p>两侧用 {@code main} / {@code session} 而不是 {@code ours} / {@code theirs}：
     * 后者是相对当前分支的，方向一变就反过来（见 {@link MergeDirection}）。
     *
     * <p>第三种不是"高级选项"，它是最常见的那个正确答案的表达方式 ——
     * 两个人各加了一个方法时，两份都留才对，而取一侧必然丢掉另一个。
     *
     * <p>要**驱动权**：裁决的是这条会话的产出怎么进主干，和驱动它是同一级别的决定。
     */
    @PostMapping("/api/sessions/{sessionId}/conflicts/resolve")
    public MergeOutcome resolve(Principal principal,
                                @PathVariable String sessionId,
                                @RequestBody ResolveRequest request) {
        Session session = driverSession(principal, sessionId);
        return merges.resolve(session, requireProject(session), request.path(), request.toResolution());
    }

    @PostMapping("/api/sessions/{sessionId}/merge/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abort(Principal principal, @PathVariable String sessionId) {
        Session session = driverSession(principal, sessionId);
        merges.abort(session, requireProject(session));
    }

    // ------------------------------------------------------------------

    private Session driverSession(Principal principal, String sessionId) {
        User me = currentUser.require(principal);
        return access.requireDriver(me.id(), SessionId.of(sessionId));
    }

    private Project requireProject(Session session) {
        return projects.findById(session.projectId()).orElseThrow(() ->
                new IllegalStateException("会话所属的项目不存在：" + session.projectId()));
    }

    /**
     * @param toCommitSha 合到哪个点。**省略 = 合到这条会话分支的最新**（全部产出）。
     *                    给了就必须是这条会话打过的**一个 checkpoint** —— 那个 sha 从
     *                    {@code GET /api/sessions/{id}/checkpoints} 拿。
     *
     *                    <p>它不是"高级选项"：一轮的产出就是一个 checkpoint，
     *                    所以"做一段合一段"用的是同一个动作，只是把终点提前
     */
    public record MergeRequest(String toCommitSha) {
    }

    /**
     * @param side    {@code "session"} 用**会话工作区**那侧，{@code "main"} 用**主干**那侧。
     *                <p>**不是** {@code ours}/{@code theirs} —— 那两个词随合并方向反转
     *                （见 {@link MergeDirection}），而这一个参数要能让人一次说清"我要哪一份"。
     *                方向到 {@code ours} 的翻转收在 {@code ProjectMerger.resolve} 里
     * @param content 人给出的裁决后全文。**给了它就忽略 {@code side}**
     */
    public record ResolveRequest(String path, String side, String content) {

        ConflictResolution toResolution() {
            // content 优先：它是最明确的表达 —— 人说"我要的就是这个"，不用再去猜 side
            if (content != null) {
                return new ConflictResolution.WithContent(content);
            }
            if ("session".equalsIgnoreCase(side)) {
                return new ConflictResolution.KeepSide(ConflictResolution.KeepSide.Side.SESSION);
            }
            if ("main".equalsIgnoreCase(side)) {
                return new ConflictResolution.KeepSide(ConflictResolution.KeepSide.Side.MAIN);
            }
            // 两个都没给（或者 side 写错了）时**直接报错**，而不是默认取一侧 ——
            // 静默地替人做这样一个决定，会让另一个人的代码无声无息地消失
            throw new IllegalArgumentException(
                    "要给出 side（session 或 main），或者直接给出裁决后的 content");
        }
    }
}
