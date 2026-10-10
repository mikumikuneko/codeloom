package com.codeloom.agent.context;

import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolCallRequested;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 这条对话里 {@code read_file} 读过哪些文件（工作区相对路径）。
 *
 * <h2>它拿什么用</h2>
 * "读过才许覆盖"那条规则就是拿它重建的：一个文件的改动权限来自**这条对话里它被读过**，
 * 而那是历史里的既成事实。
 *
 * <h2>为什么记的是"最早读到它的那个序号"，而不是一个集合</h2>
 * 因为回滚要按序号砍：一条路径只在切点**之后**才读到过时，它才算没读过。
 * 而同一个文件读两次、切点落在两次之间时，那条路径仍然算读过（前一次读到的内容还在）——
 * 于是每条路径留**最小**那个序号就够，不必把每次读都记下来。
 *
 * <p>为什么按切点砍、而不是回滚就整体清空：回滚把**代码**也退回去了（同一棵树），
 * 所以切点之前读到的内容今天依然成立，切点之后看到的那份代码**已经不存在了** ——
 * 该作废的正是那一段，不是整本账。判据与 {@link TodoTracker} 逐字相同。
 */
final class ReadPathTracker implements Derived {

    /** 路径 → 最早读到它的那条事件的序号。 */
    private final Map<String, Long> earliestRead = new LinkedHashMap<>();

    @Override
    public void accept(StoredEvent stored) {
        if (stored.event() instanceof SessionRewound rewound) {
            long cut = Derived.cutOf(rewound);
            earliestRead.values().removeIf(seq -> seq > cut);
            return;
        }
        // 解构只拿走用得到的两个：调用的 id 是 PendingCallTracker 的事
        if (stored.event() instanceof ToolCallRequested requested
                && "read_file".equals(requested.toolName())) {
            readPathOf(requested.argumentsJson())
                    .ifPresent(path -> earliestRead.merge(path, stored.seq(), Math::min));
        }
    }

    /** 这条对话里读过的文件。 */
    Set<String> paths() {
        return Set.copyOf(earliestRead.keySet());
    }

    @Override
    public void clear() {
        earliestRead.clear();
    }

    /**
     * 一次调用的参数里它读的是哪个文件 —— 取不到就当这次没读过（不是跳过整本账：
     * 模型偶尔写出不合法的参数，那一次本来就没读成）。
     *
     * <p>{@code path} 这个键名是**硬写的**，得和读文件那个工具的 JSON schema 一致：
     * 那边把参数改名而这里没跟着改，这笔账就静默地不再记。
     */
    private static Optional<String> readPathOf(String argumentsJson) {
        try {
            String path = MAPPER.readTree(argumentsJson).path("path").asText("");
            return path.isBlank() ? Optional.empty() : Optional.of(path);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
}
