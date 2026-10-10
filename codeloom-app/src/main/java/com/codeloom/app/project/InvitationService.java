package com.codeloom.app.project;

import com.codeloom.domain.port.ProjectInvitationRepository;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectInvitation;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import com.codeloom.app.workspace.WorkspaceProvisioner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 邀请链接：生成、预览、接受、撤销。
 *
 * <h2>为什么"生成"和"接受"是两件事，而"接受"才是加入</h2>
 * 因为链接是**发出去的**。生成的时候对方还不在场，所以生成只写一行凭据；
 * 真正加入发生在**对方点开链接**那一刻，那一刻才知道"是谁"。
 * 于是"项目满员了"这个判断只能放在接受这一侧 —— 生成的时候项目可能只有一个成员，
 * 而期间可能已经有人被拉进来了。
 *
 * <h2>这里的时间不注入</h2>
 * 整个类只用 {@code Instant.now()}。过期、撤销这些边界**不靠往 service 里塞时间来测** ——
 * 测试直接往仓储里存一条 {@code expiresAt} 在过去的邀请就行，那更接近真实数据的样子，
 * 而且不用为了可测性给每个方法多一个参数。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InvitationService {

    private final ProjectInvitationRepository invitations;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final WorkspaceProvisioner provisioner;

    public InvitationService(ProjectInvitationRepository invitations,
                             ProjectRepository projects,
                             UserRepository users,
                             WorkspaceProvisioner provisioner) {
        this.invitations = invitations;
        this.projects = projects;
        this.users = users;
        this.provisioner = provisioner;
    }

    // ------------------------------------------------------------------

    /**
     * 生成一张邀请，**同时把之前还活着的那张作废** —— 一个项目同时只有一张有效链接。
     *
     * <h2>为什么要这条规矩</h2>
     * 凭据清单的全部价值在于"一眼看完"：发出去几张、都还在谁手里。两张并存时，
     * "我该把哪张发给对方"没有答案，而撤销其中一张对另一张毫无影响 ——
     * 于是清单越长越没人看，而看不见的凭据是最危险的那种（见 {@code pending}）。
     *
     * <p>代价说清楚：**这会让别人手里那张当场失效**。所以界面上写了这句话
     *（「再生成一张会让上一张失效」），而不是让它悄悄发生。
     *
     * <h2>先加锁</h2>
     * "作废旧的那张"和"插入新的那张"要在同一个事务里，而且两个成员同时点
     * 「生成链接」时不能各自插一张活下来 —— 那正好破掉这条规矩。
     * 用的是和接受邀请、合并同一把锁（见 {@link ProjectRepository#lock}）。
     */
    @Transactional
    public ProjectInvitation issue(Project project, UserId by) {
        Instant now = Instant.now();
        projects.lock(project.id());
        for (ProjectInvitation existing : invitations.findByProject(project.id())) {
            if (existing.isUsableAt(now)) {
                invitations.save(existing.revokedAt(now));
            }
        }
        ProjectInvitation invitation = ProjectInvitation.issue(project.id(), by, now);
        invitations.save(invitation);
        return invitation;
    }

    /**
     * 这个项目**现在还能用**的邀请 —— 界面上那张凭据清单就是它。
     *
     * <h2>作废的不在这个清单里</h2>
     * 用过、撤过、过了期的，都**不是凭据了**：既不能操作，也没有信息量
     * （谁还能用、什么时候失效，都已经不重要）。把它们一行行列出来，清单就会
     * 一直长长，而真正要看的那一张被淹在里面。
     *
     * <p>拿着一张死链接的人不会因此摸不着头脑：他那边的 {@code preview} 会当场
     * 告诉他为什么（已撤销 / 已用过 / 已过期）——「我发过一张」这件事对证的是**他**，
     * 不是发起人。
     */
    public List<ProjectInvitation> pending(Project project) {
        Instant now = Instant.now();
        return invitations.findByProject(project.id()).stream()
                .filter(invitation -> invitation.isUsableAt(now))
                .toList();
    }

    /** 撤销一张还没被接受的邀请。 */
    @Transactional
    public void revoke(Project project, String token) {
        ProjectInvitation invitation = invitations.findByToken(token)
                // **不区分"不存在"和"不是这个项目的"**：发这条请求的人已经是这个项目的成员，
                // 他能看到的凭据清单里本来就没有别的项目的东西。分开报错只会多一个能试的接口
                .filter(found -> found.projectId().equals(project.id()))
                .orElseThrow(notFound());
        if (invitation.revokedAt() != null) {
            // 撤销是幂等的：再点一次不该报错，那本来就是这个动作该有的样子
            return;
        }
        invitations.save(invitation.revokedAt(Instant.now()));
    }

    // ------------------------------------------------------------------
    // 接受
    // ------------------------------------------------------------------

    /**
     * 拿着一张链接加入。
     *
     * <h2>为什么整个方法在一个事务里，而且先给项目行加锁</h2>
     * 两个人在同一瞬间点开两张不同的链接时，不加锁的话两边都会读到"现在一个成员"，
     * 然后都加进去 —— 而项目上限是 2。锁定项目行之后，第二个事务会等第一个提交，
     * 拿到的就是"已经两个了"，于是被干净地拒掉。
     *
     * <p>用的是 {@link ProjectRepository#lock} —— 和合并走的是同一把锁，
     * 所以"加成员"和"合并"这两件事也不会互相插队。
     *
     * <h2>已经是成员时怎么办</h2>
     * **放行，但一个字都不写**（不记"接受者"、不作废那张邀请）。
     *
     * <p>写的话错在**两件事**上：
     *
     * <ul>
     *   <li>发起人自己点一下自己的链接，那张**本来要发给对方**的凭据就死了 ——
     *       而清单里作废的不显示，于是人会以为"对方已经加入了"。</li>
     *   <li>那张事件上写着"谁接受了这张邀请"，而真相是"发起人自己点了一下"。
     *       审计从此指着一个没发生过的事。</li>
     * </ul>
     *
     * <p>判据和下面那条"满员被拒"完全一样：**状态不允许，就不该烧凭据**。
     * 而"我已经在里面了"更不是错误 —— 把人送进项目就完了。
     */
    @Transactional
    public Project accept(String token, User invitee) {
        Instant now = Instant.now();
        ProjectInvitation invitation = invitations.findByToken(token).orElseThrow(notFound());
        // 判据和理由是**同一个**（`unusableReasonAt`），不存在"判了却说不清为什么"这种缝。
        // 三种作废原因分开说：合成一句"这张邀请已经不能用了"的话，人的第一反应是"为什么"，
        // 而那个答案本来就在手里
        Optional<String> unusable = invitation.unusableReasonAt(now);
        if (unusable.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, unusable.get());
        }

        // 先锁再读：锁的是"这个项目现在有几个成员"这件事，而它必须和下面的写入在同一个事务里
        projects.lock(invitation.projectId());
        Project project = projects.findById(invitation.projectId()).orElseThrow(() ->
                new IllegalStateException("邀请指向的项目不存在：" + invitation.projectId()));

        if (project.hasMember(invitee.id())) {
            provisioner.ensure(invitee.id(), project);
            return project;
        }

        if (project.members().size() >= Project.MAX_MEMBERS) {
            // 满了就停下来，**不消耗**这张邀请：先把人移出去（或者另开一个项目）之后，
            // 同一张链接还能用。作废掉它等于因为一次可修复的拥堵而毁掉一张凭据
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "这个项目已经有 " + Project.MAX_MEMBERS + " 名成员了");
        }

        Project updated = project.withMemberAdded(invitee.id());
        projects.save(updated);
        invitations.save(invitation.acceptedBy(invitee.id(), now));

        // 加入者的树也在这里建好 —— 见 WorkspaceProvisioner。
        //
        // 它跑在事务里，于是项目行的排它锁会多持有一次 `git worktree add`
        //（几十到几百毫秒）。这是知道的取舍：那把锁挡的只是**这一个项目**上的合并和
        // 改成员，而代价换来的是"他点进去就有自己的树" —— 否则新成员第一次打开项目
        // 会看到左栏报错。（这和"合并期间跑构建"不是一回事：那是一分钟量级，
        // 这才是几十毫秒。）
        provisioner.ensure(invitee.id(), updated);
        return updated;
    }

    // ------------------------------------------------------------------
    // 预览
    // ------------------------------------------------------------------

    /**
     * 点开链接时先看到的东西：谁邀请你加入哪个项目。
     *
     * <h2>为什么允许**未登录**看</h2>
     * 因为不这样的话，一个还没注册的人点开链接只会看到"请先登录" ——
     * 他不知道自己在被邀请去哪，也就无从判断该不该费劲注册。先让他看见
     * "某某邀请你加入某某项目"，再让他决定，才是这件事正确的顺序。
     *
     * <p>安全上不吃亏：那张 token 本身就能换到成员身份，它泄露出去的后果
     * **严格大于**泄露一个项目名和邀请人的用户名。也就是说这个端点开不开，
     * 攻击面都由"token 有没有泄露"决定，不由它决定。
     *
     * <h2>为什么还多一句"你是谁"</h2>
     * 登录是可选的（见上），但**登录着的人要被告知他自己的处境**：
     * 他是不是已经在这个项目里、这张链接是不是他自己发出去的。
     *
     * <p>不这样的话，发起人点开自己的链接会看到"加入项目"——他按下去才知道自己本来就在里面
     *（见 {@link #accept}）。这里先说出来，那一次假动作就根本不会发生。
     *
     * <p>它只说"**你自己**在不在里面"，不泄露任何别人的信息 —— 一个匿名访客拿到的
     * 永远是 {@code GUEST}，看得到的还是原来那些。
     *
     * @param viewerId 问的人是谁；未登录时为空
     */
    public InvitationPreview preview(String token, UserId viewerId) {
        Instant now = Instant.now();
        ProjectInvitation invitation = invitations.findByToken(token).orElseThrow(notFound());
        // 项目已经被删掉时不给"不存在"以外的信息：链接有效但项目没了，
        // 对拿着链接的人来说和"这张链接没用"是一回事
        Project project = projects.findById(invitation.projectId()).orElseThrow(notFound());
        User inviter = users.findById(invitation.createdBy()).orElse(null);

        // 能不能用、以及为什么不能用，出自**同一处**（见 `unusableReasonAt`）——
        // 分开算的话，预览页会出现"还能用、底下却写着一句作废理由"这种自相矛盾
        Optional<String> unusable = invitation.unusableReasonAt(now);

        return new InvitationPreview(
                invitation.projectId().value(),
                project.name(),
                // 邀请人被删掉（或者数据对不上）时不炸：这张链接还能用，
                // 只是"谁邀请的"那一栏空了。为了一行显示把人挡在门外不值得
                inviter == null ? null : inviter.displayName(),
                invitation.expiresAt(),
                unusable.isEmpty(),
                unusable.orElse(null),
                viewerOf(invitation, project, viewerId));
    }

    /** 拿着这张链接的人，相对于它是谁。 */
    private static Viewer viewerOf(ProjectInvitation invitation, Project project, UserId viewerId) {
        if (viewerId == null) {
            return Viewer.GUEST;
        }
        // 发链接的人**必然**是成员（只有成员能发），所以先问"是不是他发的" ——
        // 两种身份都成立时，对他更有用的那句话是"这是你自己发的"
        if (invitation.createdBy().equals(viewerId)) {
            return Viewer.INVITER;
        }
        return project.hasMember(viewerId) ? Viewer.MEMBER : Viewer.GUEST;
    }

    // ------------------------------------------------------------------


    /**
     * 404 而不是 403：拿一批 token 挨个试的时候，分不出"不存在"和"不是你的"，
     * 就枚举不出东西来（和 {@code ProjectAccess} 里那条规则一致）。
     */
    private static Supplier<ResponseStatusException> notFound() {
        return () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这个邀请链接不存在");
    }

    /**
     * 拿着这张链接的人，相对于它是谁。
     *
     * <p>{@code INVITER} 蕴含 {@code MEMBER}（发链接的人必然在项目里）——
     * 所以要问"他是不是成员"之前先问"是不是他发的"。
     */
    public enum Viewer {
        /** 还没登录，或者登录着但不在这个项目里 —— 这张链接**能让他进来** */
        GUEST,
        /** 已经在这个项目里（链接是别人发的）—— 他能做的只是打开项目 */
        MEMBER,
        /** 这张链接就是他自己发出去的 */
        INVITER
    }

    /**
     * 预览。
     *
     * @param usable 现在能不能用；false 时 {@code reason} 一定非空
     * @param viewer 拿着这张链接的人是谁（见 {@link Viewer}）。界面靠它决定给什么动作
     */
    public record InvitationPreview(String projectId,
                                    String projectName,
                                    String invitedBy,
                                    Instant expiresAt,
                                    boolean usable,
                                    String reason,
                                    Viewer viewer) {
    }
}
