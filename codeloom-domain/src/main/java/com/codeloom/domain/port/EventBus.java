package com.codeloom.domain.port;

import com.codeloom.domain.event.EphemeralEvent;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.session.SessionId;

import java.util.function.Consumer;

/**
 * 实时事件总线：把事件推给订阅者，并跨实例扇出（Redis Pub/Sub）。
 *
 * <h2>两条路径，不要混</h2>
 * <ul>
 *   <li>{@link #publish} —— 已落库的事件。调用前必须先 {@link EventStore#append}，
 *       保证「订阅者看到的事件」一定是持久的。</li>
 *   <li>{@link #publishEphemeral} —— 流式增量。**不落库**，只是把正在生成的内容
 *       推给正在看的人。形参类型是 {@link EphemeralEvent}，所以把持久事件送错路径
 *       也是编译错误。</li>
 * </ul>
 *
 * <p>把两个方法分开而不是合并成一个，是因为它们的**可靠性要求完全不同**：
 * 前者丢了就是数据丢失，后者丢了只是少刷一段字。
 */
public interface EventBus {

    /** 广播一条已落库的事件。调用方负责先落库。 */
    void publish(StoredEvent event);

    /** 广播一条流式增量。不落库，不保证送达。 */
    void publishEphemeral(SessionId sessionId, EphemeralEvent event);

    /**
     * 订阅某条会话的实时事件流。
     *
     * <p>回调收到的是 {@link EventEnvelope} 而**不是** {@link StoredEvent}：
     * 一条订阅要同时承接两种东西 —— 已落库的事件，和模型正在逐 token 吐出来的流式增量。
     * 浏览器看的就是**一条**流；拆成两个订阅方法的话，调用方得自己去合并两条流、
     * 还得处理它们的相对顺序。两者的区分看 {@code seq} 有没有值。
     *
     * <p>返回的句柄**必须关闭**，否则连接泄漏。
     */
    Subscription subscribe(SessionId sessionId, Consumer<EventEnvelope> listener);

    /** 订阅句柄。实现 AutoCloseable 是为了能用在 try-with-resources 里。 */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
