package com.codeloom.domain.port;

import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;

import java.util.Optional;

/**
 * 用户仓储。实现在 {@code codeloom-app} —— 认证是它的职责。
 *
 * <p>注册登录这套结构是照建的，不是演示用的简化 —— 加只读演示账号之类的也不需要重构。
 */
public interface UserRepository {

    void save(User user);

    Optional<User> findById(UserId id);

    /** 登录用。账号全局唯一。 */
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 注册用。**用户名也是唯一的** —— 两个同名的人会让界面答不出"这是谁"。 */
    boolean existsByDisplayName(String displayName);
}
