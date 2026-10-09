package com.codeloom.domain.port;

import java.util.Objects;

/**
 * 一次 git 提交的署名。
 *
 * <h2>为什么要显式传，而不是配在仓库里</h2>
 * git 的 {@code user.name} / {@code user.email} 可以写在仓库配置里，那样整个仓库只有一个身份。
 * 但我们需要的是**每笔提交都能看出是谁的 agent 写的**。
 *
 * <p>所以刻意**不设仓库级身份**，每次提交由上层传入会话所有者的身份。
 * 副产品是：万一某处忘了传，git 会**响亮地失败**（{@code Author identity unknown}），
 * 而不是静默地把提交归到一个叫 codeloom 的假身份上。
 *
 * <p>注意 {@code merge} 也需要它 —— 即使加了 {@code --no-commit}，git 也要写 MERGE_HEAD。
 * 缺身份时 git 会在**冲突检测之前**就以退出码 128 退出。
 *
 * @param name  git 署名。**取自账号，不是用户名** —— 署名要能一直对上"这笔是谁做的"，
 *              而用户名是给人看的、会变；账号是登录凭据，比它稳
 * @param email 邮箱。没有真实邮箱的场合用 {@code <账号>@codeloom.local} 即可
 */
public record CommitIdentity(String name, String email) {

    public CommitIdentity {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
        if (name.isBlank()) {
            throw new IllegalArgumentException("提交者名字不能为空");
        }
        if (email.isBlank()) {
            throw new IllegalArgumentException("提交者邮箱不能为空");
        }
    }

    /** 方便构造：codeloom 本地的假邮箱。 */
    public static CommitIdentity of(String username) {
        return new CommitIdentity(username, username + "@codeloom.local");
    }
}
