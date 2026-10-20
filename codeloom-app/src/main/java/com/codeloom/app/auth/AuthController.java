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

/**
 * 注册、登录、登出与当前用户查询。
 *
 * <p>登录自己接 {@link AuthenticationManager}，不用 {@code formLogin()}：后者只认表单编码，
 * 而这是全程 JSON 的 API。校验、加盐、锁定仍由框架负责；自己做的只有把认证结果存进会话 ——
 * 那是会话 cookie 认证的落地处，不存的话这次请求是登录状态，下一个请求就又不认识了。
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {

    /**
     * 会话里存的是 {@code SecurityContext}。
     *
     * <p>刻意自己建而不是注入：容器默认那条链用两级仓库，写进会话这一级对两边都可见，
     * 行为确定。
     */
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    /** principal → 用户只在 {@link CurrentUser} 一处做，所有控制器共用。 */
    private final CurrentUser currentUser;

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository users,
                          PasswordEncoder passwordEncoder,
                          CurrentUser currentUser) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------

    /**
     * 注册并直接登录。
     *
     * @param request      账号、口令与可选用户名；用户名为空时由领域构造器回落到账号
     * @param httpRequest  当前请求，用于把认证结果写进会话
     * @param httpResponse 当前响应，用于写会话 cookie
     * @return 新用户视图，不含口令哈希
     * @throws ResponseStatusException 409：账号或用户名已被占用
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthUserView register(@RequestBody RegisterRequest request,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
        if (users.existsByUsername(request.username())) {
            // 409 而不是 400：请求本身没问题，是和现有资源冲突了
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这个账号已被占用");
        }
        // 用户名也唯一：两个同名的人会让界面答不出“这是谁”。空的不查 ——
        // 它会被领域构造器回落成账号，而账号上面刚查过。
        if (request.displayName() != null && !request.displayName().isBlank()
                && users.existsByDisplayName(request.displayName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这个用户名已被占用");
        }

        User user = User.register(request.username(),
                passwordEncoder.encode(request.password()),
                request.displayName(),
                Instant.now());
        users.save(user);

        // 注册完直接登录：前端少一次往返，也不会留下“注册成功但没登录”的中间态。
        // 走一遍 authenticate 而不是手工造 Authentication，顺带验证编码器与校验器配套。
        Authentication authenticated = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.username(), request.password()));
        saveContext(authenticated, httpRequest, httpResponse);

        return AuthUserView.of(user);
    }

    /**
     * 用账号口令登录。
     *
     * @param request      账号与口令
     * @param httpRequest  当前请求，用于把认证结果写进会话
     * @param httpResponse 当前响应，用于写会话 cookie
     * @return 登录用户视图
     * @throws org.springframework.security.core.AuthenticationException 认证失败；由 Security 转成 401
     */
    @PostMapping("/login")
    public AuthUserView login(@RequestBody LoginRequest request,
                              HttpServletRequest httpRequest,
                              HttpServletResponse httpResponse) {
        // 刻意不区分“用户不存在”和“密码不对”——区分开等于提供一个枚举账号的接口。
        Authentication authenticated = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.username(), request.password()));
        saveContext(authenticated, httpRequest, httpResponse);

        return AuthUserView.of(currentUser.require(authenticated));
    }

    /**
     * 登出并作废当前会话。
     *
     * @param request   当前请求
     * @param response  当前响应
     * @param principal 当前身份，可以为 null
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response,
                       Principal principal) {
        // 用框架的 handler：它清 SecurityContext 并失效会话。
        // 自己写容易漏掉失效会话，客户端会继续拿着一个服务端已作废的会话 id。
        new SecurityContextLogoutHandler().logout(request, response, authenticationOrNull(principal));
    }

    /**
     * 当前登录用户。
     *
     * @param principal 当前身份；能到达这里时通常非 null
     * @return 当前用户视图
     * @throws ResponseStatusException 401：principal 缺失，或会话指向的用户已不存在
     */
    @GetMapping("/me")
    public AuthUserView me(Principal principal) {
        return AuthUserView.of(currentUser.require(principal));
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

    // ------------------------------------------------------------------

    public record RegisterRequest(String username, String password, String displayName) {

        /** 打印时隐去口令。理由与遮法见 {@link ApiKeyController.ConfigureRequest#toString()}。 */
        @Override
        public String toString() {
            return "RegisterRequest[username=" + username + ", password=[已隐去], displayName="
                    + displayName + "]";
        }
    }

    public record LoginRequest(String username, String password) {

        /** 打印时隐去口令。理由与遮法见 {@link ApiKeyController.ConfigureRequest#toString()}。 */
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=[已隐去]]";
        }
    }

    /**
     * 返回给前端的用户视图。
     *
     * <p>没有 {@code passwordHash}，也永远不该有：它对客户端没有用处，出网只会扩大泄露面。
     */
    public record AuthUserView(String id, String username, String displayName) {

        static AuthUserView of(User user) {
            return new AuthUserView(user.id().value(), user.username(), user.displayName());
        }
    }
}
