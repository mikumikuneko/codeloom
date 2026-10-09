package com.codeloom.app.persistence;

import com.codeloom.app.support.AbstractPersistenceTest;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code project} / {@code project_member} 两张表的真库测试。
 *
 * <p>重点在成员上：{@code Project} 是一个带 {@code Set<UserId>} 的聚合，落库却是两张表，
 * 所以「写进去读出来还是同一个聚合」这件事必须对着真库验一次。
 */
class ProjectPersistenceTest extends AbstractPersistenceTest {

    @Autowired
    private ProjectRepository projects;

    @Test
    @DisplayName("成员在另一张表，写进去读出来仍是同一个聚合")
    void membersRoundTrip() {
        Project project = newProject("codeloom 往返", ALICE, BOB);

        projects.save(project);

        assertThat(projects.findById(project.id())).contains(project);
    }

    @Test
    @DisplayName("成员被移除后旧成员真的从表里没了（全删再全插那一步）")
    void savingAgainDropsRemovedMembers() {
        Project project = newProject("codeloom 退人", ALICE, BOB);
        projects.save(project);

        // 要验的是**仓储的行为**：save 是"全删再全插"，所以少掉的那个成员必须真的
        // 从表里消失。领域方法只负责把"少一个成员"拼出来（退出项目走的就是它）
        projects.save(project.withMemberRemoved(BOB));

        Project reloaded = projects.findById(project.id()).orElseThrow();
        assertThat(reloaded.members()).containsExactly(ALICE);
    }

    @Test
    @DisplayName("「我的项目」按成员过滤，别人的项目不该出现")
    void findByMemberFiltersByMembership() {
        Project mine = newProject("A 的项目", ALICE, BOB);
        Project notMine = newProject("别人的项目", BOB, CAROL);
        projects.save(mine);
        projects.save(notMine);

        List<ProjectId> alices = projects.findByMember(ALICE, 100, 0).stream()
                .map(Project::id).toList();

        assertThat(alices).contains(mine.id()).doesNotContain(notMine.id());
    }

    @Test
    @DisplayName("【房主易主】退出是真的把人从表里去掉 —— 他自己那边看不见了，接手的人看得见")
    void leavingRemovesTheMembershipRowForReal() {
        Project project = newProject("codeloom 易主", ALICE, BOB);
        projects.save(project);

        projects.save(project.withMemberRemoved(ALICE));

        // 退出的项目不在"我的项目"里，靠的是成员行真的没了（那条 JOIN 接不上），
        // 而不是某个"看不见但还是活着"的状态 —— 这一条盯的就是这个
        assertThat(idsOf(ALICE)).doesNotContain(project.id());
        assertThat(idsOf(BOB)).contains(project.id());
        // 而房主那一列跟着换人（它不在成员表里，只在 project 行上）
        Project reloaded = projects.findById(project.id()).orElseThrow();
        assertThat(reloaded.ownerId()).isEqualTo(BOB);
        assertThat(reloaded.members()).containsExactly(BOB);
    }

    private List<ProjectId> idsOf(UserId userId) {
        return projects.findByMember(userId, 100, 0).stream().map(Project::id).toList();
    }

    @Test
    @DisplayName("【分页】limit 真的限制了条数，offset 真的往后跳 —— 而且两者一起才拼得回全量")
    void findByMemberPagesForReal() {
        // 排序键是 (name, id)，所以按名字给个稳定顺序，翻页结果才可断言
        for (int i = 1; i <= 5; i++) {
            projects.save(newProject("分页-%02d".formatted(i), ALICE));
        }

        List<String> page1 = names(projects.findByMember(ALICE, 2, 0));
        List<String> page2 = names(projects.findByMember(ALICE, 2, 2));
        List<String> page3 = names(projects.findByMember(ALICE, 2, 4));

        assertThat(page1).containsExactly("分页-01", "分页-02");
        assertThat(page2).containsExactly("分页-03", "分页-04");
        // 最后一页不足一整页是正常的，不是错误 —— 客户端靠"拿到的比 limit 少"判断到底了
        assertThat(page3).containsExactly("分页-05");
        // 三页拼起来正好是第一页起全部，不重不漏 —— 这条才是分页最容易错的地方
        assertThat(List.of(page1, page2, page3).stream().flatMap(List::stream).toList())
                .containsExactly("分页-01", "分页-02", "分页-03", "分页-04", "分页-05");
    }

    private List<String> names(List<Project> page) {
        return page.stream().map(Project::name).toList();
    }

    @Test
    @DisplayName("批量成员查询（foreach）在多个项目时也能把成员装配齐")
    void membersAreBatchedAcrossSeveralProjects() {
        Project first = newProject("批量甲", ALICE, BOB);
        Project second = newProject("批量乙", ALICE, CAROL);
        projects.save(first);
        projects.save(second);

        assertThat(projects.findAll()).contains(first, second);
    }
}
