package com.codeloom.domain.port;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;

/**
 * Agent 请求的幂等键 —— 挡住同一个请求被处理两次。{@code POST /messages} 是阻塞的，
 * 要等这一轮跑完才返回，可能几十秒；客户端超时重试是大概率事件，用户双击「发送」也是。
 * 每多处理一次就多花一次钱、工作区被多改一遍，后者不可逆。只有一个 {@link #claim}：
 * 它同时完成「查过了吗」和「记下这次」，靠数据库唯一约束保证原子；先查再写会让两个
 * 并发重试都查到「没有」。不用 Redis：重试往往发生在服务端刚故障或重启之后，而那正是
 * 内存里的键会没掉的时刻。
 */
public interface TurnRequestRepository {

    /**
     * 认领一次请求。
     *
     * @param sessionId       会话
     * @param clientMessageId 客户端生成的请求标识；同一句话重试必须带同一个值，
     *                        每次重试都新生成一个的话，这一层就等于不存在
     * @return {@code true} = 第一次见到它，可以照常跑；{@code false} = 已经处理过，别跑第二轮
     */
    boolean claim(SessionId sessionId, String clientMessageId);

    /**
     * 把这条会话占过的幂等键清掉 —— 「丢弃会话」的三分之一。
     *
     * <p>不清的话这些行会永远留着：会话都没了，再也没有人会带同一个
     * {@code clientMessageId} 来问「这个请求处理过了吗」。
     *
     * @param sessionId 会话
     */
    void discard(SessionId sessionId);

    /**
     * 清掉这个人在这个项目里所有会话占过的幂等键 —— 「退出项目」的一步。
     *
     * <p>和 {@link #discard} 同一件事换个范围。同样必须跑在会话行被删之前（它靠
     * {@code session} 表定位），见应用层的 {@code ProjectDeparture}。
     *
     * @param projectId 项目
     * @param ownerId   要清掉其幂等键的人
     */
    void discardByProjectAndOwner(ProjectId projectId, UserId ownerId);
}
