package com.codeloom.app.session;

import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.session.Session;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 一条会话打过的 checkpoint，是事件流上的投影。
 *
 * <p>合并（合到某个点）和回滚（回到某个点）共用一条规则：只接受这条会话真的打过的点。
 * 不校验的话，任意 sha 都能把不认识的历史搬进主干，或 reset 到不属于这条会话的位置；
 * 两边各写一遍则会有一天在一处放宽、另一处失去约束。
 *
 * <p>checkpoint 来自每轮结束的自动提交（见 {@code TurnExecutor}）和建会话时的第 0 号，
 * 粒度天然是这条会话产出的一小步。
 *
 * <p>同一个校验用两把键：合并要 git 位置，用 sha（同 sha 的多条 checkpoint 指向同一提交，
 * 没有歧义）；回滚要对话位置，用事件序号（一轮未改文件时 sha 会重复，序号不会）。
 */
@Component
public class SessionCheckpoints {

    private final EventStore events;

    public SessionCheckpoints(EventStore events) {
        this.events = events;
    }

    /**
     * 一条 checkpoint 及其在事件流里的序号。
     *
     * @param seq        这条 {@code CheckpointCreated} 事件的 seq，是回滚的定位键
     * @param checkpoint 事件本身
     */
    public record Pinned(long seq, CheckpointCreated checkpoint) {
    }

    /**
     * @param session 会话
     * @return 全部 checkpoint，按发生顺序
     */
    public List<CheckpointCreated> of(Session session) {
        return pinned(session).stream().map(Pinned::checkpoint).toList();
    }

    /**
     * @param session 会话
     * @return 全部 checkpoint 及序号，按发生顺序
     */
    public List<Pinned> pinned(Session session) {
        return events.readAll(session.id()).stream()
                .filter(stored -> stored.event() instanceof CheckpointCreated)
                .map(stored -> new Pinned(stored.seq(), (CheckpointCreated) stored.event()))
                .toList();
    }

    /**
     * 要求这个 sha 是这条会话打过的 checkpoint；合并用，它要的是 git 位置。
     *
     * <p>多条 checkpoint 同 sha（一轮没有改动）时取最早那条，它们指向同一个提交。
     *
     * @param session   会话
     * @param commitSha 待校验的提交
     * @return 对应的 checkpoint
     * @throws IllegalArgumentException 不是这条会话的 checkpoint；调用方映射为 400
     */
    public CheckpointCreated require(Session session, String commitSha) {
        return of(session).stream()
                .filter(checkpoint -> checkpoint.commitSha().equals(commitSha))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "这不是这条会话打过的一个 checkpoint：" + commitSha));
    }

    /**
     * 要求这个事件序号是这条会话打过的 checkpoint；回滚用，它要的是对话位置，
     * 而该位置只有序号定得住。序号不重复，因此没有"取哪一条"的问题。
     *
     * @param session 会话
     * @param seq     checkpoint 事件的 seq
     * @return 对应的 checkpoint 与序号
     * @throws IllegalArgumentException 这条会话没有该序号的 checkpoint；调用方映射为 400
     */
    public Pinned requireAt(Session session, long seq) {
        return pinned(session).stream()
                .filter(pinned -> pinned.seq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "这条会话里没有这个序号的 checkpoint：" + seq));
    }
}
