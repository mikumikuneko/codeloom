package com.codeloom.app.persistence;

import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;

import java.time.Instant;

/**
 * {@code user} 表的一行。
 *
 * <p>放在 {@code codeloom-app} 而不是 domain 或某个能力模块：{@code UserRepository}
 * 的注释已经写明「实现在 codeloom-app —— 认证是它的职责」。用户这张表和 agent、
 * 工作区、事件流都没有关系，跟着认证走。
 */
public record UserRow(String id,
                      String username,
                      String passwordHash,
                      String displayName,
                      Instant createdAt) {

    static final String COLUMNS = """
            id, username, password_hash AS passwordHash,
            display_name AS displayName, created_at AS createdAt
            """;

    static UserRow of(User user) {
        return new UserRow(user.id().value(), user.username(), user.passwordHash(),
                user.displayName(), user.createdAt());
    }

    User toDomain() {
        return new User(UserId.of(id), username, passwordHash, displayName, createdAt);
    }
}
