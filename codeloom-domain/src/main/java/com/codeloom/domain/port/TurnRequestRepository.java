package com.codeloom.domain.port;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;

/**
 * Agent 请求的幂等键 —— 挡住"同一个请求被处理两次"。
 *
 * <h2>为什么需要它</h2>
 * {@code POST /messages} 是**阻塞**的：它要等这一轮跑完才返回，可能是几十秒。
 * 于是客户端超时重试是大概率事件，用户双击「发送」也是。而每多处理一次，
 * 就是**多花一次钱、工作区被多改一遍** —— 后者尤其难受，因为它不可逆。
 *
 * <h2>为什么是"占用"而不是"查询"</h2>
 * 接口只有一个 {@link #claim}：它同时完成"查过了吗"和"记下这次"，靠数据库的
 * 唯一约束保证原子。分成"先查再写"两步的话，两个并发的重试会同时查到"没有"，
 * 然后各写一次 —— 那正是这个接口要挡的事。
 *
 * <h2>为什么不用 Redis</h2>
 * 它得给出**持久**的答案：用户重试往往就是因为服务端刚抖了一下（甚至刚重启过），
 * 而那正是内存里的键会没掉的时刻。
 */
public interface TurnRequestRepository {

    /**
     * 认领一次请求。
     *
     * @param clientMessageId 客户端生成的请求标识。**同一句话重试必须带同一个值** ——
     *                        每次重试都新生成一个的话，这一层就等于不存在
     * @return {@code true} = 这是第一次见到它，可以照常跑；
     *         {@code false} = 已经处理过了，**别跑第二轮**
     */
    boolean claim(SessionId sessionId, String clientMessageId);

    /**
     * 把这条会话占过的幂等键清掉 —— 它是「丢弃会话」的三分之一。
     *
     * <p>不清的话这些行会永远留着：会话都没了，再也没有人会带同一个
     * {@code clientMessageId} 来问"这个请求处理过了吗"。
     */
    void discard(SessionId sessionId);

    /**
     * 清掉**这个人在这个项目里**所有会话占过的幂等键 —— 「退出项目」的一步。
     *
     * <p>和 {@link #discard} 同一件事换了个范围。同样**必须跑在会话行被删之前**
     * （它是靠 {@code session} 表定位的），见应用层的 {@code ProjectDeparture}。
     */
    void discardByProjectAndOwner(ProjectId projectId, UserId ownerId);
}
