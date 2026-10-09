package com.codeloom.workspace.persistence;

import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectRowTest {

    private static final ProjectId PROJECT_ID = ProjectId.of("44444444-4444-4444-4444-444444444444");
    private static final UserId ALICE = UserId.of("a1111111-1111-1111-1111-111111111111");
    private static final UserId BOB = UserId.of("b2222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("项目标量字段往返不丢")
    void scalarFieldsSurviveTheRoundTrip() {
        Project project = new Project(PROJECT_ID, ALICE, "codeloom 演示", "D:/repos/demo",
                Set.of(ALICE, BOB));

        ProjectRow row = ProjectRow.of(project);

        assertThat(row.id()).isEqualTo(PROJECT_ID.value());
        assertThat(row.ownerId()).isEqualTo(ALICE.value());
        assertThat(row.name()).isEqualTo("codeloom 演示");
        assertThat(row.repoPath()).isEqualTo("D:/repos/demo");
        assertThat(row.toDomain(Set.of(ALICE, BOB))).isEqualTo(project);
    }

    @Test
    @DisplayName("房主易主要真的写进去 —— 它不在成员表里，只在那一行上")
    void theOwnerIsCarriedAsItsOwnColumn() {
        Project project = new Project(PROJECT_ID, ALICE, "codeloom 演示", "D:/repos/demo",
                Set.of(ALICE, BOB));

        ProjectRow row = ProjectRow.of(project.withMemberRemoved(ALICE));

        assertThat(row.ownerId()).isEqualTo(BOB.value());
        assertThat(row.toDomain(Set.of(BOB)).ownerId()).isEqualTo(BOB);
    }

    @Test
    @DisplayName("成员查不出来时【炸掉】而不是返回一个没有成员的项目")
    void missingMembersFailLoudly() {
        // 数据库里有一行 project，却一行 project_member 都查不到 —— 这说明写入路径漏了。
        // 静默返回一个空成员的项目，会让这个错误一路飘到「谁能看到这个项目」的判断里去。
        ProjectRow row = ProjectRow.of(
                new Project(PROJECT_ID, ALICE, "x", "D:/x", Set.of(ALICE)));

        assertThatThrownBy(() -> row.toDomain(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少要有一名成员");
    }

    @Test
    @DisplayName("【不变量】房主不在成员里的那一行也要炸 —— 那是个谁也接不了手的死结")
    void anOwnerWhoIsNotAMemberFailsLoudly() {
        ProjectRow row = new ProjectRow(PROJECT_ID.value(), ALICE.value(), "x", "D:/x");

        assertThatThrownBy(() -> row.toDomain(Set.of(BOB)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("房主必须是项目成员");
    }
}
