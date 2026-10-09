package com.codeloom.agent.context;

import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TodoListUpdated;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务清单**现在**是什么样 —— 跟着事件走的一个小栈。
 *
 * <h2>为什么单独一个类</h2>
 * 投影那边要用它，而它管的是**一件容易搞错的事**：回滚要把清单一起退回去。
 * 收在一处，才不会在"从头折一遍"和"接着往前折"两条路上各写一份、然后慢慢走样。
 *
 * <h2>它**不**受压缩水位线约束</h2>
 * 别的事件在水位线之前就不送给模型了（由摘要代表），清单不行 ——
 * "压缩之后计划还在"正是它存在的理由（见 {@link TodoListUpdated} 的类注释）。
 */
final class TodoTracker {

    /** 写过的每一份清单和它落在流里的序号。回滚时按序号从尾巴上砍，见 {@link #accept}。 */
    private final List<Entry> written = new ArrayList<>();

    void accept(StoredEvent stored) {
        if (stored.event() instanceof TodoListUpdated updated) {
            written.add(new Entry(stored.seq(), updated));
            return;
        }
        if (stored.event() instanceof SessionRewound rewound) {
            // 退到"第 3 步还没做"那一刻，清单也得跟着退回去 —— 否则模型看见的
            // 是一个还没发生的计划。切点为空（旧数据）时保守清空，
            // 和投影那边"查不到边界就退到开头"是同一个判断
            long cut = rewound.toCheckpointSeq() == null
                    ? Long.MIN_VALUE
                    : rewound.toCheckpointSeq();
            while (!written.isEmpty() && written.getLast().seq() > cut) {
                written.removeLast();
            }
        }
    }

    /** 现在那份清单；一份都没写过（或已经被回滚退光）时为空。 */
    TodoListUpdated current() {
        return written.isEmpty() ? null : written.getLast().updated();
    }

    void clear() {
        written.clear();
    }

    /**
     * 一份清单和它落在流里的序号。
     *
     * <p>一条记录而不是两列平行的表：砍的判据是序号、给出的是清单，一条记录同时给出两者，
     * 就不存在"两张表长度对不上"这种状态。
     */
    private record Entry(long seq, TodoListUpdated updated) {
    }
}
