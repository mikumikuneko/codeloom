package com.codeloom.app.note;

import com.codeloom.domain.event.AgentNoteDelivered;
import com.codeloom.domain.event.EventType;
import com.codeloom.domain.session.SessionId;
import com.codeloom.realtime.event.EventCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 待投递的 agent 留言：别的会话发来、还没被目标会话领走的那些。
 *
 * <p>不能直接落事件：{@code EventStore.append} 要求 {@code LeaseToken}，而发留言是普通
 * HTTP 请求、没有 token。由执行轮次在开头持有租约时领走并落事件，走和其他事件一样的路。
 * 代价是留言要等下一轮才成为事实，而 agent 本来只在轮次里活动。见
 * {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
 *
 * <p>放在 Redis 而不是内存：发留言的请求和运行这条会话的实例不一定是同一台。
 */
@Component
public class AgentNotes {

    private static final Logger log = LoggerFactory.getLogger(AgentNotes.class);

    private static final String PREFIX = "codeloom:notes:";

    private final StringRedisTemplate redis;

    /** 无状态，可以共享一个。 */
    private final EventCodec codec = new EventCodec();

    public AgentNotes(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 投一条留言进队列。发的人立刻拿到返回，不必等目标会话开跑。
     *
     * @param sessionId 目标会话
     * @param note      留言事件
     */
    public void enqueue(SessionId sessionId, AgentNoteDelivered note) {
        redis.opsForList().rightPush(queueKey(sessionId), codec.encode(note).payload());
    }

    /**
     * 把这条会话待投递的留言挪到 pending 区，等落库成功后再确认。
     *
     * <p>不直接弹出：{@code leftPop} 是至多一次，实例死在“已取走、未落成事件”那一刻，
     * 留言就永久没了。挪到另一个 list 把“取走”和“确认”分开；中间崩溃时留言仍在
     * pending，下一次可以重投。宁可重投，不能丢。
     *
     * <p>读不出来的坏数据当场丢弃，不抛：它不该让这条会话再也收不到任何留言，
     * 也不该每轮被重新读出、永远等不到 ack。
     *
     * @param sessionId 目标会话
     * @return 待落库的留言，按入队顺序
     */
    public List<AgentNoteDelivered> drain(SessionId sessionId) {
        String queue = queueKey(sessionId);
        String pending = pendingKey(sessionId);

        // 把 queue 全部挪进 pending，一步一条、每步原子（RPOPLPUSH）：崩在中间时，
        // 挪过去的在 pending，没挪的还在 queue，一条不丢。
        // 从 queue 队尾取、压进 pending 左端，出来正好是入队顺序。
        while (rightPopAndLeftPush(queue, pending) != null) {
            // 只负责挪，解析统一在下面做
        }

        // 读 pending 但不删，落库成功后由 ack 删；上次崩在中间、未确认的也在这里。
        List<String> raw = redis.opsForList().range(pending, 0, -1);
        List<AgentNoteDelivered> notes = new ArrayList<>();
        for (String payloadJson : raw == null ? List.<String>of() : raw) {
            try {
                notes.add((AgentNoteDelivered) codec.decode(EventType.AGENT_NOTE_DELIVERED, payloadJson));
            } catch (RuntimeException e) {
                // 坏数据当场丢掉，而不是跳过：留着它每轮都会被读出来、报同一个警告，
                // 而且永远等不到 ack（ack 只删得掉能解码的那些）。
                log.warn("会话 {} 的留言里有一条读不出内容的，已丢弃", sessionId, e);
                redis.opsForList().remove(pending, 1, payloadJson);
            }
        }
        return notes;
    }

    /**
     * 查看还排着队的留言，不取走，用于回答“我发的留言到哪了”。
     *
     * <p>只返回 queue，不含 pending：pending 里的留言正在被投递、下一秒就成为事实，
     * 混进“还在等”里比不返回更让人困惑。这个查询不为留言落事件，因为入队请求不持有
     * 执行租约；缺的只是“还在不在队列里”。
     *
     * @param sessionId 目标会话
     * @return 仍在队列里的留言，按入队顺序
     */
    public List<AgentNoteDelivered> pendingInQueue(SessionId sessionId) {
        List<String> raw = redis.opsForList().range(queueKey(sessionId), 0, -1);
        List<AgentNoteDelivered> notes = new ArrayList<>();
        for (String payload : raw == null ? List.<String>of() : raw) {
            try {
                notes.add((AgentNoteDelivered) codec.decode(EventType.AGENT_NOTE_DELIVERED, payload));
            } catch (RuntimeException e) {
                // 读不出来的就跳过：这个方法是只读的，不该顺手清理别人的队列。
                log.warn("会话 {} 的留言队列里有一条读不出内容的", sessionId, e);
            }
        }
        return notes;
    }

    /**
     * 落库成功，把它们从 pending 里清掉。
     *
     * <p>按值删（{@code LREM}）而不是按下标：编解码确定，同一条留言编出的 JSON
     * 逐字节相同，可以当身份用。
     *
     * @param sessionId 目标会话
     * @param delivered 已落库的留言
     */
    public void ack(SessionId sessionId, List<AgentNoteDelivered> delivered) {
        String pending = pendingKey(sessionId);
        for (AgentNoteDelivered note : delivered) {
            redis.opsForList().remove(pending, 1, codec.encode(note).payload());
        }
    }

    /**
     * 落库失败：把它们从 pending 挪回 queue，下一轮还有机会。
     *
     * <p>压回队首而不是队尾：它们是更早发的，该排在新留言前面。
     *
     * @param sessionId 目标会话
     * @param notes     落库失败的留言
     */
    public void requeue(SessionId sessionId, List<AgentNoteDelivered> notes) {
        String queue = queueKey(sessionId);
        String pending = pendingKey(sessionId);
        for (AgentNoteDelivered note : notes) {
            String payload = codec.encode(note).payload();
            redis.opsForList().remove(pending, 1, payload);
            redis.opsForList().leftPush(queue, payload);
        }
    }

    /**
     * 会话被丢弃时清掉它的两条队列。
     *
     * <p>不清就是永久泄漏：会话没了，再没有哪一轮会来 {@code drain}，而 pending 区
     * 刻意不设 TTL（见 {@link #pendingKey}）。代价是发留言的人以为送到了，
     * 但收的人已经看不到那条会话。
     *
     * @param sessionId 被丢弃的会话
     */
    public void discard(SessionId sessionId) {
        redis.delete(List.of(queueKey(sessionId), pendingKey(sessionId)));
    }

    /**
     * Redis 的 RPOPLPUSH。
     *
     * <p>单独包一层是因为它已废弃（推荐 {@code move}），而 {@code move} 返回构建器
     * 而不是被移动的元素，这里恰恰要那个值。
     */
    @SuppressWarnings("deprecation")
    private String rightPopAndLeftPush(String source, String destination) {
        return redis.opsForList().rightPopAndLeftPush(source, destination);
    }

    private static String queueKey(SessionId sessionId) {
        return PREFIX + sessionId.value();
    }

    /**
     * 已取走、还没确认落库的那些。
     *
     * <p>刻意不设 TTL：设了的话，崩在中间的留言会被悄悄清掉，而这正是 pending 区
     * 想防的事。堆着能看见，丢了看不见。
     */
    private static String pendingKey(SessionId sessionId) {
        return PREFIX + sessionId.value() + ":pending";
    }
}
