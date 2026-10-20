package com.codeloom.agent.context;

import com.codeloom.agent.tool.ToolJson;
import com.codeloom.agent.tool.ToolNames;
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
 * 这条对话里 {@code read_file} 读过哪些文件（工作区相对路径），以及每条路径最早被读到的 seq。
 *
 * <p>它是“读过才许覆盖”那道门的输入。记最早 seq 而不是集合，是因为回滚按 seq 砍：
 * 切点之后才读到的路径作废，切点前读过的仍算读过；同一文件读两次、切点落在中间时保留前一次。
 * 回滚退的是同一棵树上的代码，所以切点之前读到的内容仍成立，只作废切点之后那一段。
 */
final class ReadPathTracker implements Derived {

    /** 路径 → 最早读到它的 seq。 */
    private final Map<String, Long> earliestRead = new LinkedHashMap<>();

    @Override
    public void accept(StoredEvent stored) {
        if (stored.event() instanceof SessionRewound rewound) {
            long cut = Derived.cutOf(rewound);
            earliestRead.values().removeIf(seq -> seq > cut);
            return;
        }
        // 调用 id 由 PendingCallTracker 负责，这里只关心工具名和参数。
        if (stored.event() instanceof ToolCallRequested requested
                && ToolNames.READ_FILE.equals(requested.toolName())) {
            readPathOf(requested.argumentsJson())
                    .ifPresent(path -> earliestRead.merge(path, stored.seq(), Math::min));
        }
    }

    /** 这条对话里读过的文件；返回快照。 */
    Set<String> paths() {
        return Set.copyOf(earliestRead.keySet());
    }

    @Override
    public void clear() {
        earliestRead.clear();
    }

    /**
     * 一次调用参数里的文件路径；参数不合法或没有 path 时当作这次没读过。
     * 键名与工具 schema 共用 {@link ToolNames#PATH_ARG}，避免改名后静默漏记。
     *
     * @param argumentsJson 工具调用的原始参数
     * @return 工作区相对路径；取不到就是空
     */
    private static Optional<String> readPathOf(String argumentsJson) {
        try {
            String path = ToolJson.MAPPER.readTree(argumentsJson).path(ToolNames.PATH_ARG).asText("");
            return path.isBlank() ? Optional.empty() : Optional.of(path);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
