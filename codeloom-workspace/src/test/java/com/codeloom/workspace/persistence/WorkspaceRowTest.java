package com.codeloom.workspace.persistence;

import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行 ↔ 领域对象的转换 —— 这一行是**一棵树**的，不是一条会话的。
 *
 * <p>三类字段各有各的考验：{@code head_commit} 可空（空项目起步时就真的是 null），
 * 路径要从字符串还原成 {@link Path}，而主键是两个 char 拼出来的复合键 ——
 * 少拼一半会得到一棵"看起来对、其实属于别人"的树。
 */
class WorkspaceRowTest {

    private static final UserId OWNER = UserId.of("33333333-3333-3333-3333-333333333333");
    private static final ProjectId PROJECT = ProjectId.of("22222222-2222-2222-2222-222222222222");
    private static final WorkspaceId ID = WorkspaceId.of(OWNER, PROJECT);

    /** 绝对路径：{@link Workspace} 的紧凑构造器只接受绝对路径，而这个测试要在
     *  Windows 和 Linux 上都跑得起来，所以不能写死 {@code "D:/..."} 或 {@code "/ws"}。 */
    private static final Path PATH = Path.of(System.getProperty("user.dir")).resolve("ws");

    @Test
    @DisplayName("往返转换不丢字段：路径按字符串进、按 Path 出，其余原样")
    void roundTripPreservesEveryField() {
        Workspace original = new Workspace(ID, PATH, "workspace/" + OWNER.value(), "c0ffee1");

        Workspace back = WorkspaceRow.of(original).toDomain();

        assertThat(back).isEqualTo(original);
        assertThat(back.path()).isInstanceOf(Path.class);
    }

    @Test
    @DisplayName("head_commit 可空：空项目起步时它真的是 null，不是空串")
    void headCommitMayBeNull() {
        // 空项目（git init 之后一个提交都没有）时这棵树还没有 HEAD。
        // 把 null 写成空串的话，下游每一处 null 判断都会静默走偏
        Workspace empty = new Workspace(ID, PATH, "workspace/" + OWNER.value(), null);

        WorkspaceRow row = WorkspaceRow.of(empty);

        assertThat(row.headCommit()).isNull();
        assertThat(row.toDomain().headCommit()).isNull();
        assertThat(row.toDomain()).isEqualTo(empty);
    }

    @Test
    @DisplayName("主键的两个组件都要落库 —— 少了任何一个都会指向别人的树")
    void bothKeyComponentsArePersisted() {
        WorkspaceRow row = WorkspaceRow.of(new Workspace(ID, PATH, "workspace/x", null));

        assertThat(row.ownerId()).isEqualTo(OWNER.value());
        assertThat(row.projectId()).isEqualTo(PROJECT.value());
        assertThat(row.toDomain().workspaceId()).isEqualTo(ID);
    }
}
