package com.codeloom.workspace.persistence;

import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.workspace.WorkspaceId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link WorkspaceRepository} 的持久化实现。
 *
 * <p>{@code save} 不碰 {@code fencing_token}：列清单里没有它，推号的唯一入口是
 * {@link MyBatisWorkspaceFence#issue}。对一棵已存在的工作区，任何绕开 fence 推进该列的
 * 路径都会让过期租约重新获得写权限。
 */
@Repository
public class MyBatisWorkspaceRepository implements WorkspaceRepository {

    private final WorkspaceMapper mapper;

    public MyBatisWorkspaceRepository(WorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Workspace> find(WorkspaceId workspaceId) {
        return Optional.ofNullable(
                        mapper.findById(workspaceId.ownerId().value(), workspaceId.projectId().value()))
                .map(WorkspaceRow::toDomain);
    }

    @Override
    public List<Workspace> findByProject(ProjectId projectId) {
        return mapper.findByProject(projectId.value()).stream()
                .map(WorkspaceRow::toDomain)
                .toList();
    }

    @Override
    public void save(Workspace workspace) {
        mapper.save(WorkspaceRow.of(workspace));
    }

    @Override
    public void delete(WorkspaceId workspaceId) {
        // 只删库里那一行；磁盘上的 worktree 由 WorkspaceManager.removeWorkspace 处理，
        // 这一层不碰文件系统
        mapper.deleteById(workspaceId.ownerId().value(), workspaceId.projectId().value());
    }
}
