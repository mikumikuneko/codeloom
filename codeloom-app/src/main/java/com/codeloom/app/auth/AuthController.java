package com.codeloom.app.auth;

import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.Objects;

/**
 * 注册、登录、登出、取当前用户。
 *
 * <h2>为什么自己写登录，而不是用 {@code formLogin()}</h2>
 * {@code formLogin()} 只认 {@code application/x-www-form-urlencoded}，
 * 而这是个全程 JSON 的 API。自己接上 {@link AuthenticationManager} 之后，
 * 校验、加盐、锁定这些仍然全由框架负责 —— 自己写的只有"把认证结果存进会话"这一步，
 * 而那一步恰好是**会话 cookie 认证的落地处**，值得摆在明面上：
 * 不存的话，这次请求里是登录状态，下一个请求就又不认识了。
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {

    /**
     * 会话存的是 {@code SecurityContext}。
     *
     * <p>刻意自己 new 而不是注入：默认那条链用的是
     * {@code DelegatingSecurityContextRepository}（请求属性 + 会话两级），
     * 而写进会话这一级对两边都可见 —— 拿不准容器里到底放了哪个实现时，
     * 用这个明确知道它写到哪儿的，比猜一个 bean 靠谱。
     */
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository users,
                          PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------------

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthUserView register(@RequestBody RegisterRequest request,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
        if (users.existsByUsername(request.username())) {
            // 409 而不是 400：请求本身没问题，是和现有资源冲突了
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这个账号已被占用");
        }
        // 用户名也唯一（见 schema 里那条索引）：两个同名的人会让界面答不出"这是谁"。
        // **空的不查** —— 空值会被领域构造器回落成账号，而那一条上面刚查过
        if (request.displayName() != null && !request.displayName().isBlank()
                && users.existsByDisplayName(request.displayName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这个用户名已被占用");
        }

        User user = User.register(request.username(),
                passwordEncoder.encode(request.password()),
                request.displayName(),
                Instant.now());
        users.save(user);

        // 注册完直接登录：前端少一次往返，也不会留下"注册成功了但没登录"的中间态。
        // 这里走一遍 authenticate 而不是手工造 Authentication —— 它会用刚存进去的哈希
        // 真校验一次，于是"编码器和校验器用的是不是同一套"这件事顺带被验了
        Authentication authenticated = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.username(), request.password()));
        saveContext(authenticated, httpRequest, httpResponse);

        return AuthUserView.of(user);
    }

    @PostMapping("/login")
    public AuthUserView login(@RequestBody LoginRequest request,
                              HttpServletRequest httpRequest,
                              HttpServletResponse httpResponse) {
        // 认证失败会抛 AuthenticationException，由 Security 的异常处理转成 401。
        // **刻意不区分"用户不存在"和"密码不对"** —— 区分开等于给了一个枚举账号的接口
        Authentication authenticated = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.username(), request.password()));
        saveContext(authenticated, httpRequest, httpResponse);

        return AuthUserView.of(requireUser(authenticated.getName()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response,
                       Principal principal) {
        // 用框架那个 handler：它同时清 SecurityContext、失效会话、删 cookie。
        // 自己写这三步很容易漏掉第三步 —— 而漏掉它意味着客户端还留着一个
        // 服务端已经作废的会话 id
        new SecurityContextLogoutHandler().logout(request, response, authenticationOrNull(principal));
    }

    @GetMapping("/me")
    public AuthUserView me(Principal principal) {
        // principal 为 null 时根本到不了这里 —— 上面那条链已经把这几个端点之外的一切
        // 都要求登录了。这个判断只是为了让"没登录"不表现为一个空指针
        return AuthUserView.of(requireUser(Objects.requireNonNull(principal, "principal").getName()));
    }

    // ------------------------------------------------------------------

    private void saveContext(Authentication authentication,
                             HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private static Authentication authenticationOrNull(Principal principal) {
        return principal instanceof Authentication authentication ? authentication : null;
    }

    private User requireUser(String username) {
        return users.findByUsername(username).orElseThrow(() -> new IllegalStateException(
                "已认证的会话指向一个不存在的用户：" + username + "（用户被删了？）"));
    }

    // ------------------------------------------------------------------

    public record RegisterRequest(String username, String password, String displayName) {
    }

    public record LoginRequest(String username, String password) {
    }

    /**
     * 返回给前端的用户视图。
     *
     * <p>**没有 passwordHash**，也永远不该有 —— 领域对象里那个字段是不可逆的哈希，
     * 但它对客户端没有任何用处，出网只是白白扩大泄露面。
     */
    public record AuthUserView(String id, String username, String displayName) {

        static AuthUserView of(User user) {
            return new AuthUserView(user.id().value(), user.username(), user.displayName());
        }
    }
}
