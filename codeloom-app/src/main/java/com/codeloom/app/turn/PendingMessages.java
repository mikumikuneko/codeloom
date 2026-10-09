package com.codeloom.app.turn;

import com.codeloom.domain.session.SessionId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用户说了、但**这一轮还轮不到**的话。
 *
 * <h2>它为什么存在</h2>
 * 参考实现（Claude Code）里，Claude 干活的时候你**照样能打字发送**：那条消息排着队，
 * 在对话里显示成灰的，等轮到自己了再交给模型。不这么做的话，一轮在跑时发消息要回 409，
 * 而**审批挂着时发消息会把整条会话打成 FAILED**
 *（状态机只允许 {@code AWAITING_APPROVAL → WAITING_USER}）。
 *
 * <h2>为什么消息不直接落库，而是先排在这里</h2>
 * 因为**追加事件要持有租约**，而租约正被那一轮握着。所以排队中的这条只能先放在内存里，
 * 等那一轮收尾、把租约交出去之前，由**它**替我们落库（见 {@code TurnExecutor} 里的排空）。
 *
 * <p>代价要说清楚：**这中间服务重启的话，排着的这句话就没了**，
 * 而调用方已经回了一句"收下了"。窗口是一轮的时间（通常几秒到几分钟）。
 * 想让它扛住重启就得让它落库，而那需要租约 —— 也就是需要那一轮来写。
 *
 * <p>放在内存里还意味着它**跟着这一个实例走**。多实例部署时，
 * 排队的那句话必须在**持有那棵树租约的那个实例**上被排空 —— 而现在
 * 发送请求和跑那一轮的实例未必是同一个。这是同一个已知缺口，一起记在这儿。
 *
 * <h2>还有一个缺口：被**别人**占着租约的那些</h2>
 * 排空发生在"一轮收尾"的那一刻，而那一步只看**它自己那条会话**的队。
 * 但租约是**树**级的 —— 同一个人在这个项目里的**另一条**会话跑着的时候，
 * 你发给这条的话也会被排在这里，而**没有任何人会来叫醒它**：
 * 那棵树的下一次收尾属于另一条会话，它不看这条队列。
 *
 * <p>结果不是"丢了"，是"晚"：那句话要等**这条会话下次有人说话**才轮得到。
 * 修法是在收尾时把同一棵树上所有排着队的会话都叫醒一遍（按
 * {@code session.workspaceId()} 找），但那意味着"替用户开一轮他这次没触发的会话"，
 * 是个要单独想清楚的动作。先记在这儿，没做。
 */
@Component
public class PendingMessages {

    private final Map<SessionId, List<String>> waiting = new ConcurrentHashMap<>();

    /** 收下一句话，排在队尾。**同一句可以重复排** —— 那是用户说了两遍，不是重复请求。 */
    public void enqueue(SessionId sessionId, String text) {
        waiting.computeIfAbsent(sessionId, id -> new CopyOnWriteArrayList<>()).add(text);
    }

    /**
     * 取走这一条会话排着的全部。
     *
     * <p>**取走并清空**，不是"看一眼"：同一条消息只该被交付一次，
     * 而"看一眼"和"交付"之间只要有人插进来，就会交付两遍。
     */
    public List<String> drain(SessionId sessionId) {
        List<String> taken = waiting.remove(sessionId);
        return taken == null ? List.of() : List.copyOf(taken);
    }

    /** 这句被收下之后、还没轮到它的话，一共有几句。 */
    public int size(SessionId sessionId) {
        List<String> queued = waiting.get(sessionId);
        return queued == null ? 0 : queued.size();
    }

    /**
     * 会话没了（被丢弃、退出项目）时，把排着的丢掉。
     *
     * <p>不丢的话它们会一直挂在这个 map 里，而且**永远等不到那一轮** ——
     * 那条会话已经没有人会去跑了。这是一处不会有人注意到的内存泄漏。
     */
    public void forget(SessionId sessionId) {
        waiting.remove(sessionId);
    }
}
