package com.codeloom.domain.port;

import java.util.Objects;

/**
 * 一次 git 提交的署名。刻意不设仓库级的 {@code user.name}/{@code user.email}：每笔提交
 * 都要能看出是谁的 agent 写的，所以身份由上层在每次提交时传入会话所有者的；忘了传时
 * git 会明确报错（{@code Author identity unknown}），而不是静默归到一个叫 codeloom 的
 * 假身份上。{@code merge} 也需要它：即使加了 {@code --no-commit}，git 也要写 MERGE_HEAD；
 * 缺身份时 git 会在冲突检测之前以退出码 128 退出。两个字段都不能为 null 或空白。见
 * {@code docs/decisions/architecture/2026-09-24-git-as-a-subprocess-with-per-commit-identity.md}。
 *
 * @param name  git 署名，取自账号而不是用户名 —— 署名要一直能对上「这次提交是谁做的」，
 *              用户名是给人看的、会变，账号是登录凭据，更稳
 * @param email 邮箱；没有真实邮箱时用 {@code 账号@codeloom.local}
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

    /**
     * 方便构造：取账号，拼出 codeloom 本地的假邮箱。
     *
     * @param username 账号
     * @return 署名
     */
    public static CommitIdentity of(String username) {
        return new CommitIdentity(username, username + "@codeloom.local");
    }
}
