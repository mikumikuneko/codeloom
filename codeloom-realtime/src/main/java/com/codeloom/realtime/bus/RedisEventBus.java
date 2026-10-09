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
 * {@link EventBus} 的 Redis 实现：本地扇出 + 跨实例扇出。
 *
 * <h2>为什么本地的订阅者也经过 Redis</h2>
 * 直接的做法是 {@code publish} 里先扇给本地订阅者、再 PUBLISH 给别的实例。这里刻意不那么做：
 *
 * <ol>
 *   <li><b>不重复投递。</b> Redis 的 Pub/Sub 会把消息发给**所有**订阅者，包括发布的那个实例。
 *       本地先扇一次的话，本实例的订阅者就会收到两遍，而要去重就得给消息打上实例标记、
 *       再写一套"是不是我自己发的"判断 —— 那是一整类只在多实例下才暴露的 bug。
 *   <li><b>顺序一致。</b> 走一条路径出去，所有订阅者看到的顺序就是 Redis 交付的顺序。
 *       两条路径的话，本实例的订阅者可能先收到 42（直接扇的）再收到 41（经 Redis 回来的）。
 * </ol>
 *
 * <p><strong>但只做上面这一条还不够。</strong> Redis 按顺序投递，不等于我们按顺序处理 ——
 * 那条保证在 {@code BusConfig} 的单线程派发器里（乱序的成因与实测都在那里），两处要一起看。
 *
 * <p>代价是本实例的订阅者多等一次局域网往返（通常一两毫秒），换来上面两类问题不会出现。
 * 将来真觉得本地延迟碍眼，正确的做法是加实例标记去重，而不是把本地扇出补回来。
 *
 * <h2>单频道 + 本地过滤</h2>
 * 所有会话共用一个频道，消息里带 {@code sessionId}，收到之后只投给订阅了该会话的人。
 * 好处是订阅关系不用跟着连接动态增减（Redis 那边的订阅数是常数）。
 * 代价是每个实例都会收到**全部**会话的事件 —— 在这个规模下无所谓，
 * 会话量上来之后应该改成按会话分频道（{@code codeloom:events:<sessionId>}），
 * 那时才需要为连接做订阅的引用计数。
 */
@Component
public class RedisEventBus implements EventBus, MessageListener {

    /**
     * 所有会话共用。改这个字符串会让新旧实例互相听不见 —— 滚动发布时要留意。
     *
     * <p>公开是为了排障能对得上：出问题时你会想在 {@code redis-cli} 里
     * {@code SUBSCRIBE codeloom:events} 看着它（和 {@code keyFor} 同理）。
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
        // 落库时间由存储层盖，而这里没有存储层 —— 传输时间就取当下。
        // 它不参与任何游标（没有 seq），只用来让客户端知道"这段字是什么时候吐出来的"
        send(EventEnvelope.ephemeral(sessionId, Instant.now(), event));
    }

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
            // 一条坏消息不该让整个扇出停摆。丢掉它、记下来 —— 能让消息坏掉的场景
            // （新旧版本格式不一致之类）本来就需要人去看，而不是让连接断掉
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
            // 很正常：本实例上没有人在看这条会话
            return;
        }
        for (Consumer<EventEnvelope> listener : listeners) {
            try {
                listener.accept(envelope);
            } catch (RuntimeException e) {
                // 一个订阅者出错不该影响别人 —— 比如某个浏览器连接已经断了，
                // 往它写会抛异常，但其他人还在看
                log.warn("一个订阅者处理事件时出错，已跳过：会话 {}", envelope.sessionId(), e);
            }
        }
    }

    private void unsubscribe(SessionId sessionId, Consumer<EventEnvelope> listener) {
        subscribers.computeIfPresent(sessionId, (id, listeners) -> {
            listeners.remove(listener);
            // 空了就把键也删掉 —— 留着空列表的话，这个表会随着"看过的会话数"一直长
            return listeners.isEmpty() ? null : listeners;
        });
    }
}
