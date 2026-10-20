package com.codeloom.app.auth;

import com.codeloom.domain.port.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 认证与接口鉴权。
 *
 * <p>用会话 cookie 而不是 {@code Authorization: Bearer}：{@code EventSource} 和浏览器
 * WebSocket 都不能自定义请求头，只有 cookie 能同时覆盖普通请求、SSE、WebSocket 三条通道。
 * 令牌方案只能塞进查询串（会进日志和 referrer）或为每条通道各写一套。
 *
 * <p>CSRF 靠 {@code SameSite=Lax} 而不是 token：跨站请求不带凭据，伪造的 POST 是匿名的。
 * 一旦加入非 JSON 的表单提交，或前端与 API 分到不同站点，这个前提失效，必须改用 CSRF token。
 * 见 {@code docs/decisions/architecture/2026-09-24-two-realtime-channels-and-cookie-auth.md}。
 *
 * <p>整个类只在 servlet Web 应用里生效：{@code SecurityFilterChain} 会被立即创建，
 * 而它的参数 {@code HttpSecurity} 只在 Web 上下文存在。凡是有 HTTP 才有意义的 bean
 * （控制器、WebSocket 端点注册等）都必须挂同一个条件；漏挂会让所有非 Web 测试一起起不来。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    /**
     * 应用的安全过滤链。
     *
     * @param http   安全配置入口
     * @param mapper 用于按 {@code ApiExceptionHandler} 的形状写错误体
     * @return 过滤链
     * @throws Exception 配置构建失败
     */
    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 注册与登录不能要求先登录；登出幂等且无害，放开它，
                        // 免得前端为“未登录时登出”多写一个分支。
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/logout")
                        .permitAll()
                        // 健康检查给容器探活，不需要凭据
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // 只放开 GET：点开邀请链接的人可能还没注册，看不到“谁邀请你加入
                        // 哪个项目”就无从判断该不该注册。那张 token 本身就能换到成员身份，
                        // 泄露它的后果严格大于泄露一个项目名。
                        // 不能写成 "/api/invitations/**"：那会把 accept 也放开，而 accept
                        // 是真正加入那一步，必须挂在已登录用户身上。
                        .requestMatchers(HttpMethod.GET, "/api/invitations/*").permitAll()
                        // /error 必须放开，否则每个错误都会变成 401：容器把异常 ERROR 转发
                        // 到 /error 渲染响应体，而安全过滤链也作用于该转发。
                        // 放开是安全的：Boot 3 默认不把异常信息写进响应体。
                        .requestMatchers("/error").permitAll()
                        // 其余一律要登录。默认拒绝，新增接口忘了想权限时它默认是关的。
                        .anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // 登录接口自己写（JSON 进出），不走表单
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(handling -> handling
                        // 未登录 401、已登录但无权限 403，都返回状态码，不重定向。
                        // 这一支到不了 ApiExceptionHandler，而前端会直接显示 detail，
                        // 所以身体必须有人话；空着时用户看到的是“请求失败（HTTP 401）”。
                        .authenticationEntryPoint((request, response, authException) -> {
                            boolean loginAttempt =
                                    request.getRequestURI().endsWith("/api/auth/login");
                            writeProblem(mapper, response, HttpStatus.UNAUTHORIZED,
                                    loginAttempt ? "登录失败" : "还没有登录",
                                    // 不区分“账号不存在”和“密码不对”（见 AuthController）
                                    loginAttempt ? "账号或密码不对" : "请先登录");
                        })
                        .accessDeniedHandler((request, response, denied) ->
                                writeProblem(mapper, response, HttpStatus.FORBIDDEN,
                                        "没有权限", "你没有权限做这件事")));
        return http.build();
    }

    /**
     * 按 {@code ApiExceptionHandler} 的形状写错误体：前端只认
     * {@code {title, status, detail}}，而过滤器链这一支到不了那个 advice。
     */
    private static void writeProblem(ObjectMapper mapper, HttpServletResponse response,
                                     HttpStatus status, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        mapper.writeValue(response.getOutputStream(), body);
    }

    /** BCrypt。用户表里的 {@code password_hash} 存的就是它。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 从用户表读人，供 {@code AuthenticationManager} 组装认证提供者。
     *
     * <p>框架会用这个 {@link UserDetailsService} 与 {@link PasswordEncoder} 做校验、
     * 加盐和比对，不要自己写 password_hash 比对。
     *
     * <p>刻意不给业务角色：两个用户是对等的协作者。{@code ROLE_USER} 只是框架要求的
     * 占位，不代表任何业务含义。
     *
     * @param users 用户仓储
     * @return 按账号查用户的 {@link UserDetailsService}
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.findByUsername(username)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withUsername(user.username())
                        .password(user.passwordHash())
                        .roles("USER")
                        .build())
                // 用户不存在也抛这个异常，不返回 null，更不说“用户不存在”——
                // 那会变成一个可以枚举账号的接口。
                .orElseThrow(() -> new UsernameNotFoundException("账号或密码不对"));
    }

    /**
     * 暴露框架按 {@link UserDetailsService} 与 {@link PasswordEncoder} 组装的认证管理器。
     *
     * @param configuration 认证配置
     * @return 认证管理器
     * @throws Exception 构建失败
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }
}
