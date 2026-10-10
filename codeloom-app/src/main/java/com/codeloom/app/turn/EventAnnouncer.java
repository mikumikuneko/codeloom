package com.codeloom.app.turn;

import com.codeloom.domain.event.EphemeralEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.session.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 把事件交给总线的**唯一一处** —— 两条通道都在这里，别混：
 *
 * <ul>
 *   <li>{@link #announce} —— <b>持久事件</b>。落库并提交之后才发，订阅者断线重连时能从库里
 *       补齐（SSE 的 {@code Last-Event-ID} 就是干这个的）。</li>
 *   <li>{@link #announceEphemeral} —— <b>流式增量</b>。模型正在打字的那半截，不落库、不保证送达。</li>
 * </ul>
 *
 * <h2>持久事件挂在 {@code afterCommit} 上，不是"谁记得谁推"</h2>
 * 在事务里发的话，订阅者可能先看到一条、然后事务回滚 —— 于是所有人的界面上都出现了
 * 一条**并不存在**的事实，而库里没有。从前这条靠"把事件交回给调用方、由它在事务外发"来躲，
 * 也就是靠**每个调用方都记得**；而它被忘了四次（回滚、换模型、两条同步一次都没发过）。
 *
 * <p>所以时机改成注册 {@code afterCommit}：它从一条**顺序上的巧合**变成一句显式声明，
 * 而"忘了推"这个状态不再存在。想知道一条事件什么时候发出去，看这里，以及
 * {@code SessionWriter.Written} 的构造点 —— 那两个地方就是全部。
 *
 * <p><b>顺序也是这条不变量的一部分：先落库的必须先发。</b>订阅端会把 seq 不比游标大的
 * 那条直接丢掉（那是防重连时重复的机制），所以**晚发的旧事件不是"晚一点到"，是永久丢失**。
 * 注册顺序就是落库顺序，单个写入各自提交时天然有序；会破掉它的是**跨了别人的写入的长事务**
 * —— 它把自己的宣布压到别人后面。所以一个事务**只包一次写入**（这也是
 * {@code SessionWriter} 那个"每次调用都是一小段事务"的取舍在另一处的样子）。
 *
 * <h2>没有事务时立刻发</h2>
 * 那时没有"提交"可等。单测直接调写入、或者自己开一小段事务，走的都是这一支。
 *
 * <h2>一律尽力而为</h2>
 * 两条通道的行为一致：失败只记日志，绝不往外抛。理由是**数据本身已经安全了** ——
 * 持久事件已经落库，推送失败只是"这一刻没推到"；流式增量本来就"不保证送达"。
 * 把这类会自愈的问题抛出去，会让 agent 循环以为是自己出错了，
 * 从而把一个能跑的 turn 判死。
 */
@Component
public class EventAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(EventAnnouncer.class);

    private final EventBus bus;

    public EventAnnouncer(EventBus bus) {
        this.bus = bus;
    }

    /**
     * 宣布一批**已经落库**的事件。
     *
     * <p>有事务在跑就等它提交（见类注释第一段）；没有就当场发。
     */
    public void announce(List<StoredEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        // 复制一份再挂进回调：回调可能在原列表已经被改过之后才跑
        List<StoredEvent> batch = List.copyOf(events);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            push(batch);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                push(batch);
            }
        });
    }

    /** 流式增量的去向（正文和思考各走一路）。见类注释第二段。 */
    public void announceEphemeral(SessionId sessionId, EphemeralEvent event) {
        quietly("流式增量", () -> bus.publishEphemeral(sessionId, event));
    }

    private void push(List<StoredEvent> batch) {
        for (StoredEvent event : batch) {
            quietly("事件 seq=" + event.seq(), () -> bus.publish(event));
        }
    }

    /** 见类注释最后一段：广播失败只记日志，绝不往外抛。 */
    private void quietly(String what, Runnable push) {
        try {
            push.run();
        } catch (RuntimeException e) {
            log.warn("{} 广播失败 —— 数据本身没问题，只是这一刻没推出去", what, e);
        }
    }
}
