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
 * 邀请链接。
 *
 * <h2>两组端点，权限完全不同</h2>
 * <ul>
 *   <li>{@code /api/projects/{id}/invitations} —— 生成、列出、撤销。**要项目成员**，
 *       因为这三件事都是在动"谁能进来"。
 *   <li>{@code /api/invitations/{token}} —— 预览和接受。**拿着链接的人就能做**，
 *       而预览那条连登录都不要（见 {@code InvitationService.preview} 的注释）。
 * </ul>
 *
 * <h2>返回的是 token 而不是完整链接</h2>
 * 完整链接要拼上前端自己的地址，而**服务端不知道它在外面叫什么**（反向代理、
 * 换域名、本地开发是 localhost:5173……猜错就是把一张指向错误地址的链接发出去）。
 * 客户端拿 token 拼 {@code /invite/<token>} 是它本来就知道的事。
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

    @PostMapping("/api/projects/{projectId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationView issue(Principal principal, @PathVariable String projectId) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return InvitationView.of(invitations.issue(project, me.id()));
    }

    /**
     * 这个项目**现在还能用**的邀请 —— 界面上那张凭据清单就是它。
     *
     * <p>作废的（用过、撤过、过期）不在里面：它们不是凭据了。理由见
     * {@code InvitationService.pending}。
     */
    @GetMapping("/api/projects/{projectId}/invitations")
    public List<InvitationView> pending(Principal principal, @PathVariable String projectId) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return invitations.pending(project).stream().map(InvitationView::of).toList();
    }

    /**
     * 撤销一张。
     *
     * <p>token 放在路径里而不是查询参数：它只用 base64url 的字符集，没有
     * {@code .} 那种会让人担心被截断的字符（那是 host 走查询参数的原因，见
     * {@code ApiKeyController.remove}）。
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
     * 点开链接先看到的东西：谁邀请你加入哪个项目。
     *
     * <p><strong>登录是可选的</strong>：还没注册的人点开链接也该看得见自己在被邀请去哪，
     * 否则他无从判断该不该费劲注册（理由写在 {@code InvitationService.preview} 的注释里）。
     * 所以这里拿 {@code Principal} 但**不 require** —— 没人登录时它是 null，
     * 那正是"匿名访客"这一态。
     *
     * <p>登录着的人额外拿到一句"他自己处在哪一态"（还没加入 / 已经在里面 / 就是他发的），
     * 界面靠它决定给「加入项目」还是「打开项目」。
     */
    @GetMapping("/api/invitations/{token}")
    public InvitationService.InvitationPreview preview(Principal principal,
                                                       @PathVariable String token) {
        User me = currentUser.find(principal).orElse(null);
        return invitations.preview(token, me == null ? null : me.id());
    }

    /** 真正加入。**这一步要登录** —— 成员身份得挂在某个人身上。 */
    @PostMapping("/api/invitations/{token}/accept")
    public ProjectView accept(Principal principal, @PathVariable String token) {
        User me = currentUser.require(principal);
        return projects.view(invitations.accept(token, me));
    }

    // ------------------------------------------------------------------

    /**
     * 一张邀请长什么样。
     *
     * <p>只有这三个字段：**这个视图里出现的，都是此刻还能用的**（作废的不进这个视图 ——
     * 见 {@code InvitationService.pending} 和 {@code issue}）。所以没有"能不能用"和
     * "为什么不能用"这两栏 —— 它们要么恒为真、要么恒为空，那种字段只会让人以为还有别的状态。
     *
     * <p>不能用时的原因在**另一条路**上：拿着链接的人打开 {@code /api/invitations/{token}}
     * 会拿到 {@code InvitationPreview}，那里有 {@code usable} 和 {@code reason}。
     *
     * @param token 客户端拿它拼 {@code /invite/<token>}。**服务端不拼完整链接**，见类注释
     */
    public record InvitationView(String token, Instant createdAt, Instant expiresAt) {

        static InvitationView of(ProjectInvitation invitation) {
            return new InvitationView(invitation.token(), invitation.createdAt(),
                    invitation.expiresAt());
        }
    }
}
