package com.codeloom.agent.context;

import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.TodoListUpdated;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务清单**现在**是什么样 —— 跟着事件走的一个小栈。
 *
 * <p>它是一个 {@link Derived}：回滚怎么退、推倒重来时怎么清，都住在这里。
 *
 * <h2>它**不**受压缩水位线约束</h2>
 * 别的事件在水位线之前就不送给模型了（由摘要代表），清单不行 ——
 * "压缩之后计划还在"正是它存在的理由（见 {@link TodoListUpdated} 的类注释）。
 */
final class TodoTracker implements Derived {

    /** 写过的每一份清单和它落在流里的序号。回滚时按序号从尾巴上砍，见 {@link #accept}。 */
    private final List<Entry> written = new ArrayList<>();

    @Override
    public void accept(StoredEvent stored) {
        if (stored.event() instanceof TodoListUpdated updated) {
            written.add(new Entry(stored.seq(), updated));
            return;
        }
        if (stored.event() instanceof SessionRewound rewound) {
            // 退到"第 3 步还没做"那一刻，清单也得跟着退回去 —— 否则模型看见的
            // 是一个还没发生的计划。切点怎么取见 Derived.cutOf
            long cut = Derived.cutOf(rewound);
            while (!written.isEmpty() && written.getLast().seq() > cut) {
                written.removeLast();
            }
        }
    }

    /** 现在那份清单；一份都没写过（或已经被回滚退光）时为空。 */
    TodoListUpdated current() {
        return written.isEmpty() ? null : written.getLast().updated();
    }

    @Override
    public void clear() {
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
