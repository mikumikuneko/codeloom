package com.codeloom.domain.port;

import com.codeloom.domain.event.EphemeralEvent;
import com.codeloom.domain.event.PersistentEvent;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.session.SessionId;

import java.util.List;

/**
 * 事件存储。append-only，永不修改、永不删除。
 *
 * <h2>为什么每个写方法都强制要求 {@link LeaseToken}</h2>
 * 这是 fencing token 的落地位置，也是"锁过期后僵尸写入者被挡住"的**唯一实现点**。
 * 把 token 做成必填参数而不是可选参数，是为了让编译器逼着每个调用方去想它 ——
 * 如果一个写入路径可以不带 token，那它就是一个漏洞。
 *
 * <h2>为什么返回的是整个信封而不是一个 seq</h2>
 * seq 是调用方唯一需要的东西（拿它当游标）。但**广播**需要
 * {@link StoredEvent} 这个完整信封：订阅者要看到的不只是"第 42 条"，
 * 还有它发生在什么时候。落库时间只有存储层知道（是它盖的），
 * 调用方要么拿回信封、要么只能自己编一个时间戳 —— 后者会让"推送出去的那条"
 * 和"后来从库里读回来的那条"在时间上不一致，而那种不一致在断线重连时才显形。
 *
 * <p>事件流是系统的事实来源：回放、断线补齐、审计、崩溃恢复全都建立在它上面。
 */
public interface EventStore {

    /**
     * 追加一条事件。
     *
     * @return 落库后的事件信封，含存储层分配的**全局单调** seq 与落库时间
     * @throws com.codeloom.domain.port.StaleLeaseException token 已失效（说明该会话已被别的实例接管）
     */
    StoredEvent append(SessionId sessionId, PersistentEvent event, LeaseToken token);

    /** 批量追加。同一事务，要么全成要么全不成，返回对应的信封。 */
    List<StoredEvent> append(SessionId sessionId, List<PersistentEvent> events, LeaseToken token);

    /**
     * 拉取 seq 之后的事件，用于**断线重连补齐**。
     *
     * <p>注意易失事件不在其中，而且**没有别的地方补它**：断线期间那半截正在生成的
     * 内容会丢（见 {@link EphemeralEvent}），一轮的完整回复随后作为
     * {@code AssistantMessage} 落库。
     */
    List<StoredEvent> readAfter(SessionId sessionId, long afterSeq, int limit);

    /** 拉取全量事件，用于**回放**。 */
    List<StoredEvent> readAll(SessionId sessionId);

    /** 当前最后一条事件的 seq；没有任何事件时返回 0。用于重连时确定初始游标。 */
    long lastSeq(SessionId sessionId);
}
