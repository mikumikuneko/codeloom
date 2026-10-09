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
 * 本实例上**正在跑的轮次**，以及把它们打断的手段。
 *
 * <h2>为什么需要一张这样的表</h2>
 * {@code CancellationToken} 是执行器在跑一轮时自己造的，跑完就没了 —— 于是没有任何
 * 别的请求够得着它。"用户点了中断"这件事需要一个落点，这就是那个落点。
 *
 * <h2>跨实例：信号走 Redis，而且**不做自我过滤**</h2>
 * 一轮跑在哪台机器上，请求不一定落在同一台 —— 所以中断信号要广播出去。
 * 而发布者自己也会收到自己的那条（Pub/Sub 就是这样），这里**刻意不去过滤它**：
 * {@code cancel()} 是幂等的，重复取消没有任何副作用。
 * 加一套"是不是我自己发的"判断，换来的只是少执行一次无副作用的调用，
 * 却多出一整类只在多实例下才暴露的 bug —— 和 {@code RedisEventBus} 那边同一个取舍。
 *
 * <h2>它只能"发信号"，不能"保证停"</h2>
 * 取消的检查点在**工具边界**上（每一轮模型调用前、每一个工具调用前）。
 * 所以中断请求成功只意味着"信号发出去了"，至于这一轮什么时候停、停在哪一步，
 * 取决于它此刻在干什么。为什么不在这里立刻掐断、以及代价是什么，见
 * {@code docs/decisions/architecture/2026-10-06-cancel-is-a-signal-timeout-is-a-result.md}。
 */
@Component
public class RunningTurns implements MessageListener {

    /** 中断信号专用的频道。**不和事件流共用一个** —— 混进去会让订阅者收到一个不是事件的东西。 */
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
     * 登记一轮开始。返回的 token 要传给这一轮的执行流程（它会被检查）。
     */
    public CancellationToken register(SessionId sessionId) {
        CancellationToken token = new CancellationToken();
        running.put(sessionId, token);
        return token;
    }

    /**
     * 一轮结束，撤掉登记。
     *
     * <p>带上 token 是防止一个罕见的错配：这一轮已经结束、而另一轮刚登记上来时，
     * 不能把新那一轮的登记撤掉 —— 那样新的一轮就再也打断不了了。
     */
    public void unregister(SessionId sessionId, CancellationToken token) {
        running.remove(sessionId, token);
    }

    /**
     * 发出中断信号：本地立刻取消，同时广播给别的实例。
     *
     * <p>返回的是"信号发出去了"，**不是"已经停了"** —— 见类注释。
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
