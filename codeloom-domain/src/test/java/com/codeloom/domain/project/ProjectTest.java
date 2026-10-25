package com.codeloom.domain.project;

import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Project} 的不变量。最多两个人：真正拦住第三个成员的是紧凑构造器，
 * {@code InvitationService.accept} 里的满员拒绝只是提前提示。房主必须是成员，否则没人
 * 接得了手。退不到没人：最后一人退出走真删除（见 {@link Project#isLastMember}），
 * 不经过 {@code withMemberRemoved}。见
 * {@code docs/decisions/architecture/2026-09-29-project-departure-not-tombstones.md}。
 */
class ProjectTest {

    private static final UserId ALICE = UserId.of("aaaa0000-0000-0000-0000-000000000002");
    private static final UserId BOB = UserId.of("aaaa0000-0000-0000-0000-000000000003");
    private static final UserId CAROL = UserId.of("aaaa0000-0000-0000-0000-000000000004");

    private static Project project(UserId... members) {
        // 房主取第一个：这样"房主在不在成员里"就和调用方给的那串直接对应上。
        UserId owner = members[0];
        return new Project(ProjectId.generate(), owner, "codeloom", "D:/repos/demo", Set.of(members));
    }

    private static Project withOwner(UserId owner, UserId... members) {
        return new Project(ProjectId.generate(), owner, "codeloom", "D:/repos/demo", Set.of(members));
    }

    @Test
    @DisplayName("【产品前提】最多两个人 —— 第三个必须被拒，而且是构造期就拒")
    void atMostTwoMembers() {
        assertThat(Project.MAX_MEMBERS).isEqualTo(2);
        Project two = project(ALICE, BOB);
        assertThat(two.members()).hasSize(2);

        assertThatThrownBy(() -> project(ALICE, BOB, CAROL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多 2 名成员");
    }

    @Test
    @DisplayName("加成员走同一条校验 —— withMemberAdded 拼出来的新值也受构造器管")
    void addingAThirdMemberIsRejectedTheSameWay() {
        Project two = project(ALICE, BOB);

        // 上限校验刻意不在 withMemberAdded 里重复一遍：它写在紧凑构造器里，
        // 于是"拼出新值"这条路径也绕不过去
        assertThatThrownBy(() -> two.withMemberAdded(CAROL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多 2 名成员");
    }

    @Test
    @DisplayName("加一个已经是成员的人不报错，成员集合也不变")
    void addingAnExistingMemberIsIdempotent() {
        Project two = project(ALICE, BOB);

        assertThat(two.withMemberAdded(BOB).members()).containsExactlyInAnyOrder(ALICE, BOB);
    }

    @Test
    @DisplayName("一个人都没有的项目不成立 —— 「最后一个人退出」走的是删除，不是这条路")
    void aProjectNeedsAtLeastOneMember() {
        Project two = project(ALICE, BOB);

        assertThatThrownBy(() -> two.withMemberRemoved(ALICE).withMemberRemoved(BOB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少要有一名成员");
    }

    @Test
    @DisplayName("【不变量】房主必须是成员 —— 否则会得到一个谁也接不了手的项目")
    void theOwnerMustBeAMember() {
        assertThatThrownBy(() -> withOwner(CAROL, ALICE, BOB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("房主必须是项目成员");
    }

    @Test
    @DisplayName("名字和仓库路径都不能是空白")
    void nameAndRepoPathMustNotBeBlank() {
        assertThatThrownBy(() -> new Project(ProjectId.generate(), ALICE, "  ", "D:/repos/x",
                Set.of(ALICE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目名");
        assertThatThrownBy(() -> new Project(ProjectId.generate(), ALICE, "codeloom", "",
                Set.of(ALICE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仓库路径");
    }

    @Test
    @DisplayName("【防御性复制】外面那个 Set 被改了，不该动到这个聚合")
    void theMemberSetIsCopiedDefensively() {
        Set<UserId> handedIn = new LinkedHashSet<>(Set.of(ALICE));
        Project project = new Project(ProjectId.generate(), ALICE, "codeloom", "D:/repos/x",
                handedIn);

        handedIn.add(BOB);

        // 拿着原集合继续改聚合的内部状态，是值对象最经典的一种破法
        assertThat(project.members()).containsExactly(ALICE);
        assertThat(project.hasMember(BOB)).isFalse();
    }

    @Test
    @DisplayName("成员是一个集合：顺序不同也是同一个项目")
    void memberOrderDoesNotMatter() {
        // id 必须一样 —— 两个不同的项目当然不相等，那验的就不是这件事了
        ProjectId id = ProjectId.generate();

        assertThat(new Project(id, ALICE, "codeloom", "D:/repos/demo", Set.of(ALICE, BOB)))
                .isEqualTo(new Project(id, ALICE, "codeloom", "D:/repos/demo", Set.of(BOB, ALICE)));
    }

    @Test
    @DisplayName("非房主退出：成员少一个，房主不变")
    void anOrdinaryMemberLeavingChangesNothingElse() {
        Project two = project(ALICE, BOB);

        Project after = two.withMemberRemoved(BOB);

        assertThat(after.members()).containsExactly(ALICE);
        assertThat(after.ownerId()).isEqualTo(ALICE);
        assertThat(after.isLastMember(ALICE)).isTrue();
    }

    @Test
    @DisplayName("【房主易主】房主退出时，剩下那个人接手 —— 否则会留下一个没主的项目")
    void theOwnerHandsOverWhenLeaving() {
        Project two = project(ALICE, BOB);

        Project after = two.withMemberRemoved(ALICE);

        // 不转的话会留下没有房主的项目：之后任何一次退出都算"最后一个人"，
        // 那条路会连同仓库删掉整个项目
        assertThat(after.ownerId()).isEqualTo(BOB);
        assertThat(after.members()).containsExactly(BOB);
    }

    @Test
    @DisplayName("isLastMember 决定走哪条路：还有人只是少一个，没人了才是项目结束")
    void isLastMemberDecidesWhichPath() {
        Project two = project(ALICE, BOB);

        assertThat(two.isLastMember(ALICE)).isFalse();
        assertThat(two.withMemberRemoved(BOB).isLastMember(ALICE)).isTrue();
        // 不在这个项目里的人当然也不是"最后一个人"
        assertThat(two.isLastMember(CAROL)).isFalse();
    }
}
