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
 * <h2>关于 {@code save} 没有 fencing token 参数</h2>
 * 和 {@code MyBatisSessionRepository} 是同一个问题、同一个答案：那一列**不在**
 * {@link WorkspaceMapper#save} 的语句里，所以这条路径根本碰不到它。
 * 推进号的是 {@code MyBatisWorkspaceFence#issue}；底下真正碰那一列的，是 {@code WorkspaceMapper} 里的那几个方法。
 *
 * <p>反过来说，**对一棵已存在的工作区，任何绕开 {@code WorkspaceFence} 单独推进那一列的
 * 路径都会开出一个洞** —— 这是设计约束，不是警告。
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
        // 只看库里那一行。磁盘上的 worktree 目录是另一件事（WorkspaceManager.removeWorkspace）——
        // 这一层够不到文件系统，也不该够到
        mapper.deleteById(workspaceId.ownerId().value(), workspaceId.projectId().value());
    }
}
