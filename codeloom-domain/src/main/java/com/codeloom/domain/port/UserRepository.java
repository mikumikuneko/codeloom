package com.codeloom.domain.port;

import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;

import java.util.Optional;

/**
 * 用户仓储。实现在 {@code codeloom-app} —— 认证是它的职责。
 *
 * <p>注册登录是正式结构，不是演示用的简化：加只读演示账号之类的不需要重构。
 */
public interface UserRepository {

    /**
     * 新建或整体更新用户。
     *
     * @param user 用户
     */
    void save(User user);

    /**
     * 按 id 读用户；不存在就是空。
     *
     * @param id 用户
     * @return 用户
     */
    Optional<User> findById(UserId id);

    /**
     * 登录用。账号全局唯一。
     *
     * @param username 账号
     * @return 用户；不存在时为空
     */
    Optional<User> findByUsername(String username);

    /**
     * 账号是否已被占用。
     *
     * @param username 账号
     * @return 是否存在
     */
    boolean existsByUsername(String username);

    /**
     * 注册用。用户名也是唯一的 —— 两个同名的人会让界面答不出「这是谁」。见
     * {@code docs/decisions/architecture/2026-10-07-display-name-is-unique.md}。
     *
     * @param displayName 用户名
     * @return 是否存在
     */
    boolean existsByDisplayName(String displayName);
}
