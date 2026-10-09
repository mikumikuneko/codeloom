package com.codeloom.app.session;

import com.codeloom.domain.port.CommitIdentity;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Component;

/**
 * 「这一笔提交该署谁的名」—— 全项目只有这一个地方回答它。
 *
 * <h2>为什么值得单独一个类</h2>
 * 因为**好几处必须给出同一个答案**，而它们问的是同一件事：这笔提交是谁干的。
 * 打 checkpoint、同步、合并、裁决收尾，四处都要署名；这些提交之后会被混在**同一条分支**
 * 上（同步会把主干的提交原样带进来），所以 git log 里只有署名能分辨"这是谁做的" ——
 * 而那是审计唯一的落点。
 *
 * <p>要是各算各的（比如一处用账号、一处用用户 id），同一批提交就会署出两种名，
 * 而那种错**不会报错**，只会让 git log 里的归属变得不可信。
 *
 * <h2>它为什么**不**挂 {@code @ConditionalOnWebApplication}</h2>
 * 它住在 {@code app.session} 这个包里，但用它的主要是不启 Web 容器的执行器 ——
 * 而"每轮开始前打 checkpoint"是执行器的职责，跟 HTTP 毫无关系。
 * 那条"只服务 HTTP 的 bean 才挂条件"的规则，判据是**谁需要它**，不是它在哪个包
 * （挂了条件之后，所有非 Web 的测试都会因为找不到这个 bean 起不来）。
 */
@Component
public class CommitIdentities {

    private final UserRepository users;

    public CommitIdentities(UserRepository users) {
        this.users = users;
    }

    public CommitIdentity of(UserId userId) {
        return users.findById(userId)
                .map(owner -> CommitIdentity.of(owner.username()))
                .orElseThrow(() -> new IllegalStateException(
                        "要对一个不存在的用户署名：" + userId + "（用户被删了？）"));
    }
}
