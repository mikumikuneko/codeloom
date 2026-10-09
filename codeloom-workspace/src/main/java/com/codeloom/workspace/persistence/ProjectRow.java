package com.codeloom.workspace.persistence;

import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;

import java.util.Set;

/**
 * {@code project} 表的一行。
 *
 * <p>成员不在这一行里 —— 它是另一张表。所以转换方向是不对称的：
 * 写出时只取 {@code Project} 的标量字段，读回时成员得由调用方另外查出来传进来。
 * 这不对称是表的形状决定的，不在这一层硬拗成对称的。
 */
public record ProjectRow(String id, String ownerId, String name, String repoPath) {

    static final String COLUMNS = "id, owner_id AS ownerId, name, repo_path AS repoPath";

    static ProjectRow of(Project project) {
        return new ProjectRow(project.id().value(), project.ownerId().value(),
                project.name(), project.repoPath());
    }

    /**
     * @param members 从 {@code project_member} 查出来的成员，必须非空
     */
    Project toDomain(Set<UserId> members) {
        // 成员为空会让 Project 的紧凑构造器抛「项目至少要有一名成员」。
        // 这是对的：一个有成员行的项目却查不出成员，说明写入路径漏了，不该被静默容忍。
        // 房主不在成员里同样会抛 —— 那是一个谁也接不了手的死结
        return new Project(ProjectId.of(id), UserId.of(ownerId), name, repoPath, members);
    }
}
