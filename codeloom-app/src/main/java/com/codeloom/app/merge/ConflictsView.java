package com.codeloom.app.merge;

import java.util.List;

/**
 * 当前等着裁决的冲突。
 *
 * <h2>为什么是个信封而不是一个数组</h2>
 * 因为"**这次是哪个方向的合并**"是**整个合并的属性**，不是每个冲突文件的属性 ——
 * 塞进每个 {@link ConflictView} 里就是把同一条信息复制 N 遍，而那 N 份迟早会不一致。
 *
 * <p>它也不是装饰：界面靠它说清"你现在在做什么"（正在把主干同步进来 / 正在把产出合回主干），
 * 而这两种情况该给的提示完全不同。
 *
 * @param direction 这次合并的方向；**没有待裁决的合并时为 null**（那时候冲突列表是空的）
 * @param conflicts 冲突文件。为空表示当前没有待裁决的合并 —— 不是错误
 */
public record ConflictsView(MergeDirection direction, List<ConflictView> conflicts) {

    public ConflictsView {
        conflicts = List.copyOf(conflicts);
    }

    /** 没有待裁决的合并。 */
    static ConflictsView none() {
        return new ConflictsView(null, List.of());
    }
}
