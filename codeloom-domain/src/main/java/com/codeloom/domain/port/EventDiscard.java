package com.codeloom.domain.port;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;

/**
 * 删除一条会话的事件流 —— 全项目唯一一处 DELETE 事件的地方。不把它加到
 * {@link EventStore}：那个接口承诺 append-only，而那句承诺服务的是回放、断线补齐、
 * 崩溃恢复，全都建立在「已经写下的行不会再变」上。被丢弃的会话不适用这三件事：
 * 没有人会回放、没有一轮会崩溃在它上面，「谁改了代码」的答案在 git log（提交署名）里。
 * 删掉是用户明确要的；留着会一直占库。只删会话的事件；工作区属于「人 + 项目」，
 * 所以「丢弃对话但代码留着」成立。见
 * {@code docs/decisions/architecture/2026-09-30-discard-really-deletes.md}。
 */
public interface EventDiscard {

    /**
     * 删掉这条会话的全部事件。
     *
     * <p>调用方必须先确认这条会话没有在跑（持有它的树锁）：一边删、一边有一轮在往里写，
     * 会得到谁也说不清的结果。那道检查在应用层，见 {@code SessionService.discard}。
     *
     * @param sessionId 会话
     */
    void bySession(SessionId sessionId);

    /**
     * 删掉这个人在这个项目里所有会话的事件 —— 「退出项目」的一步。
     *
     * <p>和逐个会话删不是同一件事：那些会话马上就要一起消失，它们只是「要清掉的东西」
     * 的一类，没有独立意义；逐个删还会在一个已经删掉一半的项目上留下可观察的中间态，
     * 而这里所有删除在同一个事务里。
     *
     * <p>它同时覆盖「最后一个人退出」：那时这个人就是唯一剩下的成员，「他的会话」和
     * 「这个项目的会话」是同一批。类注释里的理由同样成立。
     *
     * <p>调用方必须先确认他的会话没有在跑，而且必须赶在会话行被删之前调它 —— 它靠
     * {@code session} 表定位，会话行没了它一条也匹配不上，而且不报错。见
     * {@code ProjectDeparture}。
     *
     * @param projectId 项目
     * @param ownerId   要清掉其会话的人
     */
    void byProjectAndOwner(ProjectId projectId, UserId ownerId);
}
