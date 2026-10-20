package com.codeloom.app.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 聊天室的端点注册。
 *
 * <p>刻意没有 {@code setAllowedOrigins("*")}：跨源是安全决定，属于 Security 那一步，
 * 在那里才知道前端从哪来、该不该带凭据。默认的同源限制是安全的选择，不是遗漏。
 * 前端开发也不需要破例：dev server 把 {@code /ws/project-chat} 代理到后端
 * （需开启 {@code ws: true}），浏览器看到的仍是同源。
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
