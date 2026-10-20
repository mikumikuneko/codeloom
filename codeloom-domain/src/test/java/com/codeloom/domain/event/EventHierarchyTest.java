package com.codeloom.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 sealed 层级与 {@link EventType} 枚举的一一对应。
 *
 * <p>{@code EventType} 是持久化格式（数据库字符串、SSE 判别字段），sealed 层级是编译期
 * 结构；编译器拦不住忘记往 EventType 加一项，这个测试把错误提前到构建阶段。
 */
class EventHierarchyTest {

    @Test
    @DisplayName("每个事件 record 都能映射到一个 EventType，且不重不漏")
    void everyRecordHasAMatchingEventType() {
        Set<EventType> derivedFromRecords = leafEventClasses()
                .map(c -> EventType.valueOf(toUpperSnake(c.getSimpleName())))
                .collect(Collectors.toSet());

        assertThat(derivedFromRecords).containsExactlyInAnyOrder(EventType.values());
    }

    @Test
    @DisplayName("顶层只有落库/易失两个分支")
    void topLevelSplitIsExactlyTwoBranches() {
        assertThat(Event.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(PersistentEvent.class, EphemeralEvent.class);
    }

    @Test
    @DisplayName("易失事件的名单是封闭的 —— 走实时通道的必须在这里显式列出，不能悄悄多一个")
    void ephemeralEventsAreExplicitlyListed() {
        // 断言具体名单而不是个数：易失意味着"不落库、不补发"，只有极少数事件配得上，
        // 每多一个都该在这里被看见；"只有 N 个"会随正常改动失败
        assertThat(EphemeralEvent.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(AssistantDelta.class, ReasoningDelta.class);
    }

    @Test
    @DisplayName("两个分支的叶子类型合起来正好等于 EventType 的全部取值")
    void branchesCoverEveryEventType() {
        // 不断言具体个数：那个数字每加一个事件类型就要改一次，而"改了没"上面那条
        // 已经用集合守住了；会随正常改动失败的断言最后只会被顺手改掉
        assertThat(leafEventClasses()).hasSize(EventType.values().length);
        assertThat(PersistentEvent.class.getPermittedSubclasses()).isNotEmpty();
    }

    /** 两个 sealed 分支的叶子类型合起来 = 全部具体事件类型。 */
    private static Stream<Class<?>> leafEventClasses() {
        return Stream.concat(
                Arrays.stream(PersistentEvent.class.getPermittedSubclasses()),
                Arrays.stream(EphemeralEvent.class.getPermittedSubclasses()));
    }

    /** SessionStateChanged → SESSION_STATE_CHANGED */
    private static String toUpperSnake(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }
}
