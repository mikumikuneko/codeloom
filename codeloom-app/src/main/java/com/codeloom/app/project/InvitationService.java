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
 * <p>生成只写一行凭据，真正加入发生在对方点开链接那一刻。因此"项目满员"只能在接受侧
 * 判断：生成时项目可能只有一个成员，期间可能又有人被拉进来。
 *
 * <p>时间不注入，整个类只用 {@code Instant.now()}。过期与撤销的边界由测试直接往仓储里
 * 存一条过去的 {@code expiresAt} 来构造，既接近真实数据，也不必给每个方法多加参数。
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
     * 生成一张邀请，同时作废之前仍然有效的那张：一个项目同时只有一张有效链接。
     *
     * <p>否则凭据清单里两张并存，"该把哪张发给对方"没有答案，撤销一张也不影响另一张，
     * 清单越长越没人看。代价是别人手里那张会当场失效，界面必须写明这一点。
     *
     * <p>作废旧的那张与插入新的那张在同一个事务里，并持有项目行锁，防止两个成员同时
     * 生成时各自插一张。这把锁与接受邀请、合并共用（见 {@link ProjectRepository#lock}）。
     *
     * @param project 项目
     * @param by      生成者，必须是成员
     * @return 新邀请
     */
    @Transactional
    public ProjectInvitation issue(Project project, UserId by) {
        projects.lock(project.id());
        // 时间在拿到锁之后再取：等锁期间可能跨过一次合并，用旧时刻算出的过期时间会偏短
        Instant now = Instant.now();
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
     * 这个项目现在还能用的邀请，即界面上的凭据清单。
     *
     * <p>用过、撤销、过期的都不在其中：它们不能再操作，也没有信息量。拿着死链接的人
     * 会在 {@link #preview} 里看到具体原因。
     *
     * @param project 项目
     * @return 当前有效的邀请；可能为空
     */
    public List<ProjectInvitation> pending(Project project) {
        Instant now = Instant.now();
        return invitations.findByProject(project.id()).stream()
                .filter(invitation -> invitation.isUsableAt(now))
                .toList();
    }

    /**
     * 撤销一张还没被接受的邀请；已撤销时幂等返回。
     *
     * <p>第一次 {@code findByToken} 只用来拿项目 id，它读到的是事务快照，不能用于判断
     * 状态。拿到项目锁后用 {@link ProjectInvitationRepository#findByTokenForUpdate}
     * 读最新版本，否则会拿旧状态覆盖 {@link #issue} 刚作废、或 {@link #accept}
     * 刚兑现的那张。
     *
     * @param project 项目
     * @param token   邀请 token；不存在或不属于该项目时 404
     * @throws ResponseStatusException 404 找不到邀请；409 邀请已被接受
     */
    @Transactional
    public void revoke(Project project, String token) {
        ProjectInvitation located = invitations.findByToken(token)
                // 不区分"不存在"和"不是这个项目的"：调用者已是成员，凭据清单里本来就没有
                // 别的项目的东西，分开报错只会多出一个能试的接口
                .filter(found -> found.projectId().equals(project.id()))
                .orElseThrow(notFound());

        projects.lock(located.projectId());
        ProjectInvitation invitation = invitations.findByTokenForUpdate(token)
                .filter(found -> found.projectId().equals(project.id()))
                .orElseThrow(notFound());

        if (invitation.revokedAt() != null) {
            // 撤销是幂等的，再点一次不报错
            return;
        }
        if (invitation.acceptedBy() != null) {
            // 与领域方法一致：接受之后撤销，那个人已经是成员了
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "这张邀请已经被接受了，撤销它没有意义");
        }
        invitations.save(invitation.revokedAt(Instant.now()));
    }

    // ------------------------------------------------------------------
    // 接受
    // ------------------------------------------------------------------

    /**
     * 拿着一张链接加入项目。
     *
     * <p>整个方法在一个事务里并先锁项目行：两个人同时点开两张链接时，不加锁会都读到
     * 一个成员然后都加入，而项目上限是 2。锁与合并共用（{@link ProjectRepository#lock}），
     * 加成员与合并也不会互相插队。
     *
     * <p>已经是成员时放行，但不记接受者、不作废邀请，也不改成员行；仍会幂等地 ensure
     * 他的工作区（可能建树或补写 workspace 行）。否则发起人点一下自己的链接就会让本来要
     * 发给对方的凭据失效，并在事件里留下一次没发生的审计。判据与满员被拒相同：状态不允许，
     * 就不该烧凭据；而"我已经在里面"不是错误。
     *
     * @param token   邀请 token
     * @param invitee 接受者
     * @return 加入后的项目（已是成员时返回当前项目）
     * @throws ResponseStatusException 404 邀请或项目不存在；409 邀请不可用或项目已满员
     */
    @Transactional
    public Project accept(String token, User invitee) {
        // 第一次查只用来拿项目 id；它读到的是事务快照，状态要在拿到项目锁后用锁定读再取
        ProjectInvitation located = invitations.findByToken(token).orElseThrow(notFound());

        // 先锁项目，再锁定读邀请和成员：普通 findById 用的是第一次查询的快照，
        // 锁到手后读到的仍是旧成员，保存时会把别人刚加进来的人删掉
        Project project = projects.lockAndFind(located.projectId()).orElseThrow(() ->
                new IllegalStateException("邀请指向的项目不存在：" + located.projectId()));
        ProjectInvitation invitation = invitations.findByTokenForUpdate(token).orElseThrow(notFound());

        Instant now = Instant.now();
        // 判据与理由出自同一处（unusableReasonAt）；三种作废原因分开说，
        // 合成一句"不能用了"会让人立刻追问为什么，而答案就在手里
        Optional<String> unusable = invitation.unusableReasonAt(now);
        if (unusable.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, unusable.get());
        }

        if (project.hasMember(invitee.id())) {
            provisioner.ensure(invitee.id(), project);
            return project;
        }

        if (project.members().size() >= Project.MAX_MEMBERS) {
            // 满员不消耗邀请：把人移走或另开项目后，同一张链接还能用
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "这个项目已经有 " + Project.MAX_MEMBERS + " 名成员了");
        }

        Project updated = project.withMemberAdded(invitee.id());
        projects.save(updated);
        invitations.save(invitation.acceptedBy(invitee.id(), now));

        // 加入者的树在这里建好。它在事务内，项目行锁会多持有一次 worktree add（几十到
        // 几百毫秒）；这把锁只挡这个项目的合并和改成员，换来新成员点进去就有自己的树。
        // 与"合并期间跑构建"不同，后者是一分钟量级
        provisioner.ensure(invitee.id(), updated);
        return updated;
    }

    // ------------------------------------------------------------------
    // 预览
    // ------------------------------------------------------------------

    /**
     * 点开链接时先看到的预览：谁邀请你加入哪个项目。允许未登录访问。
     *
     * <p>未注册的人也需要先知道自己在被邀请去哪，再决定是否注册。安全上不吃亏：
     * token 本身能换到成员身份，它泄露的后果严格大于泄露项目名和邀请人用户名，
     * 因此攻击面由 token 是否泄露决定，而不由这个端点决定。
     *
     * <p>登录着的人还会拿到自己的处境（未加入 / 已在项目里 / 就是自己发的），避免发起人
     * 点开自己的链接后先做一次假的"加入"动作。它只描述查看者自己，匿名访客恒为
     * {@code GUEST}。
     *
     * @param token    邀请 token
     * @param viewerId 查看者；未登录时为空
     * @return 预览；邀请不可用时 {@code usable} 为 false 且 {@code reason} 非空
     * @throws ResponseStatusException 404 邀请或项目不存在
     */
    public InvitationPreview preview(String token, UserId viewerId) {
        Instant now = Instant.now();
        ProjectInvitation invitation = invitations.findByToken(token).orElseThrow(notFound());
        // 项目已删除时只报"不存在"：链接有效但项目没了，与"链接没用"对持链接者是同一件事
        Project project = projects.findById(invitation.projectId()).orElseThrow(notFound());
        User inviter = users.findById(invitation.createdBy()).orElse(null);

        // 可用性与原因出自同一处；分开算会出现"还能用、却附着一句作废理由"的矛盾
        Optional<String> unusable = invitation.unusableReasonAt(now);

        return new InvitationPreview(
                invitation.projectId().value(),
                project.name(),
                // 邀请人被删或数据对不上时不报错：链接仍可用，只是"谁邀请的"为空
                inviter == null ? null : inviter.displayName(),
                invitation.expiresAt(),
                unusable.isEmpty(),
                unusable.orElse(null),
                viewerOf(invitation, project, viewerId));
    }

    /** 拿着这张链接的人相对于它是什么身份。 */
    private static Viewer viewerOf(ProjectInvitation invitation, Project project, UserId viewerId) {
        if (viewerId == null) {
            return Viewer.GUEST;
        }
        // 发链接的人必然是成员，所以先问"是不是他发的"：两种身份都成立时，
        // 对他更有用的是"这是你自己发的"
        if (invitation.createdBy().equals(viewerId)) {
            return Viewer.INVITER;
        }
        return project.hasMember(viewerId) ? Viewer.MEMBER : Viewer.GUEST;
    }

    // ------------------------------------------------------------------


    /**
     * 404 而不是 403：否则可以拿一批 token 挨个试，从状态码分不出"不存在"和"不是你的"。
     * 与 {@code ProjectAccess} 的规则一致。
     *
     * @return 构造 404 的工厂
     */
    private static Supplier<ResponseStatusException> notFound() {
        return () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这个邀请链接不存在");
    }

    /**
     * 拿着链接的人相对于它的身份。
     *
     * <p>{@code INVITER} 蕴含 {@code MEMBER}（发链接的人必然在项目里），所以先判断
     * "是不是他发的"。
     */
    public enum Viewer {
        /** 还没登录，或登录着但不在项目里；这张链接能让他进来 */
        GUEST,
        /** 已经在项目里（链接是别人发的），他能做的只是打开项目 */
        MEMBER,
        /** 这张链接就是他自己发出去的 */
        INVITER
    }

    /**
     * 邀请预览。
     *
     * @param projectId   项目 id
     * @param projectName 项目名
     * @param invitedBy   邀请人的用户名；邀请人不存在时为 null
     * @param expiresAt   失效时间
     * @param usable      现在能不能用；false 时 {@code reason} 一定非空
     * @param reason      不能用的原因；可用时为 null
     * @param viewer      查看者相对这张链接的身份，界面据此决定给什么动作
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
