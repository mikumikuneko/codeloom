package com.codeloom.app.persistence;

import com.codeloom.app.support.AbstractPersistenceTest;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code workspace} 表的真库测试。
 *
 * <h2>为什么这些列在这张表上</h2>
 * 分支、worktree 路径、HEAD 和 {@code fencing_token} 都跟着树走，理由只有一条：
 * **锁锁的是一棵树**，而号的键必须和锁的键是同一个 —— 否则同一个人的两条会话
 * 可以同时拿到"各自的"锁去写同一份目录。见 {@code WorkspaceFence} 的类注释里那个反例。
 *
 * <p>这里接着的是从前 {@code SessionPersistenceTest} 上那两条断言：
 * 复合主键真的能按两半查、{@code fencing_token} 走完一轮 {@code save} 之后没被动过。
 */
class WorkspacePersistenceTest extends AbstractPersistenceTest {

    /** 每个测试一个项目，这样"这个项目下的树"必然只有本测试自己造的。 */
    private static final ProjectId PROJECT_ID =
            ProjectId.of("55555555-5555-5555-5555-555555555555");

    @Autowired
    private WorkspaceRepository worktrees;

    @Test
    @DisplayName("按「谁 + 哪个项目」两半查得到，读出来还是同一个对象")
    void roundTripsThroughRealMySQL() {
        Workspace tree = treeOf(OWNER, "c0ffee1");

        worktrees.save(tree);

        assertThat(worktrees.find(tree.workspaceId())).contains(tree);
        // 查的是**复合**主键，所以只给一半必须查不到 —— 少了这半边，
        // "这个人在别项目里的树"会被当成这一棵
        assertThat(worktrees.find(WorkspaceId.of(OWNER, ProjectId.generate()))).isEmpty();
    }

    @Test
    @DisplayName("upsert 的「存在就更新」这一支真的走到了 —— 同步/回滚推进 HEAD 靠的就是它")
    void saveUpdatesAnExistingTree() {
        Workspace tree = treeOf(OWNER, null);
        worktrees.save(tree);

        worktrees.save(tree.withHeadCommit("newhead"));

        assertThat(worktrees.find(tree.workspaceId())).map(Workspace::headCommit)
                .contains("newhead");
    }

    @Test
    @DisplayName("head_commit 可空列真的落成 NULL，不是空串")
    void nullableHeadStaysNull() {
        // 空项目起步（git init 之后一个提交都没有）时这棵树还没有 HEAD。
        // 盯住「NULL 而不是空串」：下游每一处都是按 null 判断"有没有提交过"的
        Workspace tree = treeOf(OWNER, null);

        worktrees.save(tree);

        assertThat(rawColumn(tree.workspaceId(), "head_commit", String.class)).isNull();
    }

    @Test
    @DisplayName("【安全约束】save 走完一轮，fencing_token 一动没动")
    void saveNeverClobbersTheFencingToken() {
        // 这是整个 WorkspaceRow 设计成立与否的那一条：
        // 如果有人「顺手」把 fencing_token 加进 save 语句，它就会被写回旧值，
        // 于是持有过期租约的僵尸写入者又能落数据 —— 而且不会有任何报错。
        Workspace tree = treeOf(OWNER, "c0ffee1");
        worktrees.save(tree);
        WorkspaceId id = tree.workspaceId();
        jdbc.update("UPDATE workspace SET fencing_token = 7 WHERE owner_id = ? AND project_id = ?",
                id.ownerId().value(), id.projectId().value());

        worktrees.save(tree.withHeadCommit("别的"));

        assertThat(rawColumn(id, "fencing_token", Long.class)).isEqualTo(7L);
    }

    @Test
    @DisplayName("findByProject 拿到项目下所有人的树 —— 会话列表靠它一次取回，不做 N+1")
    void findByProjectReturnsEveryonesTree() {
        Workspace mine = treeOf(OWNER, "aaa");
        Workspace yours = treeOf(ALICE, "bbb");
        worktrees.save(mine);
        worktrees.save(yours);

        assertThat(worktrees.findByProject(PROJECT_ID))
                .extracting(Workspace::workspaceId)
                .containsExactlyInAnyOrder(mine.workspaceId(), yours.workspaceId());
    }

    // ------------------------------------------------------------------

    private static Workspace treeOf(UserId owner, String headCommit) {
        WorkspaceId id = WorkspaceId.of(owner, PROJECT_ID);
        return new Workspace(id, Path.of("D:/ws").resolve(id.value().replace(':', '-')),
                "workspace/" + owner.value(), headCommit);
    }

    /** 绕过仓储直接读某一列 —— 仓储有意不暴露 fencing_token。 */
    private <T> T rawColumn(WorkspaceId id, String column, Class<T> type) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM workspace WHERE owner_id = ? AND project_id = ?",
                type, id.ownerId().value(), id.projectId().value());
    }
}
