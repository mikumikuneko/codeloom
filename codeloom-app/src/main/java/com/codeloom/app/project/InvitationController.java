package com.codeloom.app.project;

import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.project.ProjectInvitation;
import com.codeloom.domain.user.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

/**
 * 邀请链接接口。
 *
 * <p>两组端点权限不同：{@code /api/projects/{id}/invitations} 的生成、列出、撤销需要
 * 项目成员，因为它们决定谁能进来；{@code /api/invitations/{token}} 的预览和接受
 * 只要拿着链接即可，预览连登录都不要求（见 {@code InvitationService.preview}）。
 *
 * <p>返回 token 而不是完整链接：服务端不知道前端对外是什么地址（反向代理、换域名、
 * 本地开发），猜错就会发出指向错误地址的链接。客户端拿 token 拼 {@code /invite/TOKEN}。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InvitationController {

    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final InvitationService invitations;
    private final ProjectService projects;

    public InvitationController(CurrentUser currentUser,
                                ProjectAccess access,
                                InvitationService invitations,
                                ProjectService projects) {
        this.currentUser = currentUser;
        this.access = access;
        this.invitations = invitations;
        this.projects = projects;
    }

    // ------------------------------------------------------------------
    // 项目这一侧：生成 / 列出 / 撤销（要成员）
    // ------------------------------------------------------------------

    /**
     * 生成一张邀请；已有的有效邀请会同时作废。
     *
     * @return 新邀请的 token 与时间
     */
    @PostMapping("/api/projects/{projectId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationView issue(Principal principal, @PathVariable String projectId) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return InvitationView.of(invitations.issue(project, me.id()));
    }

    /**
     * 这个项目现在还能用的邀请，即界面上的凭据清单；作废的不在其中。
     * 理由见 {@link InvitationService#pending}。
     *
     * @return 有效邀请
     */
    @GetMapping("/api/projects/{projectId}/invitations")
    public List<InvitationView> pending(Principal principal, @PathVariable String projectId) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return invitations.pending(project).stream().map(InvitationView::of).toList();
    }

    /**
     * 撤销一张邀请；已撤销时幂等返回 204。
     *
     * <p>token 放在路径里：它只使用 base64url 字符集，没有会被误认为截断的字符。
     */
    @DeleteMapping("/api/projects/{projectId}/invitations/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Principal principal,
                       @PathVariable String projectId,
                       @PathVariable String token) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        invitations.revoke(project, token);
    }

    // ------------------------------------------------------------------
    // 拿着链接这一侧：预览 / 接受
    // ------------------------------------------------------------------

    /**
     * 点开链接先看到的预览：谁邀请你加入哪个项目。
     *
     * <p>登录可选，因此这里拿 {@code Principal} 但不 require —— 未登录时为 null，
     * 即匿名访客。登录者额外拿到自己相对链接的身份，界面据此给「加入项目」或「打开项目」。
     *
     * @return 邀请预览；不可用时带原因
     */
    @GetMapping("/api/invitations/{token}")
    public InvitationService.InvitationPreview preview(Principal principal,
                                                       @PathVariable String token) {
        User me = currentUser.find(principal).orElse(null);
        return invitations.preview(token, me == null ? null : me.id());
    }

    /** 真正加入；这一步要求登录，成员身份必须挂在某个人身上。 */
    @PostMapping("/api/invitations/{token}/accept")
    public ProjectView accept(Principal principal, @PathVariable String token) {
        User me = currentUser.require(principal);
        return projects.view(invitations.accept(token, me));
    }

    // ------------------------------------------------------------------

    /**
     * 一张有效邀请。
     *
     * <p>视图里出现的都是此刻还能用的，因此没有"是否可用"和"原因"字段；那些信息在
     * {@code /api/invitations/{token}} 返回的 {@code InvitationPreview} 里。
     *
     * @param token     客户端拿它拼 {@code /invite/TOKEN}；服务端不拼完整链接（见类注释）
     * @param createdAt 生成时间
     * @param expiresAt 失效时间
     */
    public record InvitationView(String token, Instant createdAt, Instant expiresAt) {

        static InvitationView of(ProjectInvitation invitation) {
            return new InvitationView(invitation.token(), invitation.createdAt(),
                    invitation.expiresAt());
        }
    }
}
