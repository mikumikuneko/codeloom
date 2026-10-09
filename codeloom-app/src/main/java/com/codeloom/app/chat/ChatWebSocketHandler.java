package com.codeloom.app.chat;

import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.port.UserRepository;
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
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目内聊天室。**纯人类通道，agent 看不见它。**
 *
 * <h2>三条安全约定</h2>
 * <ol>
 *   <li><b>说话的人来自连接上的已认证身份，不是消息体里的字段。</b>
 *       从消息体里取等于让谁都能冒充别人 —— 聊天室最容易出的那种问题。
 *       {@link ChatInbound} 里干脆没有 authorId 这个字段。
 *   <li><b>没有身份或没有 projectId 的连接直接关掉</b>，而不是让它挂着。
 *       身份由 Spring Security 在握手阶段落到 {@code session.getPrincipal()} 上
 *       （见 {@code SecurityConfig}：{@code anyRequest().authenticated()}），
 *       所以这一支是**防御性的**，不是主路径 —— 一条不知道"谁在说话"的聊天连接没有意义。
 *   <li><b>必须是这个项目的成员</b>（见 {@link #afterConnectionEstablished}）。
 *       URL 里的 {@code projectId} 是**调用方给的**，光有它什么都证明不了。
 * </ol>
 *
 * <h2>广播回发送者自己，不给它单独的 ack</h2>
 * 前端只有一条渲染路径：收到广播就渲染。分两条路径的话，自己的消息和别人的消息会走两段
 * 代码，而它们迟早会长得不一样。
 *
 * <h2>单实例内存里的订阅表</h2>
 * 每个实例各记各的（谁在本实例上看着这个项目）。跨实例要投递的话，得把每条聊天消息
 * 也过一遍 Redis Pub/Sub —— 而聊天消息本来就落库了，另一个实例上的客户端刷新一下就拿到了。
 * 为聊天室引入一条广播链路，换来的是"另一台机器上的两个人能看到对方打字"这种**没有实际需求**
 * 的能力。
 *
 * <h2>为什么这个类住在 app 而不是 realtime</h2>
 * 和 {@code SessionStreamController} 是同一条理由：**授权是 Web 层的事**。
 * 它要过 {@link ProjectAccess}，而那个类住在 app，依赖方向是 {@code app → realtime} ——
 * realtime 够不到它，放在 realtime 的话这道成员校验就无处可写，
 * 任何人都能 {@code ?projectId=<随便一个 uuid>} 进别人的聊天室。
 *
 * <p>而"搬进 app"这件事附带一条义务：**app 层这套服务/控制器图整体是
 * Web 有条件的**（见 {@code SecurityConfig} 的类注释），所以这个类也得挂上同一个条件 ——
 * 它现在依赖 {@link ProjectAccess}，而那个 bean 只在 Web 上下文里存在。
 * 漏挂的表现很直白：**所有非 Web 的测试一起起不来**，报的是
 * "找不到 ProjectAccess 这个 bean"，而真正的原因是这个类没挂条件。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /** 端点路径。查询参数带 projectId，见 {@link #PROJECT_ID_PARAM}。 */
    public static final String PATH = "/ws/project-chat";

    /**
     * 用**查询参数**而不是路径变量 {@code /ws/projects/{projectId}/chat}：
     * 路径变量要从 Spring 塞进 session attributes 的那个 map 里取（一个字符串常量 + 一次强转），
     * 而那是 Spring 的内部约定；查询参数读的是 URI 本身，是我们自己的约定。
     */
    public static final String PROJECT_ID_PARAM = "projectId";

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatMessageRepository messages;
    private final UserRepository users;
    private final ProjectAccess access;
    private final ObjectMapper mapper;

    /** 项目 → 正在本实例上看着它的连接。用并发 Set：连接建立与断开来自不同的线程。 */
    private final Map<ProjectId, Set<WebSocketSession>> watchers = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatMessageRepository messages,
                                UserRepository users,
                                ProjectAccess access,
                                ObjectMapper mapper) {
        this.messages = messages;
        this.users = users;
        this.access = access;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        ProjectId projectId = projectIdOf(session);
        UserId author = authorOf(session);
        if (projectId == null || author == null) {
            // 不知道"看哪个项目"或"谁在看"的连接没有意义。关掉，别让它挂着占资源，
            // 也别让它有机会在后面的消息里蒙混过关
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // ★ **登录了不等于能进这个聊天室。**
        //
        //   projectId 是**调用方写在 URL 里**的，光有它什么都证明不了 ——
        //   不过这一道的话，任何一个登录用户随手填一个 uuid 就能读到别人的聊天记录。
        //   和别的接口一样走 ProjectAccess，于是"不是成员"和"这个项目不存在"
        //   在客户端看来完全一样（都只是一条被关掉的连接），枚举不出东西来。
        //
        //   为什么在这里关而不是在握手时拦（那样能给一个干净的 403）：
        //   下面那一支已经在做"这条连接不接受"的判定，多开一处会让两条规矩有机会走岔。
        //   代价是浏览器会先 onopen 再 onclose —— 可这个区别前端也用不上，
        //   两种情况它都只能显示"连不上"。
        try {
            access.requireMember(author, projectId);
        } catch (RuntimeException rejected) {
            // 不往上抛：抛出去会让这条连接以一个含混的服务端错误收场，
            // 而"这不是你的项目"是一个**正常的拒绝**，用 POLICY_VIOLATION 说清楚
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
     * 收到一条消息：落库，然后广播给**这个项目**的所有连接。
     *
     * <p>可见性从 {@code protected} 放宽到 {@code public}：父类是 protected，
     * 而 {@code ChatWebSocketHandlerTest} 要能直接喂一条消息进来 ——
     * 那几条测的是"拿到身份之后怎么处理"，用桩驱动比每次架一条真连接快得多。
     *
     * <p>端到端那一半（真握手拿不拿得到身份、消息能不能真的回到连接上）由
     * {@code ChatWebSocketHandshakeTest} 覆盖 —— **两边都要有**：桩测不了握手，
     * 真连接也测不齐每一条分支。
     */
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ProjectId projectId = projectIdOf(session);
        UserId author = authorOf(session);
        if (projectId == null || author == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // 这里**不再查一次成员身份**。连接是长命的，所以"成员后来被移出项目"理论上
        // 会让一条旧连接继续说话 —— 但**移出成员这个功能现在不存在**（只有加成员）。
        // 真要做它的时候，必须在一处一起处理：踢掉那个人已建立的连接。
        // 那是一条要连带想清楚的改动，不是在这里偷偷加一道"每条消息查一次库"的保险 ——
        // 那种保险守着一个当前不可能发生的场景，代价是每条消息一次查询。
        ChatInbound inbound;
        try {
            inbound = mapper.readValue(message.getPayload(), ChatInbound.class);
        } catch (JsonProcessingException e) {
            // 一条坏消息不值得把连接掐了（客户端可能只是发了个半截的），
            // 但也不能一声不吭 —— 那样前端只能表现为"消息发出去了但没人看见"
            log.debug("收到无法解析的聊天消息：{}", message.getPayload(), e);
            send(session, write(Map.of("error", "消息格式不对，应为 {\"text\":\"…\"}")));
            return;
        }

        // 时间是**服务端**盖的，不用客户端给的：客户端的钟不可信，
        // 而聊天记录按时间排序是它最基本的用法
        ChatMessage saved = messages.append(
                projectId, author, inbound.text(),
                inbound.anchorEventSeq(), inbound.anchorText(), Instant.now());

        broadcast(projectId, ChatMessagePayload.of(saved));
    }

    // ------------------------------------------------------------------

    private void broadcast(ProjectId projectId, Object payload) {
        // 序列化一次发给所有人。每人都序列化一遍的话，一条消息有多少人看就做多少次
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
     * <p><strong>{@code WebSocketSession} 不是线程安全的。</strong> 两个线程同时往一个连接上
     * {@code sendMessage}，两帧文本可能在底层交错成半截 —— 表现为前端偶尔收到一段坏 JSON，
     * 而那是偶发的、难复现的那种。这个锁不能省。
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
     * <p>{@code principal.getName()} 是**账号**（Spring 的 UserDetails 约定），
     * 所以这里要查一次库换成 UserId —— 认证那一层拿到的本来可以是 id，
     * 但那是 Security 那一步才能定的细节，这里按"账号 → 用户"来取，
     * 不提前假设认证层会给出什么。
     */
    private UserId authorOf(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal == null) {
            return null;
        }
        return users.findByUsername(principal.getName()).map(User::id).orElse(null);
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
