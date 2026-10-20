package com.codeloom.app.chat;

import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import com.codeloom.realtime.chat.ChatInbound;
import com.codeloom.realtime.chat.ChatMessagePayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目内聊天室，纯人类通道，agent 不可见。
 *
 * <p>说话的人取自连接上的已认证身份，{@link ChatInbound} 没有 authorId 字段；
 * 没有身份或没有 {@code projectId} 的连接直接关闭；成员校验走 {@link ProjectAccess}，
 * 非成员与不存在的项目在客户端看来完全一样。
 *
 * <p>广播回发送者自己，不另发 ack，前端只保留一条渲染路径。订阅表只在本实例内存里
 * （项目 → 本实例上的连接）；跨实例依赖消息已落库、客户端刷新。
 *
 * <p>类住在 app 而不是 realtime：成员校验要用 {@link ProjectAccess}，依赖方向是
 * {@code app → realtime}，realtime 够不到它。它也因此必须挂上
 * {@code @ConditionalOnWebApplication}，否则所有非 Web 测试会一起起不来。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /** 端点路径。查询参数带 projectId，见 {@link #PROJECT_ID_PARAM}。 */
    public static final String PATH = "/ws/project-chat";

    /**
     * 用查询参数而不是路径变量：路径变量要从 Spring 塞进 session attributes 的 map 里取，
     * 那是框架的内部约定；查询参数读的是 URI 本身，是我们自己的约定。
     */
    public static final String PROJECT_ID_PARAM = "projectId";

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatMessageRepository messages;
    /** principal → 用户统一走 {@link CurrentUser}，不再自己查库。 */
    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final ObjectMapper mapper;

    /** 项目 → 正在本实例上看着它的连接。连接建立与断开来自不同线程，所以用并发 Set。 */
    private final Map<ProjectId, Set<WebSocketSession>> watchers = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatMessageRepository messages,
                                CurrentUser currentUser,
                                ProjectAccess access,
                                ObjectMapper mapper) {
        this.messages = messages;
        this.currentUser = currentUser;
        this.access = access;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------

    /**
     * 连接建立后校验身份与项目成员资格，通过才登记到本实例的订阅表。
     *
     * <p>登录了不等于能进这个聊天室：{@code projectId} 是调用方写在 URL 里的，
     * 不过 {@link ProjectAccess#requireMember} 的话，任何登录用户填一个 uuid 就能读
     * 别人的聊天记录。拒绝与“项目不存在”都表现为关闭连接，枚举不出东西。
     * 在建立时关闭而不是握手时拦截，是为了让拒绝只有一处判定；代价是浏览器会先
     * {@code onopen} 再 {@code onclose}，前端对两者都只能显示“连不上”。
     *
     * @param session 已建立的连接
     * @throws Exception 关闭连接失败时由框架处理
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        ProjectId projectId = projectIdOf(session);
        UserId author = authorOf(session);
        if (projectId == null || author == null) {
            // 不知道看哪个项目或谁在看的连接没有意义，也不能让它在后续消息里蒙混过关。
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        try {
            access.requireMember(author, projectId);
        } catch (RuntimeException rejected) {
            // 不往上抛：“这不是你的项目”是正常拒绝，用 POLICY_VIOLATION 说清楚，
            // 而不是让它以含混的服务端错误收场。
            log.debug("拒绝一条聊天连接：用户 {} 不是项目 {} 的成员", author, projectId);
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        watchers.computeIfAbsent(projectId, id -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ProjectId projectId = projectIdOf(session);
        if (projectId != null) {
            forget(projectId, session);
        }
    }

    /**
     * 收到一条消息：落库，然后广播给这个项目的所有连接，包括发送者。
     *
     * <p>可见性从父类的 {@code protected} 放宽到 {@code public}，只为了让
     * {@code ChatWebSocketHandlerTest} 能直接喂消息；真握手那一半由
     * {@code ChatWebSocketHandshakeTest} 覆盖。时间由服务端盖，不用客户端给的。
     *
     * @param session 发来消息的连接
     * @param message 消息体，JSON，形状见 {@link ChatInbound}
     * @throws Exception 向连接写入失败时由框架处理
     */
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ProjectId projectId = projectIdOf(session);
        UserId author = authorOf(session);
        if (projectId == null || author == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // 这里不重新查成员身份：连接是长命的，但“移出成员”这个功能还不存在。
        // 将来加入它时，必须同时踢掉该用户已建立的连接，而不是在每条消息上加一次查库。
        ChatInbound inbound;
        try {
            inbound = mapper.readValue(message.getPayload(), ChatInbound.class);
        } catch (JsonProcessingException e) {
            // 坏消息不值得断开连接（客户端可能只发了半截），但必须回一个错误帧，
            // 否则前端只会表现为“消息发出去了，但没人看见”。
            log.debug("收到无法解析的聊天消息：{}", message.getPayload(), e);
            send(session, write(Map.of("error", "消息格式不对，应为 {\"text\":\"…\"}")));
            return;
        }

        ChatMessage saved = messages.append(
                projectId, author, inbound.text(),
                inbound.anchorEventSeq(), inbound.anchorText(), Instant.now());

        broadcast(projectId, ChatMessagePayload.of(saved));
    }

    // ------------------------------------------------------------------

    private void broadcast(ProjectId projectId, Object payload) {
        // 只序列化一次，再发给所有人。
        String json = write(payload);
        for (WebSocketSession watcher : watchers.getOrDefault(projectId, Set.of())) {
            try {
                send(watcher, json);
            } catch (IOException | IllegalStateException e) {
                // 写不进去 = 这个连接已经不在了。顺手清掉，免得它一直躺在表里
                forget(projectId, watcher);
            }
        }
    }

    /**
     * 往一个连接上写一帧。
     *
     * <p>{@code WebSocketSession} 不是线程安全的。两个线程同时 {@code sendMessage}，
     * 两帧文本可能在底层交错成半截，表现为前端偶发收到坏 JSON。这个锁不能省。
     */
    private void send(WebSocketSession session, String json) throws IOException {
        synchronized (session) {
            if (!session.isOpen()) {
                throw new IllegalStateException("连接已关闭");
            }
            session.sendMessage(new TextMessage(json));
        }
    }

    private void forget(ProjectId projectId, WebSocketSession session) {
        watchers.computeIfPresent(projectId, (id, sessions) -> {
            sessions.remove(session);
            // 空了就把键删掉 —— 留着空集合的话，这张表会随着"历史上有人看过的项目数"一直长
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /**
     * 说话的人。
     *
     * <p>{@code principal.getName()} 是账号（Spring 的 UserDetails 约定），所以要查一次库
     * 换成 {@link UserId}；不假设认证层会直接给出 id。
     *
     * @return 对应用户；查不到时为 null，调用方据此关闭连接（这条通道没有 HTTP 状态码）
     */
    private UserId authorOf(WebSocketSession session) {
        return currentUser.find(session.getPrincipal()).map(User::id).orElse(null);
    }

    private static ProjectId projectIdOf(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        String value = UriComponentsBuilder.fromUri(uri).build()
                .getQueryParams().getFirst(PROJECT_ID_PARAM);
        return value == null || value.isBlank() ? null : ProjectId.of(value);
    }

    private String write(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 序列化的是我们自己定义的传输对象，正常不该失败
            throw new IllegalStateException("聊天消息序列化失败：" + payload, e);
        }
    }
}
