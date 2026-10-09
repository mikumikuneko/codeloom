package com.codeloom.app.project;

import com.codeloom.domain.port.CommitIdentity;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import com.codeloom.workspace.DataPaths;
import com.codeloom.app.workspace.WorkspaceProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目的用例：建、查、列、退。
 *
 * <h2>「退出」和「创建」在磁盘上方向相反，但规则是同一条</h2>
 * 两者在存储上都是两件事：磁盘上多（或少）一个 git 仓库，库里多（或少）一批行。
 * 两件事没法在一个事务里，所以两边各挑一个顺序：
 * <ul>
 *   <li><b>建：先落盘、后写库</b> —— 先写库的话，git 初始化失败会留下一行指向
 *       不存在仓库的项目，而那种行会在每一个操作里以"仓库不存在"的面目出现。</li>
 *   <li><b>退：先写库、后删盘</b> —— 先删盘的话，一次库侧失败就留下"行还在、
 *       仓库没了"的项目；先写库则最坏是地上多一个没人引用的目录。</li>
 * </ul>
 *
 * <p>看起来是两条相反的规则，其实是同一条：<strong>库是权威，磁盘上的残留是可以
 * 容忍的垃圾</strong>。
 *
 * <h2>没有"删除项目"这个动作</h2>
 * 项目是人走空的副产品：成员各自退出、带走自己的东西，最后一个人退出时它才真的消失。
 * 所以两个人里任何一个都只能决定**自己**走不走，没有"我把对方脚下的地抽走"这条路。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    /** 翻页找"每棵树的一条代表"时一次拉多少条。见 {@link #oneSessionPerTree}。 */
    private static final int SESSION_PAGE = 50;

    /**
     * 新项目自带的那个顶层目录的名字。
     *
     * <h2>为什么新项目不能是一个空仓库</h2>
     * 空仓库在界面上就是<strong>左栏什么都没有</strong>。而"什么都没有"和"读不出来"
     * 长得一模一样（{@code FileTree} 里那句"空目录要显示出来而不是藏掉"说的就是这件事），
     * 于是刚建完项目的人第一眼看到的是一块可疑的空白。
     *
     * <p>名字本身与"为什么不用项目名"的理由，见 {@link ProjectLayout#DIRECTORY}。
     */
    private static final String FIRST_DIRECTORY = ProjectLayout.DIRECTORY;

    private final ProjectRepository projects;
    private final UserRepository users;
    private final WorkspaceManager workspaces;
    private final SessionRepository sessions;
    private final ExecutionLease leases;
    private final ProjectDeparture departure;
    private final Path reposRoot;
    private final WorkspaceProvisioner provisioner;

    public ProjectService(ProjectRepository projects,
                         UserRepository users,
                         WorkspaceManager workspaces,
                         SessionRepository sessions,
                         ExecutionLease leases,
                         ProjectDeparture departure,
                         @Value("${codeloom.repos-root:}") String reposRoot,
                         WorkspaceProvisioner provisioner) {
        this.projects = projects;
        this.users = users;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.leases = leases;
        this.departure = departure;
        // 空 = 自动推导（项目根下的 codeloom-app/repos）。**不要在这里自己拼相对路径** ——
        // 那样"数据放哪儿"会取决于从哪个目录启动。见 DataPaths
        this.reposRoot = DataPaths.locate(reposRoot, "repos");
        this.provisioner = provisioner;
    }

    /**
     * 建一个项目 = 建一个 git 仓库 + 记一行。创建者自动成为第一个成员，也是房主。
     *
     * <p>顺序是**先落盘、后写库**（理由与反过来的代价见类注释）。
     */
    public Project create(User creator, String name) {
        ProjectId id = ProjectId.generate();
        Path repoPath = reposRoot.resolve(id.value());

        // 署名用创建者：那个基点提交也是要留在历史里的，得看得出是谁开的这个项目。
        // 目录名走端口传进去，而不是在这里自己写文件 —— "怎么让一个空目录在 git 里活下来"
        // 是 git 那边的事，应用层不该知道（见 WorkspaceManager.initializeRepository）
        workspaces.initializeRepository(repoPath, CommitIdentity.of(creator.username()), FIRST_DIRECTORY);

        Project project = new Project(id, creator.id(), name, repoPath.toString(),
                java.util.Set.of(creator.id()));
        projects.save(project);
        // 创建者的树现在就建好。**不是为了"他马上要干活"，而是因为"他拿到了这个项目的访问权"** ——
        // 点进项目就是工作区，而工作区要读文件（见 WorkspaceProvisioner）
        provisioner.ensure(creator.id(), project);
        return project;
    }

    /**
     * 退出项目 —— 任何成员都能做，房主也一样。
     *
     * <h2>带走什么、不带走什么</h2>
     * <ul>
     *   <li><b>他名下的会话（连同事件流和幂等键）</b>：那些对话的主人不再是成员了，
     *       留着就是一批**没有入口能打开**的行 —— 对方进不去（不是他的），
     *       崩溃恢复却会把它们当成候选去续跑。</li>
     *   <li><b>他那棵树</b>：库里的行 + 磁盘上的目录。</li>
     *   <li><b>不带走</b>聊天记录和邀请：它们挂在项目上，项目还在就还归它。</li>
     * </ul>
     *
     * <h2>他是最后一个时，项目跟着消失</h2>
     * 那一次还要清掉项目行、聊天记录、邀请，以及磁盘上的仓库目录
     * （见 {@link ProjectDeparture#leave}）。
     *
     * <h2>为什么先确认他的会话没在跑</h2>
     * 和「丢弃会话」是同一条理由：正在跑的那一轮收尾时会 upsert 会话行、
     * 往事件流里写 —— 而我们马上要把那些行删掉。不管的话，结果是删完之后又被写回来一条。
     */
    public void leave(Project project, UserId leaver) {
        LeaseToken held = holdOwnTree(project.id(), leaver);
        boolean projectIsGone;
        try {
            projectIsGone = departure.leave(project, leaver);
        } finally {
            if (held != null) {
                leases.release(held);
            }
        }

        // 磁盘放在事务**之后**：库里已经定了，这一步失败最多是地上多个目录，
        // 而反过来（先删盘）一次库侧失败就会留下"行还在、仓库没了"的项目
        Path repoPath = Path.of(project.repoPath());
        eraseQuietly(() -> workspaces.removeWorkspace(WorkspaceId.of(leaver, project.id()), repoPath),
                "工作区", WorkspaceId.of(leaver, project.id()).toString());
        if (projectIsGone) {
            // 树先走、仓库后走：worktree 是主仓库的**兄弟目录**，删掉仓库带不走它们
            eraseQuietly(() -> workspaces.removeRepository(repoPath), "仓库", project.repoPath());
        }
    }

    /** 「我的项目」。**分页是仓储层的事** —— 见 {@code ProjectRepository.findByMember}。 */
    public List<ProjectView> listFor(UserId userId, int limit, int offset) {
        return projects.findByMember(userId, limit, offset).stream().map(this::view).toList();
    }

    public ProjectView view(Project project) {
        return new ProjectView(project.id().value(), project.name(), project.ownerId().value(),
                project.members().stream().map(this::member).toList(),
                // 项目目录的名字。界面左边那棵树的第一行是它 —— 先有项目，才有文件
                ProjectLayout.DIRECTORY);
    }

    // ------------------------------------------------------------------
    // 按住他自己那棵树 / 清磁盘
    // ------------------------------------------------------------------

    /**
     * 按住**退出者自己**那棵树的租约。他在那儿一个字都没说过时返回 null。
     *
     * <p>{@code null} 不是"拿不到"，而是"不需要拿"：一轮执行必须先有一条会话
     * （见 {@code TurnExecutor}），没有会话的树上不可能有人在跑。
     *
     * <p>退出的只有他一个人，所以这里只需要按住一棵树 ——
     * 对方那棵树上的事情与这次操作无关（他退他的，对方照常干活）。
     */
    private LeaseToken holdOwnTree(ProjectId projectId, UserId leaver) {
        Session representative = oneSessionPerTree(projectId).get(WorkspaceId.of(leaver, projectId));
        if (representative == null) {
            return null;
        }
        return leases.tryAcquire(representative).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "你的会话正在跑，等它跑完再退出"));
    }

    /**
     * 这个项目里每棵树挑一条会话当代表。
     *
     * <h2>为什么要翻页翻到底</h2>
     * 要的只是"每棵树一条"，看上去翻一页就够了 —— 但**一个人的会话可以多到把第一页占满**
     * （两人协作里，每人各写各的），于是另一个人的树一条代表都选不出来。
     * 而退出这件事恰恰只关心一棵树，选错树就等于那道"有没有在跑"的门形同虚设。
     *
     * <p>会话很多的仓库里这是几次查询，但只在退出时发生一次。
     */
    private Map<WorkspaceId, Session> oneSessionPerTree(ProjectId projectId) {
        Map<WorkspaceId, Session> perTree = new LinkedHashMap<>();
        for (int offset = 0; ; offset += SESSION_PAGE) {
            List<Session> page = sessions.findByProject(projectId, SESSION_PAGE, offset);
            page.forEach(session -> perTree.putIfAbsent(session.workspaceId(), session));
            if (page.size() < SESSION_PAGE) {
                return perTree;
            }
        }
    }

    /**
     * 磁盘这边失败**不往上抛**，只记一条日志。
     *
     * <p>因为走到这里时库里已经定了：这时候回一个错误，用户看到的是"退出失败"，
     * 而它事实上成了 —— 他会再点一次，然后发现项目已经不在自己的列表里了。
     * 地上多留一个目录是可以容忍的垃圾；一个和事实相反的回答不是。
     */
    private static void eraseQuietly(Runnable action, String what, String target) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("清理{}的磁盘残留失败，需要手工删：{}", what, target, e);
        }
    }

    private ProjectView.Member member(UserId userId) {
        // 成员最多两个（Project.MAX_MEMBERS），所以这里逐个查是可控的；
        // 成员数要是上去了，就该改成一次批量查
        return users.findById(userId).map(ProjectView.Member::of).orElseThrow(() ->
                new IllegalStateException("项目的成员在用户表里不存在：" + userId
                        + "（成员行和用户行对不上，写入路径有漏）"));
    }
}
