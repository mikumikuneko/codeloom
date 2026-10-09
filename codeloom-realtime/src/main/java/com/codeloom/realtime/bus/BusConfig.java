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
 * 把 {@link RedisEventBus} 接到 Redis 的订阅端：谁去听那个频道，听到了谁来处理。
 *
 * <p>链路：某个实例往频道发一条事件 → Redis 推给每一个订阅者（**包括发消息的那个自己**）
 * → 容器的常驻连接收到字节、交给监听器 → 派发线程按顺序处理 → {@link RedisEventBus}
 * 交给本实例的订阅者（浏览器上那条 SSE 连接）。
 *
 * <p>"自己发的消息自己也收到"不是多余的一趟：**去重靠的正是这一趟**（见
 * {@link RedisEventBus}）。所以这里不能把自己的消息滤掉。
 *
 * <h2>两个 bean</h2>
 * <ul>
 *   <li>{@code codeloomBusContainer} —— **订阅者本体**，是 Spring Data Redis 提供的类
 *       （不是我们写的，所以在这个包里搜不到它）。它管连接、管重连、管订阅哪些频道；
 *       唯一需要我们给的东西是**用哪个线程干活**。</li>
 *   <li>{@code busDispatchExecutor} —— 那个线程。**就一个**，理由见下。</li>
 * </ul>
 *
 * <p>不让 bus 自己在构造器里 new 这个容器：{@code RedisMessageListenerContainer}
 * 是个 {@code SmartLifecycle} —— 交给 Spring 管，它会在启动时连上、关闭时断开；
 * 自己 new 这两件事都得手工做，漏掉后者在测试里表现为"跑完之后连接没关"，
 * 在应用里表现为"关不干净"。
 */
@Configuration(proxyBeanMethods = false)
public class BusConfig {

    /**
     * 派发队列的容量。**这是个猜的数**：一条事件 JSON 从几百字节到几 KB，
     * 一万条最坏也就几 MB。
     *
     * <p>这个数的意义不在于它准不准，而在于**队列必须有界** —— 理由见
     * {@link #busDispatchExecutor()}。
     */
    private static final int BUS_QUEUE_CAPACITY = 10_000;

    @Bean
    public RedisMessageListenerContainer codeloomBusContainer(RedisConnectionFactory connectionFactory,
                                                              RedisEventBus bus,
                                                              ExecutorService busDispatchExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // **派发必须单线程，顺序才不会乱。**
        //
        // 默认的 SimpleAsyncTaskExecutor 每条消息起一个新线程，同一个会话的两条事件会
        // 以任意顺序交到订阅者手里（实测出现过 601 排在 602 之后），于是前端的消息列表
        // 会时不时跳一下 —— 偶发、难复现，所以要在这一层堵死。
        //
        // 刻意**不用 SyncTaskExecutor**（在调用线程上直接跑）：那是 Lettuce 的 I/O 线程，
        // 一个慢的 SSE 客户端会把整个 Redis 订阅卡住，连带拖垮所有会话的推送。
        // 单线程 + 有界队列两头都占：既不阻塞 I/O 线程，也不并发。
        //
        // 代价：一个卡住的订阅者会拖慢其他人的推送（改进方向见下面那个执行器）
        container.setTaskExecutor(busDispatchExecutor);

        // 订的是同一个频道：本实例发出去的消息也会被自己收到，正是"不重复投递"所依赖的那一趟
        container.addMessageListener(bus, new ChannelTopic(RedisEventBus.CHANNEL));

        // 中断信号（RunningTurns）也挂在**这个容器**上，所以"按 Esc 打断"的那条消息
        // 和事件推送共用下面这一个线程（一个容器一条常驻连接就够）。
        return container;
    }

    /**
     * 派发线程池：**一个线程 + 一个有界队列**。
     *
     * <h2>为什么是一个线程</h2>
     * 为了顺序 —— 见上面 {@link #codeloomBusContainer}（乱序是实测到的）。
     *
     * <h2>为什么要显式写 ThreadPoolExecutor，而不是 Executors</h2>
     * {@code Executors.newSingleThreadExecutor(...)} 内部的队列是
     * {@code LinkedBlockingQueue}，**不设容量**：订阅者一慢下来，消息就一条条堆进队列、
     * 无限变长，最后吃光内存。阿里手册禁止用 {@code Executors} 造线程池说的就是这一条
     * （另一半是 {@code newCachedThreadPool} 那种线程数无界的）。写在明面上的参数，
     * 比藏在静态工厂里的默认值更容易看见。
     *
     * <h2>队列满了怎么办：让发布者自己跑那一条</h2>
     * {@code CallerRunsPolicy} —— 队列满时，那条消息由**投递它的那个线程**就地执行。
     * 这么选换来两件事：
     * <ul>
     *   <li><b>不丢消息。</b>丢弃类的策略（Discard / DiscardOldest）会在这里丢掉事件，
     *       而 SSE 那条链依赖"每条事件都到"。</li>
     *   <li><b>把背压传回 Redis 订阅线程</b>，而不是无限堆积。</li>
     * </ul>
     * 代价：队列**真的满了**的时候，Lettuce 的 I/O 线程会被短暂阻塞 —— 那正是上面说的
     * "不该在 I/O 线程上跑"。区别在于：这一版只在**到极限时**才发生，无界队列那一版是
     * **平时就在悄悄积累**。
     *
     * <p>真到天天被打满的那天，该做的不是把 {@link #BUS_QUEUE_CAPACITY} 调大，
     * 而是给每个连接一条自己的队列（单个连接慢了只丢它自己的帧）。
     *
     * <p>交给 Spring 管是为了关闭时能跟着停 —— 它自己启动的线程不属于容器，
     * 漏了这一步在测试里表现为"跑完之后还挂着一个非守护线程"。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService busDispatchExecutor() {
        return new ThreadPoolExecutor(
                // 核心线程数 = 最大线程数 = 1：既不会扩容，也不会缩容
                1, 1,
                // 空闲存活时间 0 —— 这里核心数 = 最大数，这个值用不上
                0L, TimeUnit.MILLISECONDS,
                // **有界**队列
                new ArrayBlockingQueue<>(BUS_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "codeloom-bus-dispatch");
                    // 守护线程：即使没被关掉，它也不会阻止 JVM 退出
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
