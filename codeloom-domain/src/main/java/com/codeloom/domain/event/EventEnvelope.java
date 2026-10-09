package com.codeloom.domain.event;

import com.codeloom.domain.session.SessionId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话事件流**上的一条**：可能已经落库，也可能正在生成中。
 *
 * <h2>它和 {@link StoredEvent} 的区别只有一个，但那个区别很重要</h2>
 * {@code StoredEvent} 的不变量更强 —— "这条已经落库了"，所以 {@code seq} 必有。
 * 而流上还有另一种东西：模型正在逐 token 吐出来的
 * {@link AssistantDelta}。它**永远不落库**（见 {@link EphemeralEvent}），
 * 但看的人必须实时看到它。
 *
 * <p>所以两者的关系是单向的：{@code StoredEvent} 能变成本类型（{@link #of}），
 * 反过来不行。这个方向性正是它们该分开的理由 —— 如果合成一个类型、允许 {@code seq} 为空，
 * 那 {@code EventStore.append} 的返回值就会出现"seq 可能是 null"这种它自己都不信的东西。
 *
 * <h2>构造器守住那条不变量</h2>
 * 「落库的必有 seq、易失的必无 seq」在这里强制，而不是靠调用方自觉。
 * 它直接决定 SSE 帧里有没有 {@code id:} 字段 —— 而 {@code id} 是客户端断线重连时
 * 回传的游标（{@code Last-Event-ID}）。给一条流式增量安上 id，
 * 客户端就会把一个**不存在的 seq** 当成续传位置，重连后从错误的地方接着拉。
 *
 * @param seq 存储层分配的全局单调序号；流式增量为 null
 */
public record EventEnvelope(SessionId sessionId, Long seq, Instant occurredAt, Event event) {

    public EventEnvelope {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(event, "event");

        if (event instanceof PersistentEvent && seq == null) {
            throw new IllegalArgumentException(
                    "已落库的事件必须带 seq（它是断线重连的游标）：" + event.getClass().getSimpleName());
        }
        if (event instanceof EphemeralEvent && seq != null) {
            throw new IllegalArgumentException(
                    "易失事件不能带 seq（它会污染客户端的续传位置）：" + event.getClass().getSimpleName());
        }
        if (seq != null && seq <= 0) {
            throw new IllegalArgumentException("seq 由存储层从 1 开始分配，不能是 " + seq);
        }
    }

    /** 已落库的事件 → 流上的一条。单向：反向不成立，见类注释。 */
    public static EventEnvelope of(StoredEvent stored) {
        return new EventEnvelope(stored.sessionId(), stored.seq(), stored.occurredAt(), stored.event());
    }

    /** 正在生成中的流式增量。没有 seq —— 它不该进入任何游标。 */
    public static EventEnvelope ephemeral(SessionId sessionId, Instant occurredAt, EphemeralEvent event) {
        return new EventEnvelope(sessionId, null, occurredAt, event);
    }

    /**
     * 已落库的那部分。
     *
     * @return 流式增量时为空 —— 调用方拿不到一个"seq 是 null 的 StoredEvent"，
     * 因为那种东西根本不该存在
     */
    public Optional<StoredEvent> stored() {
        return seq == null
                ? Optional.empty()
                : Optional.of(new StoredEvent(sessionId, seq, occurredAt, event));
    }
}
