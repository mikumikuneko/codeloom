package com.codeloom.app.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 聊天室的端点注册。
 *
 * <h2>刻意没有 {@code setAllowedOrigins("*")}</h2>
 * 开发时用 WebSocket 连另一个端口的页面很常见，顺手放开跨源也是很常见的做法。
 * 但跨源是**安全决定**，它属于 Security 那一步 —— 那时候才知道前端从哪来、
 * 该不该带凭据。在这里放开等于用一个"先让它跑起来"的默认值，
 * 替后面那次真正的决定把答案定死了。默认的同源限制在这里是**安全的选择**，不是遗漏。
 *
 * <p>而这一条**不需要为前端开发破例**：dev server 会把 {@code /ws/project-chat}
 * 代理到后端（要开 {@code ws: true}），浏览器看到的仍然是**同源**。
 * 也就是说"开发时怎么办"这个问题已经由代理解决了，不必动这里的默认值。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatWebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;

    public ChatWebSocketConfig(ChatWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, ChatWebSocketHandler.PATH);
    }
}
