package com.codeloom.domain.session;

import java.util.UUID;

/**
 * 会话标识。做成值对象而不是裸 {@code String}，让编译器拦住"把 projectId 传进需要
 * sessionId 的位置"这类错误；代价是仓储映射时多一层拆包。
 */
public record SessionId(String value) {

    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("session id 不能为空");
        }
    }

    /**
     * 从文本构造；空白值会被拒绝。
     *
     * @param value 会话标识文本
     * @return 会话标识
     */
    public static SessionId of(String value) {
        return new SessionId(value);
    }

    /**
     * 新会话的 id。在领域里生成，聚合根才能脱离存储构造、单测不必起数据库；需要全库单调
     * 排序的标识（如 event 的 seq）则相反，由存储层分配。见
     * {@code docs/decisions/architecture/2026-09-24-ids-storage-issued-vs-domain-issued.md}。
     *
     * @return 新的会话标识
     */
    public static SessionId generate() {
        return new SessionId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
