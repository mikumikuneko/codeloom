package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把「写在 SQL 里的约定」也变成测试 —— 守的是那张表上最要紧的一列。
 *
 * <p>这些断言看着像在检查字符串，实际上它们守的是两个**改动起来毫无声响**的错误：
 * 前者会让 fencing token 被普通写入抹掉（僵尸写入者于是又能落数据），
 * 后者会在表加列时悄悄改变结果集。两种都不会编译报错、不会测试失败、不会在日志里留痕，
 * 所以只能靠这里挡住。
 *
 * <p><strong>为什么这些断言在这张表上：</strong>锁本身锁的就是一棵树，
 * 号的键必须和锁的键是同一个。见 {@code WorkspaceFence} 的类注释里那个反例。
 */
class WorkspaceMapperSqlTest {

    @Test
    @DisplayName("【安全约束】save 语句绝不能出现 fencing_token")
    void saveNeverTouchesTheFence() throws Exception {
        Method save = WorkspaceMapper.class.getMethod("save", WorkspaceRow.class);

        String sql = String.join("\n", save.getAnnotation(Insert.class).value());

        // 写进去就会把它写回旧值 —— 那正是 fence 失效、僵尸写入者被放行的那个瞬间
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
