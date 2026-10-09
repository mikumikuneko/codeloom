package com.codeloom.app.merge;

import java.util.List;

/**
 * 「合到一半停下了，等着人来裁决」。
 *
 * <h2>为什么单独一个异常，而不是 {@code ResponseStatusException}</h2>
 * 因为它要带一份**数据**出去：冲突文件清单。客户端拿到 409 之后立刻需要知道
 * "要裁决哪些文件"，而 {@code ResponseStatusException} 只装得下一个状态码和一句 reason。
 * 用一个能带负载的异常，客户端一次就把该知道的都拿到了，不用再发第二个请求。
 *
 * <p>清单本身最多也就是几个路径，塞进 409 的响应体里毫无压力。
 */
public class ConflictPendingException extends RuntimeException {

    private final transient List<String> conflictingPaths;

    public ConflictPendingException(List<String> conflictingPaths) {
        super("合并有冲突，需要人工裁决：" + String.join("、", conflictingPaths));
        this.conflictingPaths = List.copyOf(conflictingPaths);
    }

    public List<String> conflictingPaths() {
        return conflictingPaths;
    }
}
