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
 * 合并与冲突裁决的 HTTP 入口。
 *
 * <p>路径挂在会话下而不是项目下：合并的主语是一条会话的产出，虽然它改的是主干。
 * 只有会话的驱动者能触发（{@code requireDriver}），因为把别人的会话合进主干等于替
 * 他们决定这些工作能否进主干。裁决的几步也挂在这条会话下，客户端需要记住正在裁决
 * 的是哪条会话的合并。
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
     * 把这条会话的产出合进主干，并验证主干。
     *
     * <p>有冲突时返回 409，响应体带冲突清单，客户端据此进入裁决流程。验证未通过仍是
     * 200：合并本身成功，{@code verification.passed} 说的是主干现在好不好；改成错误码
     * 会让人以为合并没做成而重试，而代码已经在主干上了。
     *
     * <p>请求体可省略（合到分支最新），也可给 {@code toCommitSha} 合到某个 checkpoint
     * （小步合并）。
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
     * 把主干的最新状态拉进这条会话的工作区，是合并的反方向，也是"先同步再合"的前一步。
     *
     * <p>能单独点，是因为只有动手之前同步，agent 才读得到对方已有的实现。
     * 有冲突时返回 409，冲突留在会话的工作区，后续裁决端点会自己找到它。
     */
    @PostMapping("/api/sessions/{sessionId}/sync")
    public SyncOutcome sync(Principal principal, @PathVariable String sessionId) {
        Session session = driverSession(principal, sessionId);
        return syncs.sync(session);
    }

    /** 待裁决的冲突，连同两侧内容；没有待裁决的合并时为空信封。 */
    @GetMapping("/api/sessions/{sessionId}/conflicts")
    public ConflictsView conflicts(Principal principal, @PathVariable String sessionId) {
        Session session = driverSession(principal, sessionId);
        return merges.conflicts(session, requireProject(session));
    }

    /**
     * 裁决一个文件的冲突。最后一个裁决完后合并自动完成。
     *
     * <p>参数二选一：给 {@code side} 整份取一侧（{@code main} 或 {@code session}），
     * 或给 {@code content} 直接给出结果。两侧不叫 {@code ours}/{@code theirs}，因为后者
     * 随合并方向反转（见 {@link MergeDirection}）。{@code content} 是最常见正确答案的
     * 表达方式：两个人各加一个方法时，两份都留才对，取一侧必然丢掉另一个。
     *
     * <p>需要驱动权：裁决的是这条会话的产出怎么进主干。
     */
    @PostMapping("/api/sessions/{sessionId}/conflicts/resolve")
    public MergeOutcome resolve(Principal principal,
                                @PathVariable String sessionId,
                                @RequestBody ResolveRequest request) {
        Session session = driverSession(principal, sessionId);
        return merges.resolve(session, requireProject(session), request.path(), request.toResolution());
    }

    /** 放弃当前未完成的合并，工作区恢复到合并前。 */
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
     * @param toCommitSha 合到哪个点。省略表示合到会话分支的最新；给了就必须是这条会话
     *                    打过的一个 checkpoint（sha 来自事件流里的 {@code CHECKPOINT_CREATED}）
     */
    public record MergeRequest(String toCommitSha) {
    }

    /**
     * @param path    冲突文件的项目路径
     * @param side    {@code "session"} 取会话工作区那侧，{@code "main"} 取主干那侧；
     *                不是 {@code ours}/{@code theirs}（那两个词随合并方向反转，
     *                见 {@link MergeDirection}）
     * @param content 人给出的裁决后全文；给了它就忽略 {@code side}
     */
    public record ResolveRequest(String path, String side, String content) {

        ConflictResolution toResolution() {
            // content 优先：它已经是最明确的表达
            if (content != null) {
                return new ConflictResolution.WithContent(content);
            }
            if ("session".equalsIgnoreCase(side)) {
                return new ConflictResolution.KeepSide(ConflictResolution.KeepSide.Side.SESSION);
            }
            if ("main".equalsIgnoreCase(side)) {
                return new ConflictResolution.KeepSide(ConflictResolution.KeepSide.Side.MAIN);
            }
            // 两个都没给（或 side 写错）时直接报错，不默认取一侧：
            // 静默替人做这个决定会让另一个人的代码无声消失
            throw new IllegalArgumentException(
                    "要给出 side（session 或 main），或者直接给出裁决后的 content");
        }
    }
}
