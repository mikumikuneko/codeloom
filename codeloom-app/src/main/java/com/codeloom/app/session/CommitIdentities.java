package com.codeloom.app.session;

import com.codeloom.domain.port.CommitIdentity;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Component;

/**
 * 提交署名的唯一来源。
 *
 * <p>checkpoint、同步、合并、裁决收尾四处都要署名，而这些提交会混在同一条分支上
 * （同步会把主干的提交原样带进来），git log 里只有署名能分辨提交归属，那是审计的唯一落点。
 * 各处若各算各的（例如一处用账号、一处用用户 id），同一批提交会署出两种名字，且不会报错。
 *
 * <p>不挂 {@code @ConditionalOnWebApplication}：主要使用方是不启 Web 容器的执行器，
 * 它在每轮结束时提交（checkpoint），与 HTTP 无关；挂了条件后非 Web 测试会因找不到该
 * bean 而无法启动。
 */
@Component
public class CommitIdentities {

    private final UserRepository users;

    public CommitIdentities(UserRepository users) {
        this.users = users;
    }

    /**
     * @param userId 提交的归属者
     * @return 以该用户账号为名的提交署名
     * @throws IllegalStateException 用户不存在
     */
    public CommitIdentity of(UserId userId) {
        return users.findById(userId)
                .map(owner -> CommitIdentity.of(owner.username()))
                .orElseThrow(() -> new IllegalStateException(
                        "要对一个不存在的用户署名：" + userId + "（用户被删了？）"));
    }
}
