package com.codeloom.realtime.bus;

import com.codeloom.domain.event.EphemeralEvent;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.session.SessionId;
import com.codeloom.realtime.event.EventEnvelopeCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link EventBus} 的 Redis 实现：本实例和跨实例的订阅者都经 Redis Pub/Sub 收到事件。
 *
 * <p>本地订阅者也走 Redis，而不是在 {@code publish} 里直接扇出：Pub/Sub 本来就会把消息
 * 递给发布者自己，直连再加一次会让本实例收到两遍；而走同一条路径，所有订阅者看到的
 * 顺序也与 Redis 交付顺序一致。代价是本实例多一次局域网往返。
 *
 * <p>Redis 保证投递顺序不代表处理顺序；处理顺序由 {@code BusConfig} 的单线程派发器保证，
 * 两处要一起看。
 *
 * <p>所有会话共用一个频道，消息里带 {@code sessionId}，收到后只投给订阅该会话的连接。
 * 这样 Redis 的订阅数不随连接增减。代价是每个实例都会收到全部会话的事件；
 * 会话量上来后可改为按会话分频道，那时才需要为连接维护订阅引用计数。
 */
@Component
public class RedisEventBus implements EventBus, MessageListener {

    /**
     * 所有会话共用的频道名。滚动发布时新旧实例必须使用同一个值，否则互相听不见。
     *
     * <p>公开是为了排障时能在 {@code redis-cli} 里直接 {@code SUBSCRIBE codeloom:events}。
     */
    public static final String CHANNEL = "codeloom:events";

    private static final Logger log = LoggerFactory.getLogger(RedisEventBus.class);

    private final StringRedisTemplate redis;
    private final EventEnvelopeCodec codec = new EventEnvelopeCodec();

    /** 会话 → 本实例上订阅它的那些连接。 */
    private final Map<SessionId, List<Consumer<EventEnvelope>>> subscribers = new ConcurrentHashMap<>();

    public RedisEventBus(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void publish(StoredEvent event) {
        send(EventEnvelope.of(event));
    }

    @Override
    public void publishEphemeral(SessionId sessionId, EphemeralEvent event) {
        // 没有存储层来盖落库时间，用发送时刻；它不参与游标（没有 seq）。
        send(EventEnvelope.ephemeral(sessionId, Instant.now(), event));
    }

    /**
     * @param sessionId 订阅的会话
     * @param listener  该会话的每条事件
     * @return 取消订阅的句柄；关闭后不再调用该监听器
     */
    @Override
    public Subscription subscribe(SessionId sessionId, Consumer<EventEnvelope> listener) {
        subscribers.computeIfAbsent(sessionId, id -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> unsubscribe(sessionId, listener);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        EventEnvelope envelope;
        try {
            envelope = codec.read(json);
        } catch (RuntimeException e) {
            // 一条坏消息不能让整个扇出停摆；记录后丢弃，等人工排查。
            log.warn("收到无法解码的事件消息，已丢弃：{}", json, e);
            return;
        }
        dispatch(envelope);
    }

    // ------------------------------------------------------------------

    private void send(EventEnvelope envelope) {
        redis.convertAndSend(CHANNEL, codec.write(envelope));
    }

    private void dispatch(EventEnvelope envelope) {
        List<Consumer<EventEnvelope>> listeners = subscribers.get(envelope.sessionId());
        if (listeners == null) {
            // 本实例上没有人订阅这条会话，正常情况。
            return;
        }
        for (Consumer<EventEnvelope> listener : listeners) {
            try {
                listener.accept(envelope);
            } catch (RuntimeException e) {
                // 一个订阅者失败不影响其他订阅者，例如某个浏览器连接已经断开。
                log.warn("一个订阅者处理事件时出错，已跳过：会话 {}", envelope.sessionId(), e);
            }
        }
    }

    private void unsubscribe(SessionId sessionId, Consumer<EventEnvelope> listener) {
        subscribers.computeIfPresent(sessionId, (id, listeners) -> {
            listeners.remove(listener);
            // 空列表要删键，否则表会随“本实例看过的会话数”持续增长。
            return listeners.isEmpty() ? null : listeners;
        });
    }
}
