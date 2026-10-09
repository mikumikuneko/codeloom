package com.codeloom.app.session;

import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.session.Session;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 一条会话打过的 checkpoint —— 事件流上的一个投影。
 *
 * <h2>为什么值得单独一个类</h2>
 * {@code merge}（合到某个点）和 {@code rewind}（回到某个点）用的是**同一条规则**：
 * <strong>只接受这条会话真的打过的一个点</strong>。
 *
 * <p>不查这条规则的话，客户端可以拿**任意**一个 sha 过来 —— 合并就是"把一个不认识的历史
 * 搬进主干"，回滚就是"reset 到一个不属于这条会话的位置"。两边各写一遍的话，
 * 某天有人在一处放宽了，另一处就失去了约束。
 *
 * <p>checkpoint 的来源是每轮结束那个自动提交（见 {@code TurnExecutor}），加上建会话时
 * 那个第 0 号 —— 所以它天然就是"这条会话产出的一小步"的粒度，正好是小步合并要的单位。
 *
 * <h2>同一个校验，两把键：sha 和序号</h2>
 * 两边要的东西不一样：
 *
 * <ul>
 *   <li><b>合并</b>要的是一个 <b>git 位置</b> —— 它就把那个 sha 交给 git 去 merge。
 *       好几条 checkpoint 撞同一个 sha 对合并**没有歧义**（撞车的几条本来就在同一个
 *       提交上，合哪条都是合那一个提交），所以 {@code require} 用 sha 是对的。</li>
 *   <li><b>回滚</b>要的是一个 <b>对话位置</b> —— "退到你选的那句话之前"。这个位置
 *       sha 定不了（同一轮没改文件就和上一条同 sha），所以 {@code requireAt} 用序号。</li>
 * </ul>
 *
 * <p>两把键各开一处，规则是同一条 —— 这才是这个类要守住的东西。
 */
@Component
public class SessionCheckpoints {

    private final EventStore events;

    public SessionCheckpoints(EventStore events) {
        this.events = events;
    }

    /**
     * 一条 checkpoint，连它在事件流里的序号。序号是回滚的定位键（见类注释）。
     *
     * @param seq 这条 {@code CheckpointCreated} 事件在流里的 seq
     */
    public record Pinned(long seq, CheckpointCreated checkpoint) {
    }

    /** 全部 checkpoint，按发生顺序。 */
    public List<CheckpointCreated> of(Session session) {
        return pinned(session).stream().map(Pinned::checkpoint).toList();
    }

    /** 全部 checkpoint，带上序号，按发生顺序。 */
    public List<Pinned> pinned(Session session) {
        return events.readAll(session.id()).stream()
                .filter(stored -> stored.event() instanceof CheckpointCreated)
                .map(stored -> new Pinned(stored.seq(), (CheckpointCreated) stored.event()))
                .toList();
    }

    /**
     * 要求这个 sha 确实是这条会话打过的 checkpoint。**给合并用** —— 它要的是 git 位置。
     *
     * <p>sha 撞车（一轮什么都没改）时取**最早**那条：撞车的几条指向同一个提交，
     * 对合并来说它们就是同一个位置，取哪条都一样。
     *
     * @throws IllegalArgumentException 不是 —— 那是客户端传错了，400
     */
    public CheckpointCreated require(Session session, String commitSha) {
        return of(session).stream()
                .filter(checkpoint -> checkpoint.commitSha().equals(commitSha))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "这不是这条会话打过的一个 checkpoint：" + commitSha));
    }

    /**
     * 要求这个序号确实是这条会话打过的一条 checkpoint。**给回滚用** —— 它要的是对话位置，
     * 而那个位置只有序号定得住（见类注释里"两把键"那段）。
     *
     * <p>序号不重复，所以这里不存在"取哪一条"的问题：要么就是它，要么没有。
     *
     * @throws IllegalArgumentException 不是 —— 那是客户端传错了，400
     */
    public Pinned requireAt(Session session, long seq) {
        return pinned(session).stream()
                .filter(pinned -> pinned.seq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "这条会话里没有这个序号的 checkpoint：" + seq));
    }
}
