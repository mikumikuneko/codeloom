package com.codeloom.app.merge;

import java.util.List;

/**
 * 合并遇到冲突并停下，等待人裁决。
 *
 * <p>单独一个异常是因为它要携带冲突文件清单：客户端收到 409 后立刻需要知道要裁决
 * 哪些文件，而 {@code ResponseStatusException} 只能装状态码和一句 reason。
 */
public class ConflictPendingException extends RuntimeException {

    private final transient List<String> conflictingPaths;

    /**
     * @param conflictingPaths 冲突文件路径；构造时做不可变复制
     */
    public ConflictPendingException(List<String> conflictingPaths) {
        super("合并有冲突，需要人工裁决：" + String.join("、", conflictingPaths));
        this.conflictingPaths = List.copyOf(conflictingPaths);
    }

    /** @return 冲突文件路径，不可变 */
    public List<String> conflictingPaths() {
        return conflictingPaths;
    }
}
