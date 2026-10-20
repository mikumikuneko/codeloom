package com.codeloom.domain.event;

import java.util.List;
import java.util.Objects;

/**
 * 任务清单被整份写了一遍，不是增删单条。整份替换天然幂等，重试一次还是那份清单；让模型算
 * diff 更容易错。
 *
 * <p>落成事件而不是工具输出：工具结果会被压缩清掉（见 {@link ToolResultsCleared}），计划不能
 * 消失。投影时每次变化追加一份快照（同一轮里旧的留在历史里，最新的在最后，各带时刻和"以最后
 * 一份为准"；空清单追加"已经清空"）；压缩时水位线之前由摘要代表，只把最新一份带到之后。于是
 * 回滚、重连、重启和协作者看到的都是同一份。投影行为在 {@code ContextAssembler}，见
 * {@code docs/decisions/feature/2026-10-06-todo-list-is-an-event} 与
 * {@code docs/decisions/feature/2026-10-18-clock-and-todo-live-in-the-projection}。
 *
 * @param items 完整的清单，顺序有意义（模型按它排步骤）；空列表 = 清单已清空
 */
public record TodoListUpdated(List<Item> items) implements PersistentEvent {

    public TodoListUpdated {
        Objects.requireNonNull(items, "items");
        // 拷一份：传进来的 List 可能是可变的，不拷的话调用方事后能改掉这条"已落库的事实"
        items = List.copyOf(items);
        for (Item item : items) {
            Objects.requireNonNull(item, "清单里不能有 null 这一条");
        }
    }

    /**
     * 清单里的一条。
     *
     * @param content 任务内容
     * @param state   三态之一
     */
    public record Item(String content, State state) {

        public Item {
            Objects.requireNonNull(state, "state");
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("任务内容不能为空");
            }
        }
    }

    /**
     * 三态，与 {@code todo_write} 参数里的字符串一一对应（pending / in_progress / completed）。
     * 映射只做一次，别在这边改名。
     */
    public enum State { PENDING, IN_PROGRESS, COMPLETED }
}
