package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把“不用 SELECT *”这条约定钉在每一个 mapper 上。
 *
 * <p>{@code @Select} 是运行时保留，可以直接读注解。表加一列后 {@code SELECT *} 的
 * 结果集会变化，而映射代码仍按旧假设运行——不编译报错、不测试失败、不留日志。
 *
 * <p>以前这条断言只写在 Session/Workspace 两个测试里；新增 mapper 时很容易漏掉，
 * 所以在这里一次覆盖全部。
 */
class MapperSqlTest {

    static Stream<Class<?>> mappers() {
        return Stream.of(
                ProjectMapper.class,
                ProjectInvitationMapper.class,
                SessionMapper.class,
                TurnRequestMapper.class,
                WorkspaceMapper.class);
    }

    @ParameterizedTest
    @MethodSource("mappers")
    @DisplayName("不用 SELECT *：列清单必须显式写出来")
    void selectsNeverUseStar(Class<?> mapper) {
        for (Method method : mapper.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null) {
                continue;
            }
            assertThat(String.join(" ", select.value()))
                    .as("%s.%s 不该用 SELECT *", mapper.getSimpleName(), method.getName())
                    .doesNotContain("*");
        }
    }
}
