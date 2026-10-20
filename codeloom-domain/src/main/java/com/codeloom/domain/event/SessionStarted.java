package com.codeloom.domain.event;

/**
 * 会话开始、工作区已就位，是事件流的第一条，让回放有起点。
 *
 * <p>工作区挂在「用户 × 项目」上，同一个人的第二条会话看到同一棵树，因此 {@code branch} 与
 * {@code worktreePath} 会和上一条相同；真正不同的是 {@code baseCommit} —— 第二条会话从上一条
 * 干完的位置开始。见
 * {@code docs/decisions/architecture/2026-09-24-workspace-per-person-merge-to-converge}。
 *
 * @param branch       工作区的 git 分支名
 * @param worktreePath 工作区在宿主机上的绝对路径
 * @param baseCommit   这条会话的出发点 sha
 */
public record SessionStarted(String branch, String worktreePath, String baseCommit) implements PersistentEvent {
}
