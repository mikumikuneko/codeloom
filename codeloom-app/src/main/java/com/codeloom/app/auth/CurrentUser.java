package com.codeloom.app.auth;

import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Optional;

/**
 * 把"HTTP 请求上的那个身份"换成领域里的 {@link User}。
 *
 * <h2>为什么让控制器显式把它传下去，而不是到处读 SecurityContextHolder</h2>
 * 读全局上下文看起来省事，代价是**服务层的方法从此离不开一个已认证的请求** ——
 * 它没法在一个普通单测里被调用，也没法被"以另一个人的身份"调用（比如管理操作、
 * 或者将来的定时任务）。把 {@code UserId} 当参数传下去，那些都还是可能的。
 *
 * <p>代价是每个控制器第一行都要写一次。这个代价是看得见的，而前一种的代价
 * 要等到某个功能做不了的时候才发现。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    /**
     * @throws ResponseStatusException 没有身份时 401。正常情况下走不到 —— 安全链已经
     *                                 把这几个端点之外的一切都要求登录了，
     *                                 这里只是不让"没登录"表现为一个空指针
     */
    public User require(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return users.findByUsername(principal.getName()).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "会话指向的用户不存在"));
    }

    /**
     * 有身份就给出这个人，没有就空。
     *
     * <p>**给"登录可选"的端点用**（比如邀请链接的预览）。
     * 那种端点不能用 {@link #require}：没登录是一个正常访客，不是错误；可是登录着的人
     * 又该被认出来（他是不是已经在项目里、这张链接是不是他自己发的）。
     *
     * <p>认不出来也当访客：会话指向一个已经被删掉的用户（或者匿名过滤器塞进来的
     * {@code anonymousUser}）时，答案都是"你不是这里的人" —— 那是对的。
     */
    public Optional<User> find(Principal principal) {
        if (principal == null) {
            return Optional.empty();
        }
        return users.findByUsername(principal.getName());
    }
}
