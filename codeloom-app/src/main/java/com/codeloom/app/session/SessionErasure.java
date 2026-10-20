package com.codeloom.app.session;

import com.codeloom.domain.port.EventDiscard;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.port.TurnRequestRepository;
import com.codeloom.domain.session.SessionId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 丢弃一条会话时删除事件流、幂等键和会话行，三者在同一个事务里。
 *
 * <p>拆成独立 bean 是因为 {@code @Transactional} 只在跨 bean 调用时生效，与
 * {@code SessionWriter}、{@code ProjectMerger} 同理。
 *
 * <p>三者必须一起删：只删一部分会留下无法修复的残留 —— 会话行没了，再也没有入口会去碰
 * 它的事件或幂等键，那些行会永远留着，看上去像某条会话还活着。顺序只是"先清痕迹、
 * 最后删身份"，事务使顺序不影响结果。
 *
 * <p>不碰工作区：树属于「人 + 项目」（见 {@code WorkspaceId}），"丢掉对话但代码留着"
 * 由此成立。
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

    /**
     * @param sessionId 要抹掉的会话
     */
    @Transactional
    public void erase(SessionId sessionId) {
        events.bySession(sessionId);
        requests.discard(sessionId);
        sessions.discard(sessionId);
    }
}
