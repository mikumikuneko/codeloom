package com.codeloom.domain.session;

import java.util.UUID;

/**
 * 会话标识。
 *
 * <p>做成值对象而不是裸 {@code String}，是为了让编译器拦住「把 projectId 传进
 * 需要 sessionId 的位置」这类错误 —— 三个 String 参数挨在一起时，那是最难查的
 * 一类 bug。代价是仓储映射时多一层拆包。
 */
public record SessionId(String value) {

    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("session id 不能为空");
        }
    }

    public static SessionId of(String value) {
        return new SessionId(value);
    }

    /**
     * 新会话的 id。在领域里生成而不是等数据库分配，这样聚合根脱离存储就能构造完整 ——
     * 单测时不必起数据库。
     *
     * <p>event 的 seq 不适用这条规则：它必须**全库单调**，那是存储层的自增主键才能保证的。
     */
    public static SessionId generate() {
        return new SessionId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
