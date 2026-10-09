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
 * 待投递的 agent 留言 —— 别的会话发过来、还没被目标会话"领走"的那些。
 *
 * <h2>为什么是队列，而不是直接落成事件</h2>
 * {@code EventStore.append} 硬性要求一个 {@code LeaseToken}（那是 fencing 保护的落地位置），
 * 而发留言是个**普通 HTTP 请求**、不持有任何执行权 —— 它没有 token 可带。
 * 硬要落库就得给 {@code EventStore} 开一条"不需要 token 的写入路径"，而那份接口的类注释
 * 写着为什么不该开：**"如果一个写入路径可以不带 token，那它就是一个漏洞"**。
 *
 * <p>所以反过来：由**执行轮次**在开头把留言领走，再落成事件 —— 那时它正持有租约，
 * 走的是和其他事件完全一样的那条路。
 *
 * <p>代价是留言"晚一点"才成为事实（要等下一轮开跑）。但那本来就是它的归宿：
 * agent 只在轮次里活动，早落库也没有谁能看见它。
 *
 * <h2>为什么放 Redis 而不是内存</h2>
 * 发留言的请求落在那台机器，和跑这条会话的实例**不一定是同一台**。
 * 内存队列只在单实例下才成立。
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

    /** 投一条留言进队列。发的人立刻拿到返回，不必等目标会话什么时候开跑。 */
    public void enqueue(SessionId sessionId, AgentNoteDelivered note) {
        redis.opsForList().rightPush(queueKey(sessionId), codec.encode(note).payload());
    }

    /**
     * 把这条会话待投递的留言**挪到 pending 区**，等落库成功再来确认。
     *
     * <h2>为什么不是直接弹出来</h2>
     * 弹出（{@code leftPop}）是"至多一次"：实例死在"已经取走、还没落成事件"那一刻，
     * 这条留言就**永久没了**，连补救的机会都没有。而留言是"给 agent 的话" ——
     * 丢了之后，发的人以为送到了，收的人永远不知道。
     *
     * <p>所以改成**挪到另一个 list**：取走的动作和"确认收到"分开。这中间崩掉的话，
     * 留言还在 pending 里，下一次能被捞回来重投。宁可重投也不能丢 ——
     * 重投的副作用是"同一句话说两遍"，而丢的副作用是"这句话没说过"。
     *
     * <p>坏数据**当场丢掉而不是抛**：一条读不出来的东西不该让这条会话再也收不到任何留言，也不该每轮被重新读出来、永远等不到 ack。
     */
    public List<AgentNoteDelivered> drain(SessionId sessionId) {
        String queue = queueKey(sessionId);
        String pending = pendingKey(sessionId);

        // 一、把 queue 里的全部挪进 pending。一条一动、每一步都是原子的（RPOPLPUSH）——
        // 即便崩在这一段中间，已经挪过去的在 pending 里、没挪的还在 queue 里，一条不丢。
        //
        // 用 rightPopAndLeftPush 而不是 move()：后者返回的是一个**构建器**（MoveFrom），
        // 拿不到被移动的值。这个正是 Redis 的 RPOPLPUSH 本身，直接返回值。
        // 它标了 deprecated（推荐用 move），但 move 那个签名在"取值"这件事上反而绕。
        //
        // 方向：从 queue 的**队尾**取、压进 pending 的**左端**，恰好把顺序还原成入队的
        // 先后（queue 从左到右是早到晚，倒着取、从头压，出来就是正的）
        while (rightPopAndLeftPush(queue, pending) != null) {
            // 只负责挪，解析在下面统一做
        }

        // 二、读 pending（**不删** —— 落库成功后由 ack 删）。
        // 于是"上次崩在中间、还没确认"的那些也在这里，和这次的合并成一个有序列表，
        // 它们本来就该排在前面
        List<String> raw = redis.opsForList().range(pending, 0, -1);
        List<AgentNoteDelivered> notes = new ArrayList<>();
        for (String payloadJson : raw == null ? List.<String>of() : raw) {
            try {
                notes.add((AgentNoteDelivered) codec.decode(EventType.AGENT_NOTE_DELIVERED, payloadJson));
            } catch (RuntimeException e) {
                // 坏数据**当场丢掉**，而不是跳过：留着的话它每轮都会被读出来、报同一个警告，
                // 而且永远等不到 ack（ack 只删得掉能解码的那些）
                log.warn("会话 {} 的留言里有一条读不出内容的，已丢弃", sessionId, e);
                redis.opsForList().remove(pending, 1, payloadJson);
            }
        }
        return notes;
    }

    /**
     * 看一眼还排着队的（**不取走**）—— 回答「我发的留言到哪了」。
     *
     * <h2>为什么只返回队列里的，不含 pending 区</h2>
     * pending 里的那些**正在被投递**（那一轮已经开跑、正要把它们落成事件）。
     * 把它们和"还排着队"的混在一起返回，用户看到的会是"它还在等"，而实际上它
     * 下一秒就成了事实 —— 那比不返回更让人困惑。
     *
     * <h2>为什么不为它落事件</h2>
     * 入队的请求**不持有执行租约**（这正是留言要先进队列的原因），所以它落不了事件 ——
     * 除非给 {@code EventStore} 开一条"不需要 token 的写入路径"，而那条路径不该存在。
     * 而真正缺的信息（谁发的、内容、什么时候投递的）在投递时那条
     * {@code AgentNoteDelivered} 里已经记全了，这里要补的只是「还在不在队列里」。
     */
    public List<AgentNoteDelivered> pendingInQueue(SessionId sessionId) {
        List<String> raw = redis.opsForList().range(queueKey(sessionId), 0, -1);
        List<AgentNoteDelivered> notes = new ArrayList<>();
        for (String payload : raw == null ? List.<String>of() : raw) {
            try {
                notes.add((AgentNoteDelivered) codec.decode(EventType.AGENT_NOTE_DELIVERED, payload));
            } catch (RuntimeException e) {
                // 读不出来的就跳过：这个方法是**只读**的，不该顺手清理别人的队列
                log.warn("会话 {} 的留言队列里有一条读不出内容的", sessionId, e);
            }
        }
        return notes;
    }

    /**
     * 落库成功了，把它们从 pending 里清掉。
     *
     * <p>用**值**来删（{@code LREM}）而不是按下标：编解码是确定的，同一条留言
     * 编出来的 JSON 逐字节相同，所以拿它当身份是可靠的。
     */
    public void ack(SessionId sessionId, List<AgentNoteDelivered> delivered) {
        String pending = pendingKey(sessionId);
        for (AgentNoteDelivered note : delivered) {
            redis.opsForList().remove(pending, 1, codec.encode(note).payload());
        }
    }

    /**
     * 落库失败：把它们**从 pending 挪回 queue**，下一轮还有机会。
     *
     * <p>压回队首而不是队尾 —— 它们是更早发的，该排在后面那些新留言前面。
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
     * 这条会话被丢弃了，它那两条队列跟着清掉。
     *
     * <p>不清的话，那些留言会一直躺在 Redis 里：会话都没了，再也没有哪一轮会来
     * {@code drain} 它们。而它们**没法靠 TTL 自己消失** —— pending 区刻意不设 TTL
     *（见 {@link #pendingKey}），所以那就是一处永久泄漏。
     *
     * <p>代价是发留言的人以为送到了、而收的人永远看不到了。这和"会话被丢弃"是同一件事，
     * 区别只是他看不到那条会话了。
     */
    public void discard(SessionId sessionId) {
        redis.delete(List.of(queueKey(sessionId), pendingKey(sessionId)));
    }

    /**
     * Redis 的 RPOPLPUSH。
     *
     * <p>单独包一层是因为它标了 {@code @Deprecated}（Spring 推荐改用 {@code move}），
     * 而 {@code move} 返回的是构建器 {@code MoveFrom} 而不是被移动的元素 ——
     * 我们这里恰恰要那个值。把注解的影响收在这一个方法里，别让它散到调用点。
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
     * <p>刻意**不设 TTL**：设了的话，"崩在中间"的留言会在一段时间后被悄悄清掉 ——
     * 那正是这个 pending 区想防的事。代价是极端情况下它会堆着，而那比丢消息好：
     * 堆着是能看见的（有 key、有长度），丢了是看不见的。
     */
    private static String pendingKey(SessionId sessionId) {
        return PREFIX + sessionId.value() + ":pending";
    }
}
