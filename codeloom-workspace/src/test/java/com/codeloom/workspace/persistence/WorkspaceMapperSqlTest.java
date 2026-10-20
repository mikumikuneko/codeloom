package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把写在 SQL 里的约定变成测试，重点是 {@code fencing_token}。
 *
 * <p>普通写入抹掉 fence 会让过期租约重新落数据；{@code SELECT *} 会在表加列时悄悄
 * 改变结果集。两者都不会编译报错、测试失败或留下日志，只能在这里挡住。
 *
 * <p>这些断言在这张表上：锁锁的是一棵树，号的键必须与锁的键相同。
 */
class WorkspaceMapperSqlTest {

    @Test
    @DisplayName("【安全约束】save 语句绝不能出现 fencing_token")
    void saveNeverTouchesTheFence() throws Exception {
        Method save = WorkspaceMapper.class.getMethod("save", WorkspaceRow.class);

        String sql = String.join("\n", save.getAnnotation(Insert.class).value());

        // 写进去就会把号写回旧值，导致 fence 失效、过期写入者被放行
        assertThat(sql).doesNotContain("fencing_token");
    }

    @Test
    @DisplayName("读语句也不取 fencing_token：它不是领域状态，谁也不该顺手把它读进内存")
    void fenceIsNotReadEither() {
        assertThat(WorkspaceRow.COLUMNS).doesNotContain("fencing_token");
    }

    @Test
    @DisplayName("不用 SELECT *：列清单必须显式写出来")
    void selectsNeverUseStar() {
        for (Method method : WorkspaceMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null) {
                continue;
            }
            assertThat(String.join(" ", select.value()))
                    .as("WorkspaceMapper.%s 不该用 SELECT *", method.getName())
                    .doesNotContain("*");
        }
    }
}
