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
 * <h2>为什么是「会话 cookie」而不是「Authorization: Bearer」</h2>
 * 这不是偏好，是**浏览器的限制决定的**：
 *
 * <ul>
 *   <li>{@code EventSource}（SSE 用的那个）**不能自定义请求头**。令牌放在
 *       {@code Authorization} 里的话，浏览器根本发不出去。
 *   <li>{@code WebSocket} 的握手同理 —— 浏览器的 WebSocket API 也不让带自定义头。
 * </ul>
 *
 * <p>而 cookie 是浏览器**自动带上**的，这三种传输（普通请求、SSE、WebSocket 握手）
 * 一视同仁。所以选会话 cookie 不是"老派"，是这个项目里唯一能同时覆盖三条通道的做法。
 * 令牌方案要么把 token 塞进查询串（会进日志、进 referrer），要么为每条通道各写一套。
 *
 * <h2>CSRF：靠 SameSite，而不是 token</h2>
 * CSRF 能成立的前提是"浏览器会自动带上凭据"。所以只要**关掉这个前提**，攻击面就没了：
 * 会话 cookie 显式设成 {@code SameSite=Lax}（见 {@code application.yml}），
 * 跨站的请求带上它 —— 也就是说攻击者伪造的 POST 是**匿名**的，没有任何可冒用的权限。
 *
 * <p>为什么不用 CSRF token、以及"同源 SPA + 只收 JSON"这个前提是怎么来的，
 * 见 {@code docs/decisions/architecture/2026-09-24-two-realtime-channels-and-cookie-auth.md}。
 *
 * <p><strong>什么时候必须回来改：</strong>如果哪天加了表单提交（非 JSON 的 POST
 * 不需要预检）、或者前端和 API 分到不同站点（SameSite 就挡不住了）——
 * 那时 CSRF token 是必须的，不能沿用这一条。
 *
 * <h2>为什么整个类只在 servlet Web 应用里生效</h2>
 * {@code SecurityFilterChain} 是个单例 bean，会被**立即**创建，而它的方法参数
 * {@code HttpSecurity} 只在 Web 上下文里才有。不加这个条件的话，
 * 那些不启 Web 容器的测试（比如只测持久化、总线、执行器的）会因为找不到
 * {@code HttpSecurity} 起不来。
 *
 * <p><strong>这是一条要给整个 Web 层遵守的规则</strong>：凡是有 HTTP 才有意义的 bean
 * （控制器、WebSocket 端点注册……）都挂同一个条件。于是那些不启容器的测试拿到的上下文里
 * 干干净净没有 Web 层。漏挂的后果也很直接：控制器会去要一个只在 Web 上下文里存在的 bean，
 * 于是**所有**非 Web 测试一起挂掉。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 注册与登录本身当然不能要求先登录；登出也放开 ——
                        // 它幂等且无害，而"未登录时登出拿到 401"只会让前端多写一个分支
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/logout")
                        .permitAll()
                        // 健康检查给容器探活用，不该要凭据
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // **只有 GET**：点开邀请链接的人可能还没注册，看不到
                        // "谁邀请你加入哪个项目"就无从判断该不该费劲注册。
                        // 安全上不吃亏：那张 token 本身就能换到成员身份，
                        // 它泄露的后果严格大于泄露一个项目名（见 InvitationService.preview）。
                        //
                        // 按方法限死是必须的：写成 "/api/invitations/**" 会把 accept 也放开，
                        // 而那是**真正加入**那一步，它得挂在某个登录用户身上
                        .requestMatchers(HttpMethod.GET, "/api/invitations/*").permitAll()
                        // **`/error` 必须放开，否则每一个错误都会变成 401。**
                        // 抛出异常后容器会 ERROR 转发到 /error 去渲染响应体，而在 Boot 3 里
                        // 安全过滤链**也作用于 ERROR 转发**。不放行的话，一个本该是 409 的
                        // "这个账号已被占用"，客户端拿到的是"未登录"—— 排查时会被带偏很远。
                        // 放开它是安全的：Boot 3 默认不把异常信息写进响应体
                        // （server.error.include-message=never）
                        .requestMatchers("/error").permitAll()
                        // 其余一律要登录。**默认拒绝**，不是默认放行 ——
                        // 新增一个接口忘了想权限时，它默认是关着的
                        .anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // 登录接口是自己写的（JSON 进 JSON 出），不走表单那一套
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(handling -> handling
                        // 未登录 401、已登录但没权限 403 —— 都返回状态码，不重定向
                        // （重定向到登录页是给传统页面应用用的，API 客户端只会一头雾水）
                        //
                        // **但光有状态码不够。** 这一支走的是 Security 的过滤器链，到不了
                        // {@code ApiExceptionHandler}，而前端把人话直接显示出来
                        //（见 api.ts 的 ApiError：detail → title → "请求失败（HTTP …）"）。
                        // 身体空着的时候，用户看到的就是那句协议细节 —— 和那边注释里说的是同一个坑。
                        .authenticationEntryPoint((request, response, authException) -> {
                            boolean loginAttempt =
                                    request.getRequestURI().endsWith("/api/auth/login");
                            writeProblem(mapper, response, HttpStatus.UNAUTHORIZED,
                                    loginAttempt ? "登录失败" : "还没有登录",
                                    // 刻意不说"账号不存在"还是"密码不对"（见 AuthController）
                                    loginAttempt ? "账号或密码不对" : "请先登录");
                        })
                        .accessDeniedHandler((request, response, denied) ->
                                writeProblem(mapper, response, HttpStatus.FORBIDDEN,
                                        "没有权限", "你没有权限做这件事")));
        return http.build();
    }

    /**
     * 按 {@code ApiExceptionHandler} 那份形状写一个错误身体 —— **两边必须一样**：
     * 前端只认这一种形状（`{title, status, detail}`），而过滤器链这一支到不了那个 advice。
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
     * 从用户表读人。
     *
     * <p>{@code AuthenticationManager} 自己会用"能拿到的那个 UserDetailsService" +
     * "能拿到的那个 PasswordEncoder" 组一个 DaoAuthenticationProvider，
     * 所以这里只要给出这两样，校验、加盐、明文比对都由框架负责 ——
     * 自己写一遍 password_hash 比对是这类项目最常见的自伤。
     *
     * <p>刻意不给任何角色：现在没有角色概念（两个用户是对等的协作者）。
     * 加 {@code ROLE_USER} 只是为了让框架有个东西可放，不代表任何业务含义。
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.findByUsername(username)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withUsername(user.username())
                        .password(user.passwordHash())
                        .roles("USER")
                        .build())
                // 用户不存在时也要抛这个异常，而不是返回 null，
                // 更不能说"用户不存在"——那会变成一个可以枚举账号的接口
                .orElseThrow(() -> new UsernameNotFoundException("账号或密码不对"));
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }
}
