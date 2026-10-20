package com.codeloom.app.turn;

import com.codeloom.domain.session.SessionId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用户已经发出、但这一轮还轮不到的消息。
 *
 * <p>一轮在跑或正挂着审批时，消息照样被受理并排在这里，等持有租约的那一轮收尾时落库；
 * 否则要么回 409，要么（审批挂着时）让状态机把会话打成 {@code FAILED}。
 *
 * <p>先放内存是因为追加事件要持有租约，而租约正在那一轮手里。代价是这期间服务重启会丢掉
 * 排着的消息，而调用方已经收到"收下"；窗口是一轮的长度。要让消息扛住重启就得先落库，
 * 而那同样需要租约。队列也跟随实例：多实例时排空必须发生在持有那棵树租约的实例上。
 *
 * <p>已知缺口：租约是树级的，同一人的另一条会话跑着时，发给这条的消息会被排住，
 * 直到这条会话下次有人说话才被排空 —— 是晚，不是丢。修复需要在树收尾时叫醒同一棵树上
 * 所有有队列的会话，等于替用户启动一轮，尚未实现。
 */
@Component
public class PendingMessages {

    private final Map<SessionId, List<String>> waiting = new ConcurrentHashMap<>();

    /**
     * 收下一句话，排在队尾。同一句可以重复排：那是用户说了两遍，不是重复请求。
     *
     * @param sessionId 目标会话
     * @param text      消息正文
     */
    public void enqueue(SessionId sessionId, String text) {
        waiting.computeIfAbsent(sessionId, id -> new CopyOnWriteArrayList<>()).add(text);
    }

    /**
     * 取走这条会话排着的全部消息，并清空队列：同一条消息只该交付一次，
     * 先看再交付会在两者之间插入时导致重复。
     *
     * @param sessionId 目标会话
     * @return 排着的消息，按入队顺序；没有时为空列表
     */
    public List<String> drain(SessionId sessionId) {
        List<String> taken = waiting.remove(sessionId);
        return taken == null ? List.of() : List.copyOf(taken);
    }

    /**
     * @param sessionId 目标会话
     * @return 还没轮到的话有几条
     */
    public int size(SessionId sessionId) {
        List<String> queued = waiting.get(sessionId);
        return queued == null ? 0 : queued.size();
    }

    /**
     * 会话不存在了（被丢弃、退出项目）时丢掉排着的消息；否则它们永远等不到那一轮，
     * 会一直留在内存里。
     *
     * @param sessionId 已消失的会话
     */
    public void forget(SessionId sessionId) {
        waiting.remove(sessionId);
    }
}
