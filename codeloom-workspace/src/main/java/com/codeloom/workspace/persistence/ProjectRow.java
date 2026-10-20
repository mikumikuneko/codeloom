package com.codeloom.workspace.persistence;

import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;

import java.util.Set;

/**
 * {@code project} 表的一行。
 *
 * <p>成员在另一张表，因此转换不对称：写出时只取标量字段，读回时成员由调用方查好传入。
 */
public record ProjectRow(String id, String ownerId, String name, String repoPath) {

    static final String COLUMNS = "id, owner_id AS ownerId, name, repo_path AS repoPath";

    static ProjectRow of(Project project) {
        return new ProjectRow(project.id().value(), project.ownerId().value(),
                project.name(), project.repoPath());
    }

    /**
     * @param members 从 {@code project_member} 查出的成员，必须非空
     * @return 领域项目
     * @throws IllegalArgumentException 成员为空，或房主不在成员中，由 {@link Project} 的
     *                                  构造器抛出
     */
    Project toDomain(Set<UserId> members) {
        // 成员为空或房主不在成员里都由 Project 的构造器拒绝：前者说明写入路径漏了，
        // 后者是无人能接手的项目
        return new Project(ProjectId.of(id), UserId.of(ownerId), name, repoPath, members);
    }
}
