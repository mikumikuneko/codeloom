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
 * 「这个人能不能碰这个东西」。所有资源端点都从这里过一遍。
 *
 * <h2>不是成员时返回 404，而不是 403</h2>
 * 这是刻意的。403 等于告诉对方"**这个东西存在**，只是不给你看" ——
 * 拿一批 id 挨个试一遍，就能把系统里有哪些项目、哪些会话枚举出来。
 * 项目 id 是 UUID、猜不到，但这不构成理由：能枚举和不能枚举是性质上的差别。
 *
 * <p>所以对"不是我的东西"一律装作不存在。
 *
 * <h2>但成员访问别人的会话，返回的是 403</h2>
 * 因为那时候"这东西存在"是**他已经知道的事实**（他就在这个项目里，
 * 会话列表他也看得到）。这时候还装不存在只会让人困惑，而 403 说的正是实情：
 * 你看得到，但这件事不归你做。
 *
 * <p>同一条规则在两种上下文里给出不同的码，判断依据是"对方本来知不知道它存在"，
 * 而不是"哪个码更常见"。
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
     * @throws ResponseStatusException 404：项目不存在，或者这个人不是它的成员 —— 两者不区分
     */
    public Project requireMember(UserId userId, ProjectId projectId) {
        return projects.findById(projectId)
                .filter(project -> project.hasMember(userId))
                .orElseThrow(() -> notFound("项目不存在"));
    }

    /**
     * 取一条会话，并确认这个人**看得见**它（是它所属项目的成员）。
     *
     * @throws ResponseStatusException 404：会话不存在，或者这个人不在它所属的项目里
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
     * 确认这个人能**驱动**这条会话 —— 看得出它，而且它是**他自己**的。
     *
     * <p>为什么驱动要更严：项目的模型是"每个人有自己的 agent 会话，另一方旁观"。
     * 允许成员去驱动别人的会话，等于让 A 用自己的提示词去改 B 的工作区 ——
     * 那既破坏"两个独立会话"这个前提，也让审计流里再也说不清"这轮是谁让它跑的"。
     *
     * @throws ResponseStatusException 403：看得见，但不是他的
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
