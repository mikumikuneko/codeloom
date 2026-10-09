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
 * 一条会话的实时流：**先补历史，再接上实时**，两者之间不能有缝、也不能重叠。
 *
 * <h2>缝和重叠都出在这儿</h2>
 * 一个刚连上（或断线重连）的客户端要两样东西：错过的那些（从库里读），
 * 和接下来发生的（订阅总线）。这两步之间的顺序和去重很讲究：
 *
 * <ul>
 *   <li><b>必须先订阅、后读库。</b> 反过来做的话，两条语句之间落库的事件
 *       既不在历史里、也没被订阅到 —— **永久丢失**，而且客户端不会知道。
 *   <li><b>但先订阅又会重叠：</b> 刚订阅上就到达的事件，也可能正好还在历史范围里
 *       （如果它是在读库之前落库的）。两边都发的话客户端会看到两条一样的。
 *       所以用 seq 游标去重 —— 只发比游标大的。
 *   <li><b>读库期间到达的实时事件要先攒着。</b> 直接发出去的话，客户端看到的是
 *       43、41、42 —— 因为 41 和 42 还在读库那一步里没轮上。
 * </ul>
 *
 * <p>下面那段代码就是这三条的合成：关键是"攒 vs 发"的判定与补历史**共用同一把锁**。
 *
 * <h2>它和 SSE 的分工</h2>
 * 这里只决定**该按什么顺序发哪几条**，不管它们怎么变成一个 HTTP 帧 ——
 * 所以它可以脱离 servlet 容器被测（真 MySQL + 真 Redis 就行），
 * 而 SSE 端点那边只剩"把信封拼成帧"这一件事。
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
     * 开始推这条会话的流。
     *
     * <p>方法返回时，历史已经全部交给 {@code sink} 了，实时事件从此刻起继续往里推。
     *
     * @param lastEventId 客户端已经收到的最后一条的 seq（SSE 的 {@code Last-Event-ID}）；
     *                    空表示从头开始
     * @param sink        每一条的输出口。**按顺序同步调用**，所以实现里做 IO 要小心 ——
     *                    往一个已经断开的 SSE 连接写会阻塞在这里
     * @return 订阅句柄，**必须关闭**
     */
    public EventBus.Subscription stream(SessionId sessionId, Long lastEventId,
                                        Consumer<EventEnvelope> sink) {
        long from = lastEventId == null ? 0L : lastEventId;
        AtomicLong cursor = new AtomicLong(from);
        AtomicBoolean replaying = new AtomicBoolean(true);
        Queue<EventEnvelope> buffered = new ArrayDeque<>();

        // 一把锁同时管两件事：攒/发 的切换，和补历史那一段。
        // 换个写法（比如 volatile 标志位）就会留出"标志翻转后、攒下的还没发"那个窗口 ——
        // 那正是乱序会出现的地方
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
            // 补历史失败（比如库连不上）时不能留下一个已经注册、却永远不会有人关的订阅
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
     * <p>循环到拉不满一批为止，而不是一次 {@code LIMIT N} 就算完：断线很久的客户端
     * 错过的事件可能远超一批，只拉一批会让它**静默地丢掉中间那段** ——
     * 而丢掉的正好是它最需要补的部分。
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
     * 发一条，并守住"每条只发一次"。
     *
     * <p>流式增量没有 seq，也就无从去重 —— 但它本来就不需要：它只走实时通道，
     * 不会同时出现在补发的历史里。
     */
    private static void deliver(EventEnvelope envelope, AtomicLong cursor, Consumer<EventEnvelope> sink) {
        Optional<StoredEvent> stored = envelope.stored();
        if (stored.isPresent()) {
            if (stored.get().seq() <= cursor.get()) {
                // 已经作为历史发过了。没有这一步，客户端会在"重连的那一瞬间"看到重复的一两条 ——
                // 表现为消息列表里莫名多出一条一模一样的
                return;
            }
            cursor.set(stored.get().seq());
        }
        sink.accept(envelope);
    }
}
