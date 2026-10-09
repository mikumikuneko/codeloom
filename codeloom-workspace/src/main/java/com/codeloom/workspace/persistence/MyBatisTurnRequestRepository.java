package com.codeloom.workspace.persistence;

import com.codeloom.domain.port.TurnRequestRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** {@link TurnRequestRepository} 的 MyBatis 实现，见 {@link TurnRequestMapper}。 */
@Component
public class MyBatisTurnRequestRepository implements TurnRequestRepository {

    private final TurnRequestMapper mapper;

    public MyBatisTurnRequestRepository(TurnRequestMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean claim(SessionId sessionId, String clientMessageId) {
        return mapper.claim(sessionId.value(), clientMessageId, Instant.now()) > 0;
    }

    @Override
    public void discard(SessionId sessionId) {
        // 同 MyBatisSessionRepository.discard：不看影响行数。
        // "这条会话的幂等键一条都没有"和"清掉了三条"对调用方是同一件事
        mapper.deleteBySession(sessionId.value());
    }

    @Override
    public void discardByProjectAndOwner(ProjectId projectId, UserId ownerId) {
        mapper.deleteByProjectAndOwner(projectId.value(), ownerId.value());
    }
}
