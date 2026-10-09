package com.codeloom.app.support;

import com.codeloom.domain.user.UserId;

/**
 * 测试里那几个固定的用户 id，以及一份可以直接塞进库的密码哈希。
 *
 * <h2>为什么要有这个类</h2>
 * 这几个 id **必须**是固定的：{@code CredentialIdentity} 之类的断言要按署名核对提交，
 * 而随机的 id 会让"这笔提交是谁做的"变得没法预先写进断言。
 *
 * <p>字面量散在各测试里的问题不在于那几十个字符，而在于**改的时候会漏**：
 * 漏掉的那个测试仍然跑绿，只是它验的和别人验的已经不是同一件事了。
 *
 * <p>这些 id 的形状（`aaaa0000-…`）是刻意的：一眼就是测试造的，
 * 在共享的开发库出现时不会和真数据混淆。
 */
public final class TestUsers {

    public static final UserId OWNER = UserId.of("aaaa0000-0000-0000-0000-000000000001");
    public static final UserId ALICE = UserId.of("aaaa0000-0000-0000-0000-000000000002");
    public static final UserId BOB = UserId.of("aaaa0000-0000-0000-0000-000000000003");
    public static final UserId CAROL = UserId.of("aaaa0000-0000-0000-0000-000000000004");

    /**
     * 一个**语法上合法**的 BCrypt 哈希，直接当 {@code user.password_hash} 存进去。
     *
     * <p>这些测试从不校验密码（登录有它自己的测试），只需要一个"看起来像哈希、
     * 且不是明文"的值。写成常量而不是每次现算，是因为 BCrypt 故意很慢 ——
     * 为了一个用不到的哈希让每个测试多花几十毫秒不值得。
     */
    public static final String PASSWORD_HASH =
            "$2a$10$abcdefghijklmnopqrstuvwxzyABCDEFGHIJKLMNOPQRSTUV";

    private TestUsers() {
    }
}
