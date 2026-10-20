package com.codeloom.app.merge;

import java.util.List;

/**
 * 当前等着裁决的冲突。
 *
 * <p>方向是整个合并的属性，不属于单个冲突文件；界面靠它区分"正在同步"和"正在合回"，
 * 两种情况应给的提示不同。
 *
 * @param direction 合并方向；没有待裁决的合并时为 null
 * @param conflicts 冲突文件；为空表示当前没有待裁决的合并，不是错误
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
