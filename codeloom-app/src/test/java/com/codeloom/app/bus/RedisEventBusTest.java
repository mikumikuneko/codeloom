package com.codeloom.app.bus;

import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.session.SessionId;
import com.codeloom.realtime.bus.RedisEventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件总线的真 Redis 测试。
 *
 * <p>本实例发布的消息**绕 Redis 一圈再回到本实例的订阅者**（见 {@code RedisEventBus} 的类注释），
 * 所以这些测试顺带把那条路径也走通了 —— 它和"另一个实例发来的"走的是同一段代码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isRedisReachable")
class RedisEventBusTest {

    private static final long RECEIVE_TIMEOUT_SECONDS = 5;

    @Autowired
    private RedisEventBus bus;

    @Autowired
    private StringRedisTemplate redis;

    private final SessionId sessionId = SessionId.generate();
    private final BlockingQueue<EventEnvelope> received = new LinkedBlockingQueue<>();

    // ------------------------------------------------------------------

    @Test
    @DisplayName("发布的事件会送到订阅者手上")
    void publishedEventsReachSubscribers() {
        try (EventBus.Subscription ignored = bus.subscribe(sessionId, received::add)) {
            bus.publish(new StoredEvent(sessionId, 7L, Instant.now(), new UserMessage("你好")));

            EventEnvelope envelope = awaitOne();
            assertThat(envelope.seq()).isEqualTo(7L);
            assertThat(envelope.event()).isEqualTo(new UserMessage("你好"));
        }
    }

    @Test
    @DisplayName("流式增量也送得到，而且**没有 seq**")
    void ephemeralEventsHaveNoSeq() {
        // 这一条直接决定 SSE 帧里有没有 `id:` —— 有了它，客户端断线重连时会从
        // 一个不存在的 seq 接着拉
        try (EventBus.Subscription ignored = bus.subscribe(sessionId, received::add)) {
            bus.publishEphemeral(sessionId, new AssistantDelta("半个句"));

            EventEnvelope envelope = awaitOne();
            assertThat(envelope.seq()).isNull();
            assertThat(envelope.event()).isEqualTo(new AssistantDelta("半个句"));
        }
    }

    @Test
    @DisplayName("两个订阅者都收得到")
    void everySubscriberGetsItsCopy() {
        BlockingQueue<EventEnvelope> other = new LinkedBlockingQueue<>();

        try (EventBus.Subscription first = bus.subscribe(sessionId, received::add);
             EventBus.Subscription second = bus.subscribe(sessionId, other::add)) {
            bus.publish(new StoredEvent(sessionId, 1L, Instant.now(), new UserMessage("两个人都在看")));

            assertThat(awaitOne().seq()).isEqualTo(1L);
            assertThat(await(other)).extracting(EventEnvelope::seq).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("只投给订阅了这条会话的人")
    void onlyInterestedSubscribersGetIt() throws Exception {
        SessionId otherSession = SessionId.generate();
        BlockingQueue<EventEnvelope> other = new LinkedBlockingQueue<>();

        try (EventBus.Subscription ignored = bus.subscribe(sessionId, received::add);
             EventBus.Subscription alsoIgnored = bus.subscribe(otherSession, other::add)) {
            bus.publish(new StoredEvent(sessionId, 1L, Instant.now(), new UserMessage("只给这条会话")));

            assertThat(awaitOne()).isNotNull();
            assertThat(other.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    @DisplayName("订阅关掉之后就不再收到 —— 否则订阅表会随着「连过的连接数」一直长")
    void closedSubscriptionsStopReceiving() throws Exception {
        EventBus.Subscription subscription = bus.subscribe(sessionId, received::add);
        subscription.close();

        bus.publish(new StoredEvent(sessionId, 1L, Instant.now(), new UserMessage("关掉之后发的")));

        assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("一条坏消息不会让整个扇出停摆")
    void oneBadMessageDoesNotBreakTheBus() throws Exception {
        // 让消息坏掉的场景本来就需要人去看（新旧版本格式不一致之类），
        // 但它不该把正在看的人一起带走
        try (EventBus.Subscription ignored = bus.subscribe(sessionId, received::add)) {
            redis.convertAndSend(RedisEventBus.CHANNEL, "这不是事件信封");

            bus.publish(new StoredEvent(sessionId, 9L, Instant.now(), new UserMessage("坏消息之后的好消息")));

            assertThat(awaitOne().seq()).isEqualTo(9L);
        }
    }

    // ------------------------------------------------------------------

    private EventEnvelope awaitOne() {
        return await(received);
    }

    private static EventEnvelope await(BlockingQueue<EventEnvelope> queue) {
        try {
            EventEnvelope envelope = queue.poll(RECEIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(envelope).as("%d 秒内没有收到事件".formatted(RECEIVE_TIMEOUT_SECONDS)).isNotNull();
            return envelope;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待事件时被中断", e);
        }
    }
}
