package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把「写在 SQL 里的约定」也变成测试。
 *
 * <p>剩下的这条断言看着像在检查字符串，实际上守的是一个**改动起来毫无声响**的错误：
 * 表加一列，{@code SELECT *} 的结果集就变了，而映射代码会按旧假设继续跑 ——
 * 不编译报错、不测试失败、不在日志里留痕，所以只能靠这里挡住。
 *
 * <p>读注解里的 SQL 是可行的，因为 {@code @Select} 都是运行时保留。
 *
 * <p>"save 绝不能出现 fencing_token"那条断言随该列一起搬到了 {@code workspace} 表上，
 * 现在住在 {@link WorkspaceMapperSqlTest}。
 */
class SessionMapperSqlTest {

    @Test
    @DisplayName("不用 SELECT *：列清单必须显式写出来")
    void selectsNeverUseStar() {
        for (Method method : SessionMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null) {
                continue;
            }
            assertThat(String.join(" ", select.value()))
                    .as("SessionMapper.%s 不该用 SELECT *", method.getName())
                    .doesNotContain("*");
        }
    }

    @Test
    @DisplayName("会话行上不该再出现分支、worktree 路径和 HEAD —— 那三样是工作区的")
    void sessionNoLongerCarriesWorkspaceColumns() {
        assertThat(SessionRow.COLUMNS)
                .doesNotContain("branch")
                .doesNotContain("worktree_path")
                .doesNotContain("head_commit");
    }
}
