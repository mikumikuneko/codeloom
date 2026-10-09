package com.codeloom.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 sealed 层级与 {@link EventType} 枚举之间的一一对应。
 *
 * <p>存在的理由：{@code EventType} 是**持久化格式**（数据库里的字符串、SSE 帧里的判别字段），
 * 而 sealed 层级是**编译期结构**。新增一个事件类型时，编译器会拦住所有穷尽 switch，
 * 却拦不住「忘了往 EventType 里加一项」—— 那个错误会一直潜伏到运行时第一次序列化才炸。
 * 这个测试把它提前到构建阶段。
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
        // 断言的是**具体名单**，不是"个数"。易失意味着"不落库、不补发"，
        // 那是只有极少数事件才配得上的待遇，所以每多一个都该在这里被看见一次 ——
        // 而"只有 N 个"那种写法会随正常改动失败，最后只会被人顺手改掉（见隔壁那条的注释）
        assertThat(EphemeralEvent.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(AssistantDelta.class, ReasoningDelta.class);
    }

    @Test
    @DisplayName("两个分支的叶子类型合起来正好等于 EventType 的全部取值")
    void branchesCoverEveryEventType() {
        // 刻意**不断言具体个数** —— 那个数字每加一个事件类型就要跟着改一次，
        // 而"改了没"这件事上面那条测试已经在守了（它比的是集合，不是数量）。
        // 一条会随正常改动而失败的断言，最后只会被人顺手改掉，等于没有。
        assertThat(leafEventClasses()).hasSize(EventType.values().length);
        assertThat(PersistentEvent.class.getPermittedSubclasses()).isNotEmpty();
    }

    /** 三个 sealed 接口的叶子类型合起来 = 全部具体事件类型。 */
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
