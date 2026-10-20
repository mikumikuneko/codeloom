package com.codeloom.app.persistence;

import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link UserRepository} 的持久化实现。
 *
 * <p>方法全是“一行进一行出”：没有成员集合、没有必须保护不许写的列、没有要回填的生成键。
 */
@Repository
public class MyBatisUserRepository implements UserRepository {

    private final UserMapper mapper;

    public MyBatisUserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 有这一行就更新，没有就插入。
     *
     * <p>分两步而不用 {@code ON DUPLICATE KEY UPDATE}：{@code user} 有两个唯一键
     * （账号、用户名），那句 SQL 撞上任一个都会更新那一行 —— 撞在别人的用户名上会
     * 改掉别人的行，而不是报错。这里按 id 判断，撞名让它抛出去。
     *
     * @param user 要保存的用户
     */
    @Override
    public void save(User user) {
        UserRow row = UserRow.of(user);
        if (mapper.update(row) == 0) {
            mapper.insert(row);
        }
    }

    /**
     * @param id 用户 id
     * @return 对应用户；不存在时为空
     */
    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(mapper.findById(id.value())).map(UserRow::toDomain);
    }

    /**
     * @param username 账号
     * @return 对应用户；不存在时为空
     */
    @Override
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(mapper.findByUsername(username)).map(UserRow::toDomain);
    }

    /**
     * @param username 账号
     * @return 是否已存在
     */
    @Override
    public boolean existsByUsername(String username) {
        return mapper.countByUsername(username) > 0;
    }

    /**
     * @param displayName 用户名
     * @return 是否已存在
     */
    @Override
    public boolean existsByDisplayName(String displayName) {
        return mapper.countByDisplayName(displayName) > 0;
    }
}
