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
 * 把 HTTP 请求上的身份换成领域里的 {@link User}。
 *
 * <p>由控制器显式调用并把结果传下去，而不是让服务层读 {@code SecurityContextHolder}：
 * 后者会让服务层离不开一个已认证的请求，无法在普通单测里调用，也无法“以另一个人的身份”
 * 调用。代价是每个控制器第一行写一次。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    /**
     * 取当前用户。
     *
     * <p>正常流程走不到 401：安全链已经把这几个端点之外的一切要求登录了。
     * 这个判断只是不让“没登录”表现为空指针。
     *
     * @param principal 请求上的身份，可以为 null
     * @return 对应用户
     * @throws ResponseStatusException 401：没有身份，或会话指向的用户已不存在
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
     * <p>给“登录可选”的端点用（例如邀请链接预览）：没登录是正常访客，不是错误；
     * 已登录的人又该被认出来。会话指向已删除的用户，或 Principal 是匿名过滤器塞进来的
     * {@code anonymousUser} 时，同样返回空。
     *
     * @param principal 请求上的身份，可以为 null
     * @return 对应用户；认不出来时为空
     */
    public Optional<User> find(Principal principal) {
        if (principal == null) {
            return Optional.empty();
        }
        return users.findByUsername(principal.getName());
    }
}
