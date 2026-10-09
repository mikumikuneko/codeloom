package com.codeloom.domain.user;

import java.time.Instant;
import java.util.Objects;

/**
 * 平台用户。
 *
 * @param passwordHash 已哈希的密码（BCrypt）。这个字段本身允许存在于领域模型里 ——
 *                     它是不可逆的哈希，不是密钥；但 {@code toString()} 仍然要小心，
 *                     所以下面覆盖掉了它。
 */
public record User(UserId id,
                   String username,
                   String passwordHash,
                   String displayName,
                   Instant createdAt) {

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(createdAt, "createdAt");

        if (username.isBlank()) {
            throw new IllegalArgumentException("账号不能为空");
        }
        if (passwordHash.isBlank()) {
            throw new IllegalArgumentException("密码哈希不能为空");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = username;
        }
    }

    public static User register(String username, String passwordHash, String displayName, Instant now) {
        return new User(UserId.generate(), username, passwordHash, displayName, now);
    }

    /** 手写 toString 是为了不把密码哈希带进日志。 */
    @Override
    public String toString() {
        return "User[id=" + id + ", username=" + username + "]";
    }
}
