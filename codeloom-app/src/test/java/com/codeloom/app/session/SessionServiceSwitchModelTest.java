package com.codeloom.app.session;

import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.app.note.AgentNotes;
import com.codeloom.app.support.TestUsers;
import com.codeloom.app.turn.PendingMessages;
import com.codeloom.app.turn.SessionProjections;
import com.codeloom.app.turn.SessionWriter;
import com.codeloom.app.workspace.WorkspaceProvisioner;
import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.SessionState;
import com.codeloom.domain.project.ProjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 换模型时**必须从租约之后的状态出发**，不能用控制器送来的请求快照。
 *
 * <p>这是那些"整行 upsert"最容易漏掉的一条：等锁期间另一轮可能已经跑完并推进了
 * {@code turn_index}/状态，而 {@code Session.withModel} 会把旧值一起写回去。
 */
class SessionServiceSwitchModelTest {

    @Test
    @DisplayName("【租约后重读】换模型用新读到的会话，不把旧 state/turn_index 写回去")
    void switchModelUsesTheSessionReadAfterTheLease() {
        SessionRepository sessions = mock(SessionRepository.class);
        WorkspaceRepository stored = mock(WorkspaceRepository.class);
        WorkspaceProvisioner provisioner = mock(WorkspaceProvisioner.class);
        WorkspaceManager workspaces = mock(WorkspaceManager.class);
        EventStore events = mock(EventStore.class);
        ExecutionLease leases = mock(ExecutionLease.class);
        SessionWriter writer = mock(SessionWriter.class);
        LlmClientProvider clients = mock(LlmClientProvider.class);
        SessionCheckpoints checkpoints = mock(SessionCheckpoints.class);
        SessionErasure erasure = mock(SessionErasure.class);
        AgentNotes notes = mock(AgentNotes.class);
        SessionProjections projections = mock(SessionProjections.class);
        PendingMessages pending = mock(PendingMessages.class);

        SessionService service = new SessionService(sessions, stored, provisioner, workspaces,
                events, leases, writer, clients, checkpoints, erasure, notes, projections, pending);

        ModelConfig original = new ModelConfig(ProviderId.of("deepseek"), "deepseek-flash", "系统提示");
        Session stale = Session.create(SessionId.generate(), ProjectId.generate(),
                TestUsers.OWNER, original);
        // 请求读到之后、拿到租约之前，另一轮已经跑完了：状态和轮次都往前走了
        Session fresh = new Session(stale.id(), stale.projectId(), stale.ownerId(),
                SessionState.WAITING_USER, 3, original);
        LeaseToken token = mock(LeaseToken.class);
        when(leases.tryAcquire(stale)).thenReturn(Optional.of(token));
        when(sessions.findById(stale.id())).thenReturn(Optional.of(fresh));
        when(clients.findClient(eq(stale.ownerId()), any(ModelConfig.class)))
                .thenReturn(Optional.of(mock(LlmClient.class)));

        ModelConfig expected = new ModelConfig(ProviderId.of("deepseek"), "deepseek-reasoner",
                original.systemPrompt());
        SessionWriter.Written written = mock(SessionWriter.Written.class);
        when(writer.changeModel(eq(fresh), any(ModelConfig.class), eq(token))).thenReturn(written);
        when(written.session()).thenReturn(fresh.withModel(expected));

        Session result = service.switchModel(stale, expected.provider(), expected.modelId());

        assertThat(result).isEqualTo(fresh.withModel(expected));
        // 这一条是这次修复的核心：传给写入层的是**重读之后**的会话
        verify(writer).changeModel(eq(fresh), any(ModelConfig.class), eq(token));
    }
}
