package com.codeloom.app.turn;

import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.session.SessionId;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本实例正在跑的轮次，以及打断它们的手段。
 *
 * <p>执行器自己创建的 {@code CancellationToken} 跑完即失，其他请求够不着；中断请求需要
 * 一个落点，这张表就是。中断信号同时经 Redis 广播，因为轮次未必跑在收到请求的实例上。
 * 发布者也会收到自己的消息，这里刻意不过滤：{@code cancel()} 幂等，重复取消无副作用，
 * 而自行过滤会引入只在多实例下暴露的错误。
 *
 * <p>它只能发信号，不能保证停：取消检查点在模型调用前和工具调用前，何时停在哪个边界取决于
 * 这一轮当时在做什么。理由见
 * {@code docs/decisions/architecture/2026-10-06-cancel-is-a-signal-timeout-is-a-result.md}。
 */
@Component
public class RunningTurns implements MessageListener {

    /** 中断信号专用频道，不与事件流共用：混在一起会让订阅者收到不是事件的东西。 */
    public static final String INTERRUPT_CHANNEL = "codeloom:interrupts";

    private final StringRedisTemplate redis;
    private final Map<SessionId, CancellationToken> running = new ConcurrentHashMap<>();

    public RunningTurns(StringRedisTemplate redis, RedisMessageListenerContainer container) {
        this.redis = redis;
        // 挂到事件总线那个容器上，而不是再建一个 —— 一个容器一条订阅连接就够了，
        // 而多一个容器就多一条常驻连接和一份要跟着关的生命周期
        container.addMessageListener(this, new ChannelTopic(INTERRUPT_CHANNEL));
    }

    /**
     * 登记一轮开始。
     *
     * @param sessionId 会话
     * @return 要传给这一轮执行流程的取消 token
     */
    public CancellationToken register(SessionId sessionId) {
        CancellationToken token = new CancellationToken();
        running.put(sessionId, token);
        return token;
    }

    /**
     * 撤掉登记；只有 token 与当前登记相符时才移除。带上 token 是为了防止错配：
     * 这一轮刚结束、另一轮已登记时，不能把新轮次的登记撤掉，否则它再也无法被打断。
     *
     * @param sessionId 会话
     * @param token     该轮次的 token
     */
    public void unregister(SessionId sessionId, CancellationToken token) {
        running.remove(sessionId, token);
    }

    /**
     * 发出中断信号：本地立即取消，并广播给其他实例。
     *
     * <p>返回只表示信号已发出，不表示这一轮已经停下。
     *
     * @param sessionId 要中断的会话
     */
    public void interrupt(SessionId sessionId) {
        cancelLocally(sessionId);
        redis.convertAndSend(INTERRUPT_CHANNEL, sessionId.value());
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String raw = new String(message.getBody(), StandardCharsets.UTF_8);
        cancelLocally(SessionId.of(raw));
    }

    private void cancelLocally(SessionId sessionId) {
        CancellationToken token = running.get(sessionId);
        if (token != null) {
            token.cancel();
        }
    }
}
