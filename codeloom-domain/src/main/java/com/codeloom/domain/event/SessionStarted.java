package com.codeloom.domain.event;

/**
 * 会话开始，工作区已就位。作为事件流的第 0 条，让「回放一条会话」有明确起点。
 *
 * <h2>这三个字段描述的是**那条会话开始干活时**工作区的样子</h2>
 * 工作区本身挂在「用户 × 项目」上（见 {@code WorkspaceId}），同一个人在这个项目里的
 * 第二条会话会看到**同一棵树**，所以 {@code branch} 和 {@code worktreePath} 会和上一条
 * 会话记的一样。这不是冗余，是"这条会话是从哪儿开始的"这句话的一部分。
 *
 * <p>真正会不同的是 {@code baseCommit}：第二条会话开始的位置，是上一条会话干完之后的位置。
 *
 * @param branch       这条会话所在工作区的 git 分支名
 * @param worktreePath 那条工作区在宿主机上的绝对路径
 * @param baseCommit   这条会话的出发点 sha
 */
public record SessionStarted(String branch, String worktreePath, String baseCommit) implements PersistentEvent {
}
