package com.codeloom.workspace.persistence;

import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;

import java.nio.file.Path;

/**
 * {@code workspace} 表的一行：谁在哪个项目里的那棵树，当前停在哪个提交。
 *
 * <p>主键是天然复合键 {@code (owner_id, project_id)}，不另造 id。
 * {@link #COLUMNS} 不含 {@code fencing_token}：它不是领域状态，只由
 * {@code WorkspaceFence} 在写入路径上推进；一旦 {@code save} 带上它，保存工作区会把号
 * 写回旧值，让过期租约重新获得写权限。
 */
public record WorkspaceRow(String ownerId,
                           String projectId,
                           String branch,
                           String worktreePath,
                           String headCommit) {

    /**
     * 查询列清单：不用 {@code SELECT *}，每个下划线列显式起别名到 record 组件名，
     * 理由同 {@link SessionRow#COLUMNS}。
     */
    static final String COLUMNS = """
            owner_id AS ownerId, project_id AS projectId, branch,
            worktree_path AS worktreePath, head_commit AS headCommit
            """;

    static WorkspaceRow of(Workspace workspace) {
        return new WorkspaceRow(
                workspace.workspaceId().ownerId().value(),
                workspace.workspaceId().projectId().value(),
                workspace.branch(),
                workspace.path().toString(),
                workspace.headCommit());
    }

    /**
     * 行 → 领域对象；不做防御性校验，理由同 {@link SessionRow#toDomain()}。
     *
     * @return 领域工作区
     * @throws IllegalArgumentException 行里的值不满足领域不变量
     */
    Workspace toDomain() {
        return new Workspace(
                WorkspaceId.of(UserId.of(ownerId), ProjectId.of(projectId)),
                Path.of(worktreePath),
                branch,
                headCommit);
    }
}
