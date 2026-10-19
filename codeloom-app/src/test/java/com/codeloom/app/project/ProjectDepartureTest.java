package com.codeloom.app.project;

import com.codeloom.app.support.TestUsers;
import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.port.EventDiscard;
import com.codeloom.domain.port.ProjectInvitationRepository;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.TurnRequestRepository;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退出项目必须按**锁之后**的成员状态判断，不能按请求进来时的快照。
 *
 * <p>两个人同时退出时，后到的那个如果拿旧快照判断，会把先退出的人重新写回成员表；
 * 反过来，如果对方先退了、自己成了最后一人，按旧快照判断就会把项目留下来。
 */
class ProjectDepartureTest {

    @Test
    @DisplayName("【锁后重读】对方先退出、自己成了最后一人 → 项目跟着消失")
    void leaveUsesTheMembershipReadAfterTheLock() {
        ProjectId projectId = ProjectId.generate();
        // 请求进来时看到的还是两个人
        Project stale = new Project(projectId, TestUsers.ALICE, "锁后重读",
                "D:/repos/" + projectId.value(), Set.of(TestUsers.ALICE, TestUsers.BOB));
        // 拿到锁之后，ALICE 已经先退出了 —— 现在只剩 BOB
        Project current = new Project(projectId, TestUsers.BOB, "锁后重读",
                "D:/repos/" + projectId.value(), Set.of(TestUsers.BOB));

        ProjectRepository projects = mock(ProjectRepository.class);
        EventDiscard events = mock(EventDiscard.class);
        TurnRequestRepository requests = mock(TurnRequestRepository.class);
        SessionRepository sessions = mock(SessionRepository.class);
        ChatMessageRepository chat = mock(ChatMessageRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        ProjectInvitationRepository invitations = mock(ProjectInvitationRepository.class);
        when(projects.lockAndFind(projectId)).thenReturn(Optional.of(current));

        ProjectDeparture departure = new ProjectDeparture(
                projects, events, requests, sessions, chat, workspaces, invitations);

        assertThat(departure.leave(stale, TestUsers.BOB)).isTrue();

        // 按旧快照会走"少一个成员"、把 ALICE 写回去；按当前状态才是真删除
        verify(projects, never()).save(org.mockito.ArgumentMatchers.any());
        verify(chat).deleteByProject(projectId);
        verify(invitations).deleteByProject(projectId);
        verify(projects).delete(projectId);
    }
}
