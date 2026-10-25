package com.codeloom.app.auth;

import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 资源访问判定：这个人能不能碰这个东西。所有资源端点都从这里过一遍。
 *
 * <p>不是成员时返回 404 而不是 403：403 等于确认对象存在，拿一批 id 就能枚举出项目和
 * 会话；对“不是我的东西”一律装作不存在。但成员访问别人的会话返回 403 —— 那时对象
 * 存在是他已经知道的事实，装不存在只会让人困惑。判据是“对方本来知不知道它存在”，
 * 不是哪个码更常见。见
 * {@code docs/decisions/architecture/2026-09-26-authorization-404-before-403.md}。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProjectAccess {

    private final ProjectRepository projects;
    private final SessionRepository sessions;

    public ProjectAccess(ProjectRepository projects, SessionRepository sessions) {
        this.projects = projects;
        this.sessions = sessions;
    }

    /**
     * 取项目并确认这个人是它的成员。
     *
     * @param userId    当前用户
     * @param projectId 项目
     * @return 项目
     * @throws ResponseStatusException 404：项目不存在，或这个人不是它的成员 —— 两者不区分
     */
    public Project requireMember(UserId userId, ProjectId projectId) {
        return projects.findById(projectId)
                .filter(project -> project.hasMember(userId))
                .orElseThrow(() -> notFound("项目不存在"));
    }

    /**
     * 确认某个人是这个项目的成员；不是就按项目不存在回答。
     *
     * <p>给已经拿到 {@link Project} 的调用方用（例如“看谁的树”那个参数）：
     * 规则和 {@link #requireMember} 是同一句，别在别处再写一遍。
     *
     * @param project 项目
     * @param userId  要确认的人
     * @throws ResponseStatusException 404：这个人不是成员
     */
    public void requireMembership(Project project, UserId userId) {
        if (!project.hasMember(userId)) {
            throw notFound("项目不存在");
        }
    }

    /**
     * 取一条会话，并确认这个人看得见它（是它所属项目的成员）。
     *
     * @param userId    当前用户
     * @param sessionId 会话
     * @return 会话
     * @throws ResponseStatusException 404：会话不存在，或这个人不在它所属的项目里
     */
    public Session requireVisible(UserId userId, SessionId sessionId) {
        Session session = sessions.findById(sessionId).orElseThrow(() -> notFound("会话不存在"));
        if (projects.findById(session.projectId())
                .filter(project -> project.hasMember(userId)).isEmpty()) {
            throw notFound("会话不存在");
        }
        return session;
    }

    /**
     * 确认这个人能驱动这条会话：看得出它，而且它是他自己的。
     *
     * <p>驱动必须比旁观更严：允许成员驱动别人的会话，等于让 A 用自己的提示词去改
     * B 的工作区，既破坏“两个独立会话”这个前提，也让审计流说不清这轮是谁让它跑的。
     *
     * @param userId    当前用户
     * @param sessionId 会话
     * @return 会话
     * @throws ResponseStatusException 404：他看不到这条会话；403：看得到，但不是他的
     */
    public Session requireDriver(UserId userId, SessionId sessionId) {
        Session session = requireVisible(userId, sessionId);
        if (!session.ownerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能驱动自己的会话");
        }
        return session;
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }
}
