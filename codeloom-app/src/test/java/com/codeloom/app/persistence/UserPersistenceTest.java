package com.codeloom.app.persistence;

import com.codeloom.app.support.TestUsers;
import com.codeloom.app.support.AbstractPersistenceTest;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code user} 表的真库测试。
 *
 * <p>账号上有唯一索引，所以这里每个测试都用随机账号 —— 开发库里可能已经有人手工
 * 注册过账号，撞名会让测试莫名其妙地红，而那不是被测代码的问题。
 */
class UserPersistenceTest extends AbstractPersistenceTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-09-25T10:00:00.500Z");
    private static final String BCrypt_HASH = TestUsers.PASSWORD_HASH;

    @Autowired
    private UserRepository users;

    @Test
    @DisplayName("用户往返，密码哈希跟着一起回来")
    void roundTripsThroughRealMySQL() {
        User user = register("Alice");

        users.save(user);

        assertThat(users.findById(user.id())).contains(user);
        assertThat(users.findByUsername(user.username())).contains(user);
    }

    @Test
    @DisplayName("existsByUsername 只用一次 COUNT，返回的是布尔语义")
    void existsByUsername() {
        User user = register("Bob");

        assertThat(users.existsByUsername(user.username())).isFalse();
        users.save(user);
        assertThat(users.existsByUsername(user.username())).isTrue();
    }

    @Test
    @DisplayName("【真库】改名改密码不会顺手把 created_at 一起改写")
    void saveDoesNotRewriteCreatedAt() {
        // created_at 记的是「什么时候注册的」，不是「这一行最后一次被写是什么时候」。
        // 所以它不在 upsert 的更新列表里 —— 这条测试就是钉住这一点的。
        User user = register("Carol");
        users.save(user);

        String renamedTo = unique("Carol 改了个名");
        User renamed = new User(user.id(), user.username(), BCrypt_HASH + "NEW",
                renamedTo, Instant.parse("2027-01-01T00:00:00Z"));
        users.save(renamed);

        User reloaded = users.findById(user.id()).orElseThrow();
        assertThat(reloaded.displayName()).isEqualTo(renamedTo);
        assertThat(reloaded.passwordHash()).isEqualTo(BCrypt_HASH + "NEW");
        assertThat(reloaded.createdAt()).isEqualTo(REGISTERED_AT);
    }

    @Test
    @DisplayName("【真库】用户名重复插不进去 —— 唯一键在建表时就定死了，不靠调用方自觉")
    void duplicateDisplayNameIsRejectedByTheDatabase() {
        String name = unique("重名");
        users.save(new User(UserId.generate(), "user-" + UUID.randomUUID(), BCrypt_HASH,
                name, REGISTERED_AT));

        User clash = new User(UserId.generate(), "user-" + UUID.randomUUID(), BCrypt_HASH,
                name, REGISTERED_AT);

        // 抛出去，而不是**安静地改掉别人的那一行** —— 那正是 ON DUPLICATE KEY UPDATE
        // 在有两个唯一键的表上会干的事（见 UserMapper.update 那段）
        assertThatThrownBy(() -> users.save(clash)).isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------

    /**
     * 造一个用户。**账号和用户名都带随机尾巴** —— 两条唯一键，而开发库里可能还留着
     * 上一次跑留下的同名行（清漏了不该让下一次测试莫名地红）。
     */
    private static User register(String displayName) {
        return User.register("user-" + UUID.randomUUID(), BCrypt_HASH,
                unique(displayName), REGISTERED_AT);
    }

    /** 可读的前缀 + 随机尾巴：认得出是谁，又不会撞上别的行。 */
    private static String unique(String readable) {
        return readable + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
