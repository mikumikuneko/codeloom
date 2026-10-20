package com.codeloom.domain.workspace;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;

import java.util.Objects;

/**
 * 一棵工作区的标识：谁在哪个项目里的那一份。键是「用户 × 项目」，不是会话。
 *
 * <p>一个人在一个项目里有且只有一棵树，所有会话都在其中干活：换会话只换对话，不换代码。
 * 若键落在会话上，同一个人开第二段对话就看不到上一轮的改动，还要为合并付一次代价。
 *
 * <p>它同时是并发控制的粒度：执行租约锁的是一棵树（见 {@link com.codeloom.domain.port.ExecutionLease}），
 * 锁的键必须与树的键相同，否则同一个人的两条会话能同时写一份 worktree；不同的人各有自己的树，照旧能同时跑。
 *
 * <p>文本形式是两个 UUID 用冒号连接，Redis 锁键和日志用它（见 {@link #value()}）；UUID 里不会出现冒号。
 * 磁盘目录不用它：冒号在 Windows 上是保留字符，工作区按 ownerId/projectId 分两层（见 {@code LocalWorkspaceManager}）。
 *
 * @param ownerId   树属于谁；见
 *                  {@code docs/decisions/architecture/2026-09-24-workspace-per-person-merge-to-converge.md}
 * @param projectId 在哪一个项目里
 */
public record WorkspaceId(UserId ownerId, ProjectId projectId) {

    public WorkspaceId {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(projectId, "projectId");
    }

    /**
     * 从用户和项目构造。
     *
     * @param ownerId   树属于谁
     * @param projectId 在哪一个项目里
     * @return 工作区标识
     */
    public static WorkspaceId of(UserId ownerId, ProjectId projectId) {
        return new WorkspaceId(ownerId, projectId);
    }

    /**
     * 文本形式：ownerId 和 projectId 用冒号连接，供 Redis 锁键和日志使用。
     *
     * <p>磁盘目录不用它：冒号在 Windows 上是保留字符，工作区按
     * ownerId/projectId 分两层（见 {@code LocalWorkspaceManager}）。
     *
     * @return 以冒号分隔的文本标识
     */
    public String value() {
        return ownerId.value() + ":" + projectId.value();
    }

    @Override
    public String toString() {
        return value();
    }
}
