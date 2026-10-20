package com.codeloom.workspace.persistence;

import com.codeloom.domain.port.ProjectInvitationRepository;
import com.codeloom.domain.project.ProjectInvitation;
import com.codeloom.domain.project.ProjectId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** {@link ProjectInvitationRepository} 的 MyBatis 实现，见 {@link ProjectInvitationMapper}。 */
@Repository
public class MyBatisProjectInvitationRepository implements ProjectInvitationRepository {

    private final ProjectInvitationMapper mapper;

    public MyBatisProjectInvitationRepository(ProjectInvitationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(ProjectInvitation invitation) {
        mapper.save(ProjectInvitationRow.of(invitation));
    }

    @Override
    public Optional<ProjectInvitation> findByToken(String token) {
        return Optional.ofNullable(mapper.findByToken(token)).map(ProjectInvitationRow::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code MANDATORY}：不在事务里调用时，行锁会在语句结束时释放，而调用方会
     * 误以为自己持有互斥。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProjectInvitation> findByTokenForUpdate(String token) {
        return Optional.ofNullable(mapper.findByTokenForUpdate(token))
                .map(ProjectInvitationRow::toDomain);
    }

    @Override
    public List<ProjectInvitation> findByProject(ProjectId projectId) {
        return mapper.findPendingByProject(projectId.value()).stream()
                .map(ProjectInvitationRow::toDomain)
                .toList();
    }

    @Override
    public void deleteByProject(ProjectId projectId) {
        mapper.deleteByProject(projectId.value());
    }
}
