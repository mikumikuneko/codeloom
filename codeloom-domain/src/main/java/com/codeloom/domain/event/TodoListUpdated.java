package com.codeloom.domain.event;

import java.util.List;
import java.util.Objects;

/**
 * 任务清单被写了一遍 —— **整份替换**，不是增删单条。
 *
 * <h2>为什么是全量替换</h2>
 * 写它的是模型。让它算 diff（"把第 2 条标完成、删掉第 4 条"）比让它把整份重写一遍
 * 容易错得多，而整份重写天然幂等 —— 重试一次还是那份清单，不会变成删了两遍。
 *
 * <h2>为什么它是一条事件，而不是"某个工具的一句输出"</h2>
 * 工具结果会随上下文压缩被清掉（见 {@link ToolResultsCleared}），**计划就此消失** ——
 * 而这正是长任务里最不该丢的东西。写成事件不会：投影时它会**重新注入**，
 * 于是压缩把前面的对话浓缩掉之后，清单还在模型眼前。
 *
 * <p>顺带白得三件事：
 * <ul>
 *   <li>回滚时清单跟着一起退（退到"第 3 步还没做"那一刻，清单也得退回去）；</li>
 *   <li>断线重连、换台机器、重启进程，看到的都是同一份；</li>
 *   <li>**协作的另一个人也看得见它打算怎么做** —— 这是"两个人各自一个 agent"才有的收益。</li>
 * </ul>
 *
 * @param items 完整的清单，**顺序有意义**（模型按它排步骤）；空列表 = 清单已清空
 */
public record TodoListUpdated(List<Item> items) implements PersistentEvent {

    public TodoListUpdated {
        Objects.requireNonNull(items, "items");
        // 拷一份：record 里的 List 默认是可变的，不拷的话调用方事后能改掉这条"已落库的事实"
        items = List.copyOf(items);
        for (Item item : items) {
            Objects.requireNonNull(item, "清单里不能有 null 这一条");
        }
    }

    /** 清单里的一条。 */
    public record Item(String content, State state) {

        public Item {
            Objects.requireNonNull(state, "state");
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("任务内容不能为空");
            }
        }
    }

    /**
     * 三态。**和模型在工具参数里写的字符串一一对应**（见 {@code todo_write} 的参数 schema：
     * pending / in_progress / completed）—— 枚举名和那三个词映射一次，别在这边改名。
     */
    public enum State { PENDING, IN_PROGRESS, COMPLETED }
}
