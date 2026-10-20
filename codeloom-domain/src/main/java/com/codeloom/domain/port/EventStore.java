package com.codeloom.domain.port;

import com.codeloom.domain.event.EphemeralEvent;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.session.SessionId;

import java.util.List;
import java.util.OptionalInt;

/**
 * 事件存储。append-only，永不修改、永不删除；整条会话的删除不在本接口，走单独的
 * {@code EventDiscard}，删除会让 seq 留下空洞，读路径不假设连续。每个写方法都强制要求 {@link LeaseToken}：
 * 这是 fencing token 的落地位置，也是「锁过期后僵尸写入者被挡住」的唯一实现点；token
 * 必填而非可选，编译器要求每个调用方显式提供。返回整个信封而不是 seq：广播需要
 * {@link StoredEvent}，订阅者还要知道它发生在什么时候；落库时间只有存储层知道，调用方
 * 自编会让「推出去的」与「之后读回来的」时间不一致，断线重连时才显形。事件流是系统的
 * 事实来源：回放、断线补齐、审计、崩溃恢复都建立在它上面。见
 * {@code docs/decisions/architecture/2026-09-24-one-event-log-as-the-source-of-truth.md}
 * 与 {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
 */
public interface EventStore {

    /**
     * 追加一条事件。
     *
     * @param sessionId 会话
     * @param event     事件
     * @param token     执行租约凭据，必须属于这条会话；fencing token 校验在这里落地
     * @return 落库后的事件信封，含存储层分配的全局单调 seq 与落库时间
     * @throws IllegalArgumentException token 不属于这条会话
     * @throws StaleLeaseException      token 已失效，说明这棵树已被别的实例接管
     */
    StoredEvent append(SessionId sessionId, PersistentEvent event, LeaseToken token);

    /**
     * 批量追加。同一事务，要么全成要么全不成。
     *
     * @param sessionId 会话
     * @param events    事件
     * @param token     执行租约凭据，必须属于这条会话
     * @return 与入参顺序对应的信封；空入参返回空列表
     * @throws IllegalArgumentException token 不属于这条会话
     * @throws StaleLeaseException      token 已失效
     */
    List<StoredEvent> append(SessionId sessionId, List<PersistentEvent> events, LeaseToken token);

    /**
     * 拉取 seq 之后的事件，用于断线重连补齐。
     *
     * <p>易失事件不在其中，而且没有别的地方补它：断线期间正在生成的那半截内容会丢
     * （见 {@link EphemeralEvent}），一轮的完整回复随后作为 {@code AssistantMessage} 落库。
     *
     * @param sessionId 会话
     * @param afterSeq  只返回 seq 大于它的事件
     * @param limit     最多返回多少条
     * @return 事件信封
     */
    List<StoredEvent> readAfter(SessionId sessionId, long afterSeq, int limit);

    /**
     * 拉取全量事件，用于回放。
     *
     * @param sessionId 会话
     * @return 该会话的全部事件
     */
    List<StoredEvent> readAll(SessionId sessionId);

    /**
     * 这条会话最后一次服务商报的上下文大小（token）；没有就是空。
     *
     * <p>它是要不要压缩那个判断的一手读数：估算器按字符算，比例照英文定、系统性低估
     * 中文，所以真正决定压缩的通常是这个数（见 {@code ContextCompactor}）；有了它就不必
     * 先读整条流。给的是读数本身而不是那条事件：调用方只要这个数，交出整个
     * {@code TurnTokensUsed} 等于把它长什么样也变成契约。读数覆盖不完整：一轮没调过
     * 模型（被取消、或压根没走完）时不会写 {@code TurnTokensUsed}；如果最后一条开启事件
     * 比最后一条用量事件还新，返回空 —— 调用方要能接受「这次问不出来」，不能拿上一轮的
     * 读数当成这一轮的。见
     * {@code docs/decisions/architecture/2026-10-10-compaction-looks-at-the-reading-first.md}。
     *
     * @param sessionId 会话
     * @return 最后一次读数；没有时为空
     */
    OptionalInt lastContextTokens(SessionId sessionId);
}
