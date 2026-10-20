package com.codeloom.domain.project;

import com.codeloom.domain.user.UserId;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 项目 = 一个 git 仓库 + 它的成员；会话都挂在项目下面。
 *
 * <p>{@code ownerId} 与 {@code members} 回答不同的问题："现在谁是房主"与"现在谁在里面"。
 * 房主表达不成成员集合里的某一个，所以单独记着；"房主必须是成员"写在紧凑构造器里，任何
 * 构造路径都绕不过去，否则项目没人接得了手。
 *
 * <p>没有"删除项目"这个动作，只有退出：最后一个人退出时项目随之消失，清理由应用层的
 * {@code ProjectDeparture} 做，本聚合只回答"还有没有人"；因此没有人能单方面让一个还有别人的项目消失。
 *
 * @param id       项目标识
 * @param ownerId  当前房主，必须在 {@code members} 里
 * @param name     项目名，不能为空
 * @param repoPath 仓库路径，不能为空
 * @param members  当前成员，至少一人、至多 {@link #MAX_MEMBERS} 人
 */
public record Project(ProjectId id,
                      UserId ownerId,
                      String name,
                      String repoPath,
                      Set<UserId> members) {

    /** 协作上限。两人协作是这个项目的产品前提，不是临时限制。 */
    public static final int MAX_MEMBERS = 2;

    public Project {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(repoPath, "repoPath");
        Objects.requireNonNull(members, "members");

        if (name.isBlank()) {
            throw new IllegalArgumentException("项目名不能为空");
        }
        if (repoPath.isBlank()) {
            throw new IllegalArgumentException("仓库路径不能为空");
        }
        if (members.isEmpty()) {
            throw new IllegalArgumentException("项目至少要有一名成员");
        }
        if (members.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException(
                    "项目最多 " + MAX_MEMBERS + " 名成员，收到 " + members.size());
        }
        if (!members.contains(ownerId)) {
            throw new IllegalArgumentException(
                    "房主必须是项目成员：" + ownerId + " 不在 " + members);
        }
        // 防御性复制：不让外部拿着原集合继续改这个聚合的内部状态
        members = Set.copyOf(members);
    }

    /**
     * 这个人是不是项目成员。
     *
     * @param userId 待检查的用户
     * @return 是成员时为 true
     */
    public boolean hasMember(UserId userId) {
        return members.contains(userId);
    }

    /**
     * 这个人是不是当前房主。
     *
     * @param userId 待检查的用户
     * @return 是房主时为 true
     */
    public boolean isOwnedBy(UserId userId) {
        return ownerId.equals(userId);
    }

    /**
     * 他是不是这里最后一个人——是的话，他退出等于项目消失。调用方据此分两头：还有人只是
     * 少一个成员（房主可能易主），没人了则项目本身该被删掉；这条判断属于聚合。
     *
     * @param userId 待检查的用户
     * @return 他是唯一成员时为 true
     */
    public boolean isLastMember(UserId userId) {
        return members.size() == 1 && members.contains(userId);
    }

    /**
     * 成员变更。上限校验不在这里重复：紧凑构造器会拦住任何构造路径。
     *
     * @param userId 新成员
     * @return 加入该成员后的项目
     */
    public Project withMemberAdded(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        Set<UserId> next = new LinkedHashSet<>(members);
        next.add(userId);
        return new Project(id, ownerId, name, repoPath, next);
    }

    /**
     * 一个人退出。房主退出时把房主交给剩下的那个人；不交会留下没有房主的项目，之后任何
     * 一次退出都会被当成"最后一个人"并连带删除整个项目。成员上限是 2，候选人只有一个。
     *
     * <p>不能退到没人：最后一个人的退出是项目结束、走真删除，不经过这个方法（见
     * {@link #isLastMember}）。见
     * {@code docs/decisions/architecture/2026-09-29-project-departure-not-tombstones.md}。
     *
     * @param userId 要退出的用户
     * @return 移除该成员（必要时转移房主）后的项目
     * @throws IllegalArgumentException 移除后成员为空时，由紧凑构造器抛出
     */
    public Project withMemberRemoved(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        Set<UserId> next = new LinkedHashSet<>(members);
        next.remove(userId);
        // 剩下的那个接手；空集合会在紧凑构造器里被拒，正是"不能退到没人"这条规则的落点
        UserId nextOwner = isOwnedBy(userId) && !next.isEmpty() ? next.iterator().next() : ownerId;
        return new Project(id, nextOwner, name, repoPath, next);
    }

}
