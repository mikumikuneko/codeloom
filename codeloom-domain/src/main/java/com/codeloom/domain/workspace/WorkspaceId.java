package com.codeloom.domain.workspace;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;

import java.util.Objects;

/**
 * 一棵工作区的标识：**谁**在**哪个项目**里的那一份。
 *
 * <h2>为什么键是「用户 × 项目」，而不是会话</h2>
 * 树挂在「人 + 项目」上：一个人在一个项目里有且只有一棵树，他的所有会话
 * 都在那棵树里干活。**换会话只换对话，不换代码。**
 *
 * <p>键要是落在会话上（每会话一棵 worktree、一条 {@code session/<id>} 分支），
 * **同一个人在同一项目里开第二段对话就看不到上一轮的改动了** —— 代码明明是他的，
 * 却因为"换了条会话"而隔在另一棵树里；而且这个人的两条会话线还要为「合并」付一次代价，
 * 而它们本来就是同一个人的同一份工作。
 *
 * <h2>它同时是并发控制的粒度</h2>
 * 执行租约锁的是**一棵树**（见 {@code ExecutionLease}），而不只是"一条会话" ——
 * 因为同一个人的两条会话现在共用一份目录，谁也不能在另一个人正写的时候插进去。
 * 这是最要紧的一条推论：**锁的键和树的键必须是同一个**，否则两个会话能同时
 * 写一份 worktree，而那正是 worktree 隔离本来要防的事。
 *
 * <p>（两个**不同**的人各有自己的树，所以他们照旧能同时跑，互不干扰 ——
 * 隔离没有消失，只是从"每个会话"提到了"每个人"。）
 *
 * <h2>两个 UUID 拼成文本形式，用冒号分隔</h2>
 * Redis 键、目录名、日志都用它，见 {@link #value()}。UUID 里不会出现冒号，
 * 所以拼起来不会有歧义。
 */
public record WorkspaceId(UserId ownerId, ProjectId projectId) {

    public WorkspaceId {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(projectId, "projectId");
    }

    public static WorkspaceId of(UserId ownerId, ProjectId projectId) {
        return new WorkspaceId(ownerId, projectId);
    }

    /**
     * 文本形式：{@code <ownerId>:<projectId>}。
     *
     * <p>目录名用它（见 {@code LocalWorkspaceManager}）：那一层多出来的斜杠会把
     * 同一棵树拆进两层目录里，而"一棵树一个目录名"是路径规则好读的前提。
     */
    public String value() {
        return ownerId.value() + ":" + projectId.value();
    }

    @Override
    public String toString() {
        return value();
    }
}
