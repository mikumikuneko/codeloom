package com.codeloom.realtime.bus;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 把 {@link RedisEventBus} 接到 Redis 的订阅端，并定义派发线程：容器负责连接、重连和频道订阅，
 * 派发线程按顺序把消息交给本实例的订阅者。
 *
 * <p>本实例发出的消息也会经 Redis 回到自己这里；不能过滤，理由见 {@link RedisEventBus}。
 *
 * <p>容器是 Spring Data Redis 的 {@link RedisMessageListenerContainer}。它实现
 * {@link org.springframework.context.SmartLifecycle}，交给 Spring 管理才能在启动时连接、
 * 关闭时断开；自行构造就要
 * 手工补上这两步，漏掉关闭会让测试挂着一个未关闭的连接、应用退出不干净。
 *
 * <p>队列与拒绝策略的理由见 {@code docs/decisions/architecture/2026-10-05-bounded-queue-for-the-bus.md}。
 */
@Configuration(proxyBeanMethods = false)
public class BusConfig {

    /**
     * 派发队列的容量，按一条事件 JSON 几百字节到几 KB 估出来的值，不是实测结果。
     *
     * <p>这个值的具体大小不重要，重要的是队列必须有界；理由见 {@link #busDispatchExecutor()}。
     */
    private static final int BUS_QUEUE_CAPACITY = 10_000;

    /**
     * 订阅容器：常驻连接、频道订阅、以及派发所用线程都挂在它身上。
     *
     * @param connectionFactory Redis 连接工厂
     * @param bus               收到消息后的监听器
     * @param busDispatchExecutor 派发线程；必须是单线程，见 {@link #busDispatchExecutor()}
     * @return 由 Spring 启停的容器
     */
    @Bean
    public RedisMessageListenerContainer codeloomBusContainer(RedisConnectionFactory connectionFactory,
                                                              RedisEventBus bus,
                                                              ExecutorService busDispatchExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 派发必须单线程，否则同一会话的事件会以任意顺序到达订阅者（默认的
        // SimpleAsyncTaskExecutor 每条消息起一个新线程，实测出现过 601 排在 602 之后）。
        //
        // 也不能用 SyncTaskExecutor 在调用线程上直接跑：那是 Lettuce 的 I/O 线程，
        // 一个慢的 SSE 客户端会卡住整个 Redis 订阅，连带拖垮所有会话的推送。
        // 单线程加有界队列既不阻塞 I/O 线程，也不并发。
        //
        // 代价：一个卡住的订阅者会拖慢其他人的推送。
        container.setTaskExecutor(busDispatchExecutor);

        // 订阅同一个频道；本实例发出的消息也会回来一趟（见 RedisEventBus）。
        container.addMessageListener(bus, new ChannelTopic(RedisEventBus.CHANNEL));

        // 中断信号也挂在这个容器上，因此“按 Esc 打断”的消息与事件推送共用这一个线程。
        return container;
    }

    /**
     * 派发线程池：一个线程配一个有界队列。
     *
     * <p>单线程是为了顺序，见 {@link #codeloomBusContainer}。
     *
     * <p>不用 {@code Executors.newSingleThreadExecutor()}：它内部的
     * {@link java.util.concurrent.LinkedBlockingQueue} 没有容量上限，订阅者一慢消息就
     * 无限堆积直到耗尽内存。这里把容量和拒绝策略写在明面上。
     *
     * <p>队列满时用 {@link ThreadPoolExecutor.CallerRunsPolicy}，由投递消息的线程就地
     * 执行：事件不丢，背压传回 Redis 订阅线程。代价是队列真的满时 Lettuce 的 I/O 线程
     * 会被短暂阻塞；无界队列则会把问题从“偶尔阻塞”变成“平时悄悄积累”。
     *
     * <p>真被打满时不该调大 {@link #BUS_QUEUE_CAPACITY}，而应改成每个连接一条队列。
     *
     * <p>{@code destroyMethod = "shutdown"} 让 Spring 关闭时停掉线程；否则测试会留下
     * 非守护线程。线程本身是守护线程，不会单独阻止 JVM 退出。
     *
     * @return 单线程、有界队列、调用者执行的派发线程池
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService busDispatchExecutor() {
        return new ThreadPoolExecutor(
                // 核心线程数 = 最大线程数 = 1：既不会扩容，也不会缩容
                1, 1,
                // 核心数等于最大数，空闲存活时间用不上
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(BUS_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "codeloom-bus-dispatch");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
