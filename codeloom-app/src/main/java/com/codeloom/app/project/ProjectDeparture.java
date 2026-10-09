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
 * 「退出项目」在库里要做的全部事情，**在一个事务里**。
 *
 * <h2>为什么是一个独立的 bean</h2>
 * 和 {@code SessionErasure} 同一条理由：{@code @Transactional} 只在**跨 bean 调用**时
 * 生效，自己调自己拿不到代理。给事务边界一个明确的落点，
 * 而不是散落在调用方那句"我记得要开事务"里。
 *
 * <h2>顺序是这件事的一半，而且错了不会报错</h2>
 * <ol>
 *   <li><b>先清事件和幂等键 —— 它们都得赶在会话行被删之前。</b>
 *       这两条 SQL 靠 {@code session} 表定位"哪些行属于这个人"，
 *       会话行没了它们就匹配 0 行，而 <strong>DELETE 影响 0 行不报错</strong>：
 *       结果是那些行永远留在库里，且没有任何入口能发现。</li>
 *   <li><b>再删会话行。</b></li>
 *   <li><b>然后是"这个人自己的那棵树"</b>（库里那一行；磁盘上的目录由调用方
 *       在事务之后清，因为这一层够不到文件系统）。</li>
 *   <li><b>最后才是项目本身</b>：还有人就走"少一个成员"（房主可能易主），
 *       没人了就走真删除。</li>
 * </ol>
 *
 * <h2>聊天记录和邀请为什么不跟着人走</h2>
 * 它们挂在**项目**上，不是挂在这个人身上。他走了、项目还在的时候，
 * 留下的人照旧看得到之前聊过什么、还有哪些没用掉的邀请 —— 那些不属于离开的人。
 * 只有项目本身要消失（最后一个人退出）时，它们才一起走。
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
     * @return {@code true} = 他是这里最后一个人，**项目从此不存在了** ——
     *         调用方还要去把仓库目录从磁盘上清掉（这件事必须等事务提交之后做）
     */
    @Transactional
    public boolean leave(Project project, UserId leaver) {
        ProjectId projectId = project.id();

        // ★ 顺序不能动：这两条靠 session 表定位，必须在会话行被删之前跑
        events.byProjectAndOwner(projectId, leaver);
        requests.discardByProjectAndOwner(projectId, leaver);
        sessions.discardByProjectAndOwner(projectId, leaver);

        workspaces.delete(WorkspaceId.of(leaver, projectId));

        if (!project.isLastMember(leaver)) {
            // 还有人：只是少一个成员。房主走了的话，新房主在领域方法里定下来
            projects.save(project.withMemberRemoved(leaver));
            return false;
        }

        // 人走空了 —— 项目真的消失，挂在项目上、不属于任何个人的那些跟着走
        chat.deleteByProject(projectId);
        invitations.deleteByProject(projectId);
        projects.delete(projectId);
        return true;
    }
}
