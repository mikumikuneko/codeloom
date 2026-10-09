package com.codeloom.app.persistence;

import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link UserRepository} 的持久化实现。
 *
 * <p>这是四个仓储实现里最简单的一个：没有成员集合、没有需要保护不许写的列、
 * 没有生成的键要回填，方法全是「一行进一行出」。
 */
@Repository
public class MyBatisUserRepository implements UserRepository {

    private final UserMapper mapper;

    public MyBatisUserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 有这一行就更新、没有就插一行。
     *
     * <p>分两步而不是用 {@code ON DUPLICATE KEY UPDATE}：{@code user} 表有**两个**唯一键
     *（账号、用户名），而那句 SQL 撞上任一个都会更新那一行 —— 撞在**别人**的用户名上
     * 就会把别人那一行改掉，而不是报错（见 {@link UserMapper#update}）。
     * 这里按 id 判断，撞名就让它抛出去。
     */
    @Override
    public void save(User user) {
        UserRow row = UserRow.of(user);
        if (mapper.update(row) == 0) {
            mapper.insert(row);
        }
    }

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(mapper.findById(id.value())).map(UserRow::toDomain);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(mapper.findByUsername(username)).map(UserRow::toDomain);
    }

    @Override
    public boolean existsByUsername(String username) {
        return mapper.countByUsername(username) > 0;
    }

    @Override
    public boolean existsByDisplayName(String displayName) {
        return mapper.countByDisplayName(displayName) > 0;
    }
}
