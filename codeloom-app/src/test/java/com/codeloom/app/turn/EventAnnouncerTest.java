package com.codeloom.app.turn;

import com.codeloom.domain.event.EphemeralEvent;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.session.SessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link EventAnnouncer} 的时机契约：提交前一条都不发，提交后按落库顺序发。
 *
 * <p>这里不起 Spring 上下文：连库的测试中提交只可能发生在测试方法之后（方法事务回滚），
 * 所以直接驱动 Spring 的同步登记簿：{@code initSynchronization()} 模拟事务进行中，
 * 再手动调用 {@code afterCommit}。真落库加真总线的版本在
 * {@code SessionWriterAnnouncementTest}，两者合起来覆盖"机制正确"和"该路径真的走了它"。
 */
class EventAnnouncerTest {

    private final RecordingBus bus = new RecordingBus();
    private final EventAnnouncer announcer = new EventAnnouncer(bus);

    private final SessionId session = SessionId.generate();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("事务还开着就一条都不发 —— 发了的话订阅者会看到一条库里根本没有的事实")
    void nothingIsSentBeforeTheTransactionCommits() {
        beginTransaction();

        announcer.announce(List.of(event(1)));

        assertThat(bus.published).isEmpty();
        commit();
        assertThat(bus.published).containsExactly(event(1));
    }

    @Test
    @DisplayName("同一个事务里的几次写入按落库顺序发")
    void writesAreAnnouncedInRegistrationOrder() {
        beginTransaction();

        announcer.announce(List.of(event(1)));
        announcer.announce(List.of(event(2)));

        // 顺序反了，订阅端已见过 seq=2，会把 seq=1 当作重连时的重复直接丢弃，而它不会再出现
        //（判据见 SessionStreamService.deliver）
        commit();
        assertThat(bus.published).extracting(StoredEvent::seq).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("没有事务就当场发 —— 那会儿没有「提交」可等")
    void withoutATransactionIsSentImmediately() {
        announcer.announce(List.of(event(1)));

        assertThat(bus.published).containsExactly(event(1));
    }

    @Test
    @DisplayName("一批是空的就什么都不发")
    void anEmptyBatchSendsNothing() {
        announcer.announce(List.of());

        assertThat(bus.published).isEmpty();
    }

    @Test
    @DisplayName("广播炸了不往外抛 —— 那只是「这一刻没推到」，库里的那条事实还在")
    void aFailingBusDoesNotBreakTheWrite() {
        bus.explode = true;

        assertThatCode(() -> announcer.announce(List.of(event(1)))).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------

    private void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    /** 模拟提交 —— Spring 在提交之后做的就是逐个回调它们。 */
    private void commit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private StoredEvent event(long seq) {
        return new StoredEvent(session, seq, Instant.parse("2026-10-10T10:00:00Z"),
                new UserMessage("第 " + seq + " 句"));
    }

    private static final class RecordingBus implements EventBus {

        private final List<StoredEvent> published = new ArrayList<>();
        private boolean explode;

        @Override
        public void publish(StoredEvent event) {
            if (explode) {
                throw new IllegalStateException("模拟 Redis 那边挂了");
            }
            published.add(event);
        }

        @Override
        public void publishEphemeral(SessionId sessionId, EphemeralEvent event) {
            throw new UnsupportedOperationException("这个测试只管持久事件那条通道");
        }

        @Override
        public Subscription subscribe(SessionId sessionId, Consumer<EventEnvelope> listener) {
            throw new UnsupportedOperationException("这个测试不订阅");
        }
    }
}
