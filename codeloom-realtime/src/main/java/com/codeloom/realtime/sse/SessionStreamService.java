package com.codeloom.realtime.sse;

import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.session.SessionId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 一条会话的实时流：先补历史，再接上实时，两者之间不能有缝也不能重叠。
 *
 * <p>必须先订阅、后读库：反过来，两条语句之间落库的事件既不在历史里也没被订阅到，
 * 会永久丢失且客户端不会知道。但先订阅会重叠，刚订阅就到达的事件可能也还在历史范围里，
 * 所以用 seq 游标去重，只发比游标大的。
 *
 * <p>读库期间到达的实时事件先缓冲，否则客户端会看到乱序。缓冲与发送的切换和补历史
 * 共用同一把锁。
 *
 * <p>这里只决定按什么顺序发哪几条，不负责把信封变成 HTTP 帧，因此可以脱离 servlet
 * 容器测试；SSE 端点只负责拼帧。
 */
@Component
public class SessionStreamService {

    private final EventStore events;
    private final EventBus bus;
    private final int replayBatch;

    public SessionStreamService(EventStore events,
                                EventBus bus,
                                @Value("${codeloom.stream.replay-batch:500}") int replayBatch) {
        this.events = events;
        this.bus = bus;
        this.replayBatch = replayBatch;
    }

    /**
     * 开始推这条会话的流。方法返回时历史已经全部交给 {@code sink}，实时事件从此刻起继续推送。
     *
     * @param sessionId   会话
     * @param lastEventId 客户端已经收到的最后一条的 seq（SSE 的 {@code Last-Event-ID}）；
     *                    空表示从头开始
     * @param sink        每一条的输出口，按顺序同步调用；实现里的 IO 会阻塞这条流，
     *                    往已经断开的 SSE 连接写也会阻塞在这里
     * @return 订阅句柄，必须关闭
     */
    public EventBus.Subscription stream(SessionId sessionId, Long lastEventId,
                                        Consumer<EventEnvelope> sink) {
        long from = lastEventId == null ? 0L : lastEventId;
        AtomicLong cursor = new AtomicLong(from);
        AtomicBoolean replaying = new AtomicBoolean(true);
        Queue<EventEnvelope> buffered = new ArrayDeque<>();

        // 同一把锁管缓冲/发送的切换和补历史，避免“标志翻转后、缓冲的还没发”的乱序窗口。
        Object gate = new Object();

        EventBus.Subscription subscription = bus.subscribe(sessionId, envelope -> {
            synchronized (gate) {
                if (replaying.get()) {
                    buffered.add(envelope);
                    return;
                }
                deliver(envelope, cursor, sink);
            }
        });

        try {
            replay(sessionId, cursor, sink);
        } catch (RuntimeException e) {
            // 补历史失败时不能留下已经注册、却永远不会有人关闭的订阅。
            subscription.close();
            throw e;
        }

        synchronized (gate) {
            replaying.set(false);
            for (EventEnvelope envelope = buffered.poll(); envelope != null; envelope = buffered.poll()) {
                deliver(envelope, cursor, sink);
            }
        }
        return subscription;
    }

    /**
     * 分批把漏掉的历史拉完。
     *
     * <p>循环到拉不满一批为止，而不是只执行一次查询：断线很久的客户端错过的事件可能
     * 远超一批，只拉一批会静默丢掉中间那段，而那正是最需要补的部分。
     */
    private void replay(SessionId sessionId, AtomicLong cursor, Consumer<EventEnvelope> sink) {
        List<StoredEvent> batch;
        do {
            batch = events.readAfter(sessionId, cursor.get(), replayBatch);
            for (StoredEvent stored : batch) {
                cursor.set(stored.seq());
                sink.accept(EventEnvelope.of(stored));
            }
        } while (batch.size() == replayBatch);
    }

    /**
     * 发一条，并守住“每条只发一次”。
     *
     * <p>流式增量没有 seq，无法去重，也不需要：它只走实时通道，不会出现在补发的历史里。
     */
    private static void deliver(EventEnvelope envelope, AtomicLong cursor, Consumer<EventEnvelope> sink) {
        Optional<StoredEvent> stored = envelope.stored();
        if (stored.isPresent()) {
            if (stored.get().seq() <= cursor.get()) {
                // 已经作为历史发过了；没有这一步，客户端重连时会看到重复的一两条。
                return;
            }
            cursor.set(stored.get().seq());
        }
        sink.accept(envelope);
    }
}
