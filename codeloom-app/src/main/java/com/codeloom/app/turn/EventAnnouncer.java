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
 * 把事件交给总线的唯一入口，两条通道在这里分开：
 *
 * <p>{@link #announce} 发布持久事件，在落库并提交之后才发，订阅者断线重连时可从库里补齐
 * （SSE 的 {@code Last-Event-ID}）。{@link #announceEphemeral} 发布流式增量：模型正在打字的
 * 半截，不落库，也不保证送达。
 *
 * <p>持久事件注册在 {@code afterCommit} 上：在事务里发，订阅者可能先看到一条、事务随后回滚，
 * 于是界面出现库里没有的事实。此前靠调用方在事务外发送，被遗忘过多次（回滚、换模型、两条
 * 同步）。注册顺序就是落库顺序，单个写入各自提交时天然有序。
 *
 * <p>顺序是不变量的一部分：先落库的必须先发。订阅端会丢弃 seq 不大于游标的事件，
 * 所以晚发的旧事件会被永久丢弃。会破坏顺序的是跨了别人写入的长事务，
 * 因此一个事务只包一次写入。
 *
 * <p>没有事务时立刻发送，单测直接调用写入即走这一支。两条通道都尽力而为：失败只记日志，
 * 绝不外抛。数据本身已经安全（持久事件已落库，增量本就不保证送达），抛出会让 agent 循环
 * 把一个能跑的轮次判死。
 */
@Component
public class EventAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(EventAnnouncer.class);

    private final EventBus bus;

    public EventAnnouncer(EventBus bus) {
        this.bus = bus;
    }

    /**
     * 宣布一批已经落库的事件：有事务在跑就等提交，没有就立即发送。
     *
     * @param events 已落库的事件，按落库顺序；空列表直接返回
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

    /**
     * @param sessionId 事件所属会话
     * @param event     不落库的流式增量（正文或思考）
     */
    public void announceEphemeral(SessionId sessionId, EphemeralEvent event) {
        quietly("流式增量", () -> bus.publishEphemeral(sessionId, event));
    }

    private void push(List<StoredEvent> batch) {
        for (StoredEvent event : batch) {
            quietly("事件 seq=" + event.seq(), () -> bus.publish(event));
        }
    }

    /** 广播失败只记日志，绝不外抛。 */
    private void quietly(String what, Runnable push) {
        try {
            push.run();
        } catch (RuntimeException e) {
            log.warn("{} 广播失败 —— 数据本身没问题，只是这一刻没推出去", what, e);
        }
    }
}
