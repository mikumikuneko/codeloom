package com.codeloom.app.support;

import com.codeloom.domain.user.UserId;

/**
 * 测试使用的固定用户 id，以及一份可直接写入数据库的密码哈希。
 *
 * <p>id 必须固定：按署名核对提交之类的断言需要预先把"这笔提交是谁做的"写进断言，
 * 随机 id 做不到。字面量散落各处的真正问题是修改时会漏，漏掉的测试仍然通过，
 * 只是它验证的已不是同一件事。
 *
 * <p>id 的形状（{@code aaaa0000-…}）刻意一眼可见是测试数据，在共享开发库中不会与真实数据混淆。
 */
public final class TestUsers {

    public static final UserId OWNER = UserId.of("aaaa0000-0000-0000-0000-000000000001");
    public static final UserId ALICE = UserId.of("aaaa0000-0000-0000-0000-000000000002");
    public static final UserId BOB = UserId.of("aaaa0000-0000-0000-0000-000000000003");
    public static final UserId CAROL = UserId.of("aaaa0000-0000-0000-0000-000000000004");

    /**
     * 语法上合法的 BCrypt 哈希，直接作为 {@code user.password_hash} 写入。
     *
     * <p>这些测试从不校验密码（登录有独立测试），只需一个看起来像哈希且不是明文的值。
     * 写成常量而非每次计算，因为 BCrypt 故意很慢，为一个用不到的哈希多花几十毫秒不值得。
     */
    public static final String PASSWORD_HASH =
            "$2a$10$abcdefghijklmnopqrstuvwxzyABCDEFGHIJKLMNOPQRSTUV";

    private TestUsers() {
    }
}
