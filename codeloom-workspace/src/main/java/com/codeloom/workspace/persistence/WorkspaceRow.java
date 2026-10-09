package com.codeloom.workspace.persistence;

import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;

import java.nio.file.Path;

/**
 * {@code workspace} 表的一行 —— 谁在哪个项目里的那棵树，现在停在哪儿。
 *
 * <h2>主键是 {@code (owner_id, project_id)}，不是合成 id</h2>
 * 这个键本来就是个天然复合键（"谁"加"哪个项目"），造一个 UUID 出来只会多一列没人读的
 * 数据，外加一次"拿 id 反查是谁"的查询。项目成员表、BYOK 密钥表都是这么做的。
 *
 * <h2>为什么这里没有 fencingToken</h2>
 * 和 {@link SessionRow} 里那段是同一个理由，方向相反：那一列**确实在这张表上**，
 * 但它不是领域状态，而是并发控制的落点，只由 {@code WorkspaceFence} 在写入路径上推进。
 * 所以 {@link #COLUMNS} 里刻意不含它 —— 一旦 {@code save()} 也带上它，
 * 下一次保存工作区就会把号写回旧值，僵尸写入者于是又能落数据。
 */
public record WorkspaceRow(String ownerId,
                           String projectId,
                           String branch,
                           String worktreePath,
                           String headCommit) {

    /**
     * 查询用的列清单。理由和 {@link SessionRow#COLUMNS} 那段完全一样：
     * 不用 {@code SELECT *}，而且每个下划线列都显式起别名到 record 的组件名。
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

    /** 行 → 领域对象。不在这里做防御性校验，理由同 {@link SessionRow#toDomain()}。 */
    Workspace toDomain() {
        return new Workspace(
                WorkspaceId.of(UserId.of(ownerId), ProjectId.of(projectId)),
                Path.of(worktreePath),
                branch,
                headCommit);
    }
}
