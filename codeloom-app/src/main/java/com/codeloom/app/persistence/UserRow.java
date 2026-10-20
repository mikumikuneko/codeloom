package com.codeloom.app.persistence;

import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;

import java.time.Instant;

/**
 * {@code user} 表的一行。
 *
 * <p>放在 app 模块：认证是它的职责，用户表与 agent、工作区、事件流都无关。
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
