package com.codeloom.domain.event;

import com.codeloom.domain.session.SessionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话事件流上的一条：可能已经落库，也可能正在生成中。
 *
 * <p>它比 {@link StoredEvent} 弱 —— 后者保证 {@code seq} 必有。流上还有永不落库的
 * {@link AssistantDelta}，但看的人必须实时看到它。{@code StoredEvent} 能变成本类型
 * （{@link #of}）；反向只有带 seq 的条目成立（{@link #stored()}），流式增量换不回去。
 *
 * <p>构造器强制"落库的必有 seq、易失的必无 seq"：seq 决定 SSE 帧里有没有 {@code id:} 字段，而
 * 它是客户端断线重连时回传的游标（{@code Last-Event-ID}）。给流式增量安上 id，客户端会把不
 * 存在的 seq 当成续传位置。
 *
 * @param sessionId  事件归属的会话
 * @param seq        存储层分配的全局单调序号；流式增量为 null
 * @param occurredAt 事件发生时间
 * @param event      业务事实
 * @throws IllegalArgumentException 已落库事件缺少 seq、易失事件带 seq，或 seq 不是正数
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

    /**
     * 已落库的事件 → 流上的一条。反向转换见 {@link #stored()}：只有带 seq 的条目换得回去。
     *
     * @param stored 已落库的事件
     * @return 流上的一条
     */
    public static EventEnvelope of(StoredEvent stored) {
        return new EventEnvelope(stored.sessionId(), stored.seq(), stored.occurredAt(), stored.event());
    }

    /**
     * 正在生成中的流式增量。没有 seq —— 它不该进入任何游标。
     *
     * @param sessionId  事件归属的会话
     * @param occurredAt 事件发生时间
     * @param event      流式增量
     * @return 流上的一条
     */
    public static EventEnvelope ephemeral(SessionId sessionId, Instant occurredAt, EphemeralEvent event) {
        return new EventEnvelope(sessionId, null, occurredAt, event);
    }

    /**
     * 已落库的那部分。
     *
     * @return 流式增量时为空 —— 调用方拿不到一个"seq 是 null 的 StoredEvent"，因为那种东西
     *         不该存在
     */
    public Optional<StoredEvent> stored() {
        return seq == null
                ? Optional.empty()
                : Optional.of(new StoredEvent(sessionId, seq, occurredAt, event));
    }
}
