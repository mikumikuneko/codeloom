package com.codeloom.domain.project;

import com.codeloom.domain.user.UserId;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 项目 = 一个 git 仓库 + 它的成员。会话都挂在项目下面。
 *
 * <h2>为什么既有 {@code ownerId} 又有 {@code members}</h2>
 * 它们回答的不是同一个问题：{@code ownerId} 是"现在谁是房主"，{@code members} 是
 * "现在谁在里面"。把房主表达成"成员集合里的某一个"是做不到的 —— 集合没有先后、没有主次，
 * 而这两个集合都会变（有人退出），所以房主必须单独记着。
 *
 * <p>房主的唯一职责是**退出时把它交出去**：他是项目里最后一个人时，项目跟着他一起消失；
 * 还有别人时，房主转给那个人（见 {@link #withMemberRemoved}）。所以那条不变量
 * —— 房主必须是成员 —— 写在紧凑构造器里，任何构造路径都绕不过去：
 * 一个"房主不在成员里"的项目会变成一个谁也接不了手的死结。
 *
 * <h2>项目怎么消失</h2>
 * 没有"删除项目"这个动作，只有**退出**：最后一个人退出去的时候，项目就真的没了
 * （那一整套清理由应用层的 {@code ProjectDeparture} 做，这个聚合只负责算出
 * "还有没有人"）。这样就不存在"我的一个动作把对方脚下的地抽走"——
 * 两个人里任何一个都只能决定自己走不走。
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

    public boolean hasMember(UserId userId) {
        return members.contains(userId);
    }

    public boolean isOwnedBy(UserId userId) {
        return ownerId.equals(userId);
    }

    /**
     * 他是不是这里最后一个人 —— 是的话，他退出就等于项目从此消失。
     *
     * <p>调用方要靠它分两头：还有人 → 只是少一个成员（房主可能易主）；
     * 没有别人 → 项目本身该被删掉。这条判断属于聚合，因为它是"成员还剩几个"的推论，
     * 而那是这个聚合才知道的事。
     */
    public boolean isLastMember(UserId userId) {
        return members.size() == 1 && members.contains(userId);
    }

    /**
     * 成员变更。上限校验不在这里重复 —— 它已经写在紧凑构造器里，
     * 任何构造路径都绕不过去，这里只要拼出新的成员集合即可。
     */
    public Project withMemberAdded(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        Set<UserId> next = new LinkedHashSet<>(members);
        next.add(userId);
        return new Project(id, ownerId, name, repoPath, next);
    }

    /**
     * 一个人退出。
     *
     * <h2>房主退出 = 房主易主</h2>
     * 还有别人时，房主转给他。不转的话就会留下一个**没有房主**的项目：
     * 后面谁退出都算"最后一个人"，而那条路会把整个项目连仓库一起删掉 ——
     * 于是第一个人前脚刚走，第二个人后脚一退，项目莫名其妙就没了。
     *
     * <p>成员上限是 2，所以"交出去"永远只有一个候选人，不需要排序或选主。
     *
     * <p><strong>不能退到没人。</strong>最后一个人的退出不是"成员少一个"，
     * 而是"这个项目结束了"—— 那条路走的是真删除，不经过这个方法
     * （见 {@link #isLastMember}）。
     */
    public Project withMemberRemoved(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        Set<UserId> next = new LinkedHashSet<>(members);
        next.remove(userId);
        // 剩下的那个接手。空集合会在紧凑构造器里抛"至少要有一名成员"，
        // 而那正是"不能退到没人"这条规则的落点
        UserId nextOwner = isOwnedBy(userId) && !next.isEmpty() ? next.iterator().next() : ownerId;
        return new Project(id, nextOwner, name, repoPath, next);
    }

}
