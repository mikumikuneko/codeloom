package com.codeloom.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TodoListUpdatedTest {

    private static TodoListUpdated.Item item(String content) {
        return new TodoListUpdated.Item(content, TodoListUpdated.State.PENDING);
    }

    @Test
    @DisplayName("清单是**拷进去的** —— 事件一旦落库，调用方就不能再改它")
    void theListIsCopied() {
        List<TodoListUpdated.Item> mine = new ArrayList<>(List.of(item("跑测试")));

        TodoListUpdated event = new TodoListUpdated(mine);
        mine.add(item("偷偷加一条"));

        // 不拷的话，一条"已经发生过的事实"能被事后改掉 —— 而事件流是 append-only 的，
        // 那正是这套东西全部可信度的来源
        assertThat(event.items()).hasSize(1);
        assertThatThrownBy(() -> event.items().add(item("再加一条")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("空的清单是合法的 —— 它表示「清空了」")
    void anEmptyListMeansCleared() {
        assertThat(new TodoListUpdated(List.of()).items()).isEmpty();
    }

    @Test
    @DisplayName("内容空白的任务不收 —— 那种条目在界面上就是一行空白，模型也拿它没法")
    void blankContentIsRejected() {
        assertThatThrownBy(() -> new TodoListUpdated.Item("   ", TodoListUpdated.State.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TodoListUpdated.Item(null, TodoListUpdated.State.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("状态不能缺 —— 缺了就成了「这条到底做没做」由读的人自己猜")
    void stateIsRequired() {
        assertThatThrownBy(() -> new TodoListUpdated.Item("跑测试", null))
                .isInstanceOf(NullPointerException.class);
    }
}
