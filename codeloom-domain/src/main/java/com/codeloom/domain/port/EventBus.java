package com.codeloom.domain.port;

import com.codeloom.domain.event.EphemeralEvent;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.session.SessionId;

import java.util.function.Consumer;

/**
 * 实时事件总线：把事件推给订阅者，并跨实例扇出（Redis Pub/Sub）。
 *
 * <p>两条路径不能混：{@link #publish} 只发已落库的事件，调用前必须先
 * {@link EventStore#append}，保证订阅者看到的事件一定是持久的；{@link #publishEphemeral}
 * 只发不落库的流式增量。形参类型不同，送错路径是编译错误。分开是因为可靠性要求
 * 完全不同：前者丢了是数据丢失，后者丢了只是少显示一段流式输出。见
 * {@code docs/decisions/architecture/2026-09-24-events-split-persistent-and-ephemeral.md}。
 */
public interface EventBus {

    /**
     * 广播一条已落库的事件。调用方负责先落库。
     *
     * @param event 已落库的事件
     */
    void publish(StoredEvent event);

    /**
     * 广播一条流式增量。不落库，不保证送达。
     *
     * @param sessionId 会话
     * @param event     流式增量
     */
    void publishEphemeral(SessionId sessionId, EphemeralEvent event);

    /**
     * 订阅某条会话的实时事件流。
     *
     * <p>回调收到的是 {@link EventEnvelope} 而不是 {@link StoredEvent}：一条订阅同时
     * 承接已落库的事件和正在逐 token 吐出的流式增量，浏览器看的是同一条流。拆成两个
     * 订阅方法会逼调用方自己合并两条流并处理相对顺序。两者靠 {@code seq} 有没有值区分。
     *
     * @param sessionId 会话
     * @param listener  事件回调
     * @return 订阅句柄，必须关闭，否则连接泄漏
     */
    Subscription subscribe(SessionId sessionId, Consumer<EventEnvelope> listener);

    /** 订阅句柄。实现 {@link AutoCloseable}，可用于 try-with-resources。 */
    interface Subscription extends AutoCloseable {
        /** 关闭订阅并释放连接。 */
        @Override
        void close();
    }
}
