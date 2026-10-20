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
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import com.codeloom.workspace.DataPaths;
import com.codeloom.app.turn.PendingMessages;
import com.codeloom.app.workspace.WorkspaceProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目的用例：建、查、列、退出。
 *
 * <p>建和退出都涉及磁盘与库两步，无法放进同一个事务，所以顺序相反：建先落盘后写库，
 * 否则 git 初始化失败会留下指向不存在仓库的项目行；退出先写库后删盘，否则库侧失败会
 * 留下行还在、仓库没了的项目。两者遵循同一条规则：库是权威，磁盘残留是可容忍的垃圾。
 *
 * <p>没有"删除项目"这个动作：项目是人走空的副产品，成员各自退出、带走自己的东西，
 * 最后一个人退出时项目才消失。任何人都只能决定自己走不走。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    /** 翻页找"每棵树的一条代表"时一次拉多少条。见 {@link #oneSessionPerTree}。 */
    private static final int SESSION_PAGE = 50;

    /**
     * 新项目自带的顶层目录名。空仓库在界面上与"读不出来"无法区分，所以新项目必须有东西。
     * 名字与种子内容见 {@link ProjectLayout#DIRECTORY}。
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
    private final PendingMessages pending;

    public ProjectService(ProjectRepository projects,
                         UserRepository users,
                         WorkspaceManager workspaces,
                         SessionRepository sessions,
                         ExecutionLease leases,
                         ProjectDeparture departure,
                         @Value("${codeloom.repos-root:}") String reposRoot,
                         WorkspaceProvisioner provisioner,
                         PendingMessages pending) {
        this.projects = projects;
        this.users = users;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.leases = leases;
        this.departure = departure;
        // 空 = 自动推导（项目根下的 data/repos）。不要在这里自己拼相对路径：
        // 那样"数据放哪儿"会取决于从哪个目录启动。见 DataPaths
        this.reposRoot = DataPaths.locate(reposRoot, "repos");
        this.provisioner = provisioner;
        this.pending = pending;
    }

    /**
     * 建一个项目 = 建一个 git 仓库 + 记一行。创建者自动成为第一个成员，也是房主。
     *
     * <p>顺序是先落盘、后写库，理由与反过来的代价见类注释。
     *
     * @param creator 创建者，同时成为第一个成员和房主
     * @param name    项目名
     * @return 已保存的项目；创建者的工作区已经建好
     */
    public Project create(User creator, String name) {
        ProjectId id = ProjectId.generate();
        Path repoPath = reposRoot.resolve(id.value());

        // 署名用创建者：基点提交要留在历史里，看得出是谁开的项目。
        // 目录名走端口传入，应用层不负责"怎么让空目录在 git 里活下来"
        workspaces.initializeRepository(repoPath, CommitIdentity.of(creator.username()), FIRST_DIRECTORY);

        Project project = new Project(id, creator.id(), name, repoPath.toString(),
                java.util.Set.of(creator.id()));
        projects.save(project);
        // 创建者的树现在就建好：他拿到项目访问权的那一刻，工作区就要能读文件
        provisioner.ensure(creator.id(), project);
        return project;
    }

    /**
     * 退出项目：任何成员都能做，房主也一样。
     *
     * <p>带走他名下的会话（连同事件流和幂等键）与他的那棵树（库里的行和磁盘目录）。
     * 会话必须删：主人不再是成员，留着就没有入口能打开，而崩溃恢复启动时仍会扫描它们、
     * 给执行到一半的调用补写 {@code ToolInterrupted} —— 一条没人能打开的会话不该继续
     * 产生事实。
     * 聊天记录和邀请不跟着走，它们挂在项目上。最后一个人退出时，项目行、聊天记录、
     * 邀请和仓库目录一起清除（见 {@link ProjectDeparture#leave}）。
     *
     * <p>先确认他的会话没在跑：正在跑的那一轮收尾时会 upsert 会话行并写事件，
     * 不管的话会在删除之后又写回来一条，和丢弃会话是同一条理由。
     *
     * @param project 项目
     * @param leaver  退出的人，必须是成员
     * @throws ResponseStatusException 409，他的会话正在执行中
     */
    public void leave(Project project, UserId leaver) {
        LeaseToken held = holdOwnTree(project.id(), leaver);
        // 会话行会在 departure 里被成批删掉，之后就定位不到；排队留言的 id 必须在删之前收集，
        // 在事务成功之后才丢。事务失败时留言还在，用户再点一次即可
        List<SessionId> leaving = sessionIdsOf(project.id(), leaver);
        boolean projectIsGone;
        try {
            projectIsGone = departure.leave(project, leaver);
        } finally {
            if (held != null) {
                leases.release(held);
            }
        }
        leaving.forEach(pending::forget);

        // 磁盘操作放在事务之后：此时库已定，失败最多留下一个目录；
        // 反过来先删盘，库侧失败会留下"行还在、仓库没了"的项目
        Path repoPath = Path.of(project.repoPath());
        eraseQuietly(() -> workspaces.removeWorkspace(WorkspaceId.of(leaver, project.id()), repoPath),
                "工作区", WorkspaceId.of(leaver, project.id()).toString());
        if (projectIsGone) {
            // 树先走、仓库后走：worktree 是主仓库的兄弟目录，删仓库带不走它们
            eraseQuietly(() -> workspaces.removeRepository(repoPath), "仓库", project.repoPath());
        }
    }

    /**
     * @param userId 成员
     * @param limit  这一页最多多少条
     * @param offset 跳过多少条
     * @return 「我的项目」；分页由仓储层完成，见 {@code ProjectRepository.findByMember}
     */
    public List<ProjectView> listFor(UserId userId, int limit, int offset) {
        return projects.findByMember(userId, limit, offset).stream().map(this::view).toList();
    }

    /**
     * @param project 项目
     * @return 对外的项目视图，包含成员和项目目录名
     */
    public ProjectView view(Project project) {
        return new ProjectView(project.id().value(), project.name(), project.ownerId().value(),
                project.members().stream().map(this::member).toList(),
                // 项目目录名；界面左边那棵树的第一行是它
                ProjectLayout.DIRECTORY);
    }

    // ------------------------------------------------------------------
    // 按住他自己那棵树 / 清磁盘
    // ------------------------------------------------------------------

    /**
     * 按住退出者自己那棵树的租约；他在那儿没有会话时返回 null。
     *
     * <p>null 表示"不需要拿"而不是"拿不到"：一轮执行必须先有一条会话，没有会话的树上
     * 不可能有人在跑。退出的只有他一个人，对方那棵树与这次操作无关。
     *
     * @throws ResponseStatusException 409，他的会话正在运行
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
     * 这个人在这个项目里的所有会话 id（不是每棵树一条，排队留言是按会话排的）。
     *
     * <p>退出时用它在会话行被删之前记下要清哪些内存队列。翻页翻到底的理由和
     * {@link #oneSessionPerTree} 一样。
     */
    private List<SessionId> sessionIdsOf(ProjectId projectId, UserId owner) {
        List<SessionId> ids = new ArrayList<>();
        for (int offset = 0; ; offset += SESSION_PAGE) {
            List<Session> page = sessions.findByProject(projectId, SESSION_PAGE, offset);
            page.stream()
                    .filter(session -> session.ownerId().equals(owner))
                    .forEach(session -> ids.add(session.id()));
            if (page.size() < SESSION_PAGE) {
                return ids;
            }
        }
    }

    /**
     * 这个项目里每棵树挑一条会话当代表。
     *
     * <p>必须翻页到底：一个人的会话可以多到占满第一页，只翻一页会让另一棵树选不出代表。
     * 退出只关心一棵树，选错树会让"有没有在跑"的检查失效。只在退出时调用一次。
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
     * 磁盘清理失败不往上抛，只记日志。
     *
     * <p>走到这里时库里已经定下结果：回一个错误会让用户以为退出失败，再点一次才发现
     * 项目已经不在列表里。地上多留一个目录可以容忍，一个与事实相反的回答不行。
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
