package com.codeloom.app.session;

import com.codeloom.domain.port.EventDiscard;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.TurnRequestRepository;
import com.codeloom.domain.session.SessionId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「丢弃一条会话」那三下删除，**在一个事务里**。
 *
 * <h2>为什么是一个独立的 bean</h2>
 * 因为 Spring 的 {@code @Transactional} 只在**跨 bean 调用**时生效 ——
 * 在同一个类里自己调自己拿不到代理，事务不会开。这和 {@code SessionWriter}、
 * {@code ProjectMerger} 是同一条理由：给事务边界一个明确的落点，
 * 而不是散落在调用方的自觉里。
 *
 * <h2>三样东西为什么必须一起</h2>
 * 一条会话在库里留下的东西有三处：事件流、幂等键、它自己那一行。
 * 只删掉一部分会留下**没有任何路径能修回来**的状态 ——
 * 会话都没了，谁也不会再去碰它的事件或幂等键，那些行于是永远住在那里，
 * 而且看上去像"某个会话还活着"。
 *
 * <p>顺序是"先清痕迹、最后抹掉身份"。事务保证它和别的顺序等价，所以这个顺序只是为了好读。
 *
 * <h2>它不碰工作区</h2>
 * 树属于「人 + 项目」（见 {@code WorkspaceId}），不属于任何一条会话 ——
 * 这正是"丢掉对话但代码留着"能成立的地方。
 */
@Component
public class SessionErasure {

    private final EventDiscard events;
    private final TurnRequestRepository requests;
    private final SessionRepository sessions;

    public SessionErasure(EventDiscard events,
                          TurnRequestRepository requests,
                          SessionRepository sessions) {
        this.events = events;
        this.requests = requests;
        this.sessions = sessions;
    }

    @Transactional
    public void erase(SessionId sessionId) {
        events.bySession(sessionId);
        requests.discard(sessionId);
        sessions.discard(sessionId);
    }
}
