package com.codeloom.app.project;

import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.port.EventDiscard;
import com.codeloom.domain.port.ProjectInvitationRepository;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.TurnRequestRepository;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退出项目时在库里要做的全部事情，在一个事务里完成。
 *
 * <p>拆成独立 bean 是因为 {@code @Transactional} 只在跨 bean 调用时生效，
 * 与 {@code SessionErasure} 同理。
 *
 * <p>顺序是契约的一半，错了不会报错：事件和幂等键必须先删，它们靠 {@code session} 表
 * 定位行，会话行删掉后只会匹配 0 行，而 DELETE 影响 0 行不报错，残留永远不会被发现。
 * 然后删会话行、这个人的工作区行（磁盘目录由调用方在事务后清），最后处理项目本身：
 * 还有人则只移除成员，没人则真正删除。
 *
 * <p>聊天记录和邀请挂在项目上而不是人上：项目还在时留下的人仍能看到；只有项目消失时
 * 才一起删除。
 */
@Component
public class ProjectDeparture {

    private final ProjectRepository projects;
    private final EventDiscard events;
    private final TurnRequestRepository requests;
    private final SessionRepository sessions;
    private final ChatMessageRepository chat;
    private final WorkspaceRepository workspaces;
    private final ProjectInvitationRepository invitations;

    public ProjectDeparture(ProjectRepository projects,
                            EventDiscard events,
                            TurnRequestRepository requests,
                            SessionRepository sessions,
                            ChatMessageRepository chat,
                            WorkspaceRepository workspaces,
                            ProjectInvitationRepository invitations) {
        this.projects = projects;
        this.events = events;
        this.requests = requests;
        this.sessions = sessions;
        this.chat = chat;
        this.workspaces = workspaces;
        this.invitations = invitations;
    }

    /**
     * @param project 请求进入时读到的项目快照
     * @param leaver  退出的人
     * @return true 表示项目已不存在（他是最后一个人，或上一次退出已经删掉它），调用方
     *         还要在事务提交后清掉磁盘上的仓库目录；false 表示项目仍在
     */
    @Transactional
    public boolean leave(Project project, UserId leaver) {
        ProjectId projectId = project.id();

        // 入参快照可能已过期（对方也可能退出）。先锁项目再锁定读当前成员：
        // 两个人都退出时，后到的必须看到先到的已经走了，否则会把对方写回成员表
        Project current = projects.lockAndFind(projectId).orElse(null);

        // 顺序不能动：这两条靠 session 表定位，必须在会话行被删之前跑
        events.byProjectAndOwner(projectId, leaver);
        requests.discardByProjectAndOwner(projectId, leaver);
        sessions.discardByProjectAndOwner(projectId, leaver);

        workspaces.delete(WorkspaceId.of(leaver, projectId));

        if (current == null || !current.hasMember(leaver)) {
            // 项目已经没了（上一次退出删掉了），或者这个人已经走了。
            // 个人数据已经清过；只有项目真的没了才返回 true —— 调用方据此清仓库目录，
            // 重复删除是幂等的。人已经走了但项目还在时返回 false，不能动共享的仓库。
            return current == null;
        }

        if (!current.isLastMember(leaver)) {
            // 还有人：只是少一个成员。房主走了的话，新房主在领域方法里定下来
            projects.save(current.withMemberRemoved(leaver));
            return false;
        }

        // 人走空了 —— 项目真的消失，挂在项目上、不属于任何个人的那些跟着走
        chat.deleteByProject(projectId);
        invitations.deleteByProject(projectId);
        projects.delete(projectId);
        return true;
    }
}
