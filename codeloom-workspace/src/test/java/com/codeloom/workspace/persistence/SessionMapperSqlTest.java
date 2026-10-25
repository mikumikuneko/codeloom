package com.codeloom.workspace.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把写在 SQL 里的约定变成测试。
 *
 * <p>这些断言守的是无声的错误：表加一列后 {@code SELECT *} 的结果集会变化，而映射代码
 * 仍按旧假设运行 —— 不编译报错、不测试失败、不留日志，只能在这里挡住。{@code @Select}
 * 是运行时保留，可以直接读注解。
 *
 * <p>"save 绝不能出现 fencing_token"的断言随该列移到了
 * {@link WorkspaceMapperSqlTest}。
 */
class SessionMapperSqlTest {

    @Test
    @DisplayName("会话行上不该再出现分支、worktree 路径和 HEAD —— 那三样是工作区的")
    void sessionNoLongerCarriesWorkspaceColumns() {
        assertThat(SessionRow.COLUMNS)
                .doesNotContain("branch")
                .doesNotContain("worktree_path")
                .doesNotContain("head_commit");
    }
}
