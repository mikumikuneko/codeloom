package com.codeloom.app.recovery;

import com.codeloom.app.turn.SessionWriter;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventStore;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.ToolCallLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 进程启动后收拾上一次未能收场的会话：逐个尝试获取租约，获取到就把执行到一半的工具调用
 * 补写成 {@code ToolInterrupted}；获取不到说明有实例仍在运行，跳过。
 *
 * <p>补写这条事实是为了防止重放：那个调用可能已经改过文件、跑过构建，重跑就是二次修改。
 * 模型看到该事件后会先确认现状，安全性已经足够，因此不做自动续跑 —— 本项目部署在宿主机、
 * 有人值守，用户说一句"接着"即可。
 *
 * <p>不需要另建心跳检测：租约过期本身就意味着持有它的实例已经不在，这正是
 * {@code ExecutionLease} 的设计。
 *
 * <p>理由见 {@code docs/decisions/architecture/2026-09-28-crash-recovery-writes-facts-not-resumes.md}。
 */
@Component
public class CrashRecovery {

    private static final Logger log = LoggerFactory.getLogger(CrashRecovery.class);

    private final SessionRepository sessions;
    private final EventStore events;
    private final ExecutionLease leases;
    private final SessionWriter writer;

    public CrashRecovery(SessionRepository sessions,
                         EventStore events,
                         ExecutionLease leases,
                         SessionWriter writer) {
        this.sessions = sessions;
        this.events = events;
        this.leases = leases;
        this.writer = writer;
    }

    /** 应用就绪后跑一次；此时数据库和 Redis 都已可用。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinishedSessions() {
        // 扫描全部会话，不做状态过滤：是否要恢复由 recover() 判断（看有没有执行到一半的
        // 工具调用），空闲会话是空操作。不用"可能未完成的状态"白名单：新增状态会被静默
        // 漏掉，而漏掉恢复会让一个执行到一半的调用被重新执行
        List<Session> candidates = sessions.findAll();
        if (candidates.isEmpty()) {
            return;
        }
        log.info("启动检查 {} 条会话，逐个确认有没有需要恢复的", candidates.size());
        for (Session session : candidates) {
            try {
                recover(session);
            } catch (RuntimeException e) {
                // 一条恢复失败不该拖住其他的 —— 剩下的还能救
                log.warn("恢复会话 {} 时出错，跳过它继续", session.id(), e);
            }
        }
    }

    /**
     * 恢复一条会话。
     *
     * <p>包级可见是为了单独测试：上面的扫描会遍历整个库，测试不应使用它。
     *
     * @param session 待检查的会话
     */
    void recover(Session session) {
        Optional<LeaseToken> acquired = leases.tryAcquire(session);
        if (acquired.isEmpty()) {
            // 租约被持有说明那棵树有人管，跳过。注意持有者可能是同一个人的另一条会话，
            // 因为它们的树是共用的
            return;
        }
        LeaseToken token = acquired.get();
        List<String> interrupted;
        try {
            interrupted = unfinishedToolCalls(session.id());
            if (!interrupted.isEmpty()) {
                writer.interruptUnfinished(session, interrupted, token);
            }
            if (!interrupted.isEmpty()) {
                log.info("会话 {} 有 {} 个执行到一半的工具调用，已补写成 ToolInterrupted",
                        session.id(), interrupted.size());
            }
        } finally {
            leases.release(token);
        }
    }

    /**
     * 执行到一半的调用：有 {@code ToolCallRequested}，却没有对应的结束事实。
     *
     * <p>本方法不做 switch：每个事件“把调用推到哪一步”由 {@code ToolCallLifecycle} 的
     * 穷尽 switch 回答（新增类型在那里编译不过），这里只把那一步翻译成“加入名单 / 移出
     * 名单”。这是筛选而不是映射 —— 漏掉一组判据只会少选，不会把一个调用算错。
     *
     * <p>{@code ToolApprovalRequested} 绝不能进名单："正等人批"与"崩溃时执行到一半"
     * 都表现为有 requested、没有收尾，但前者还没跑。混入后，重启会把
     * {@code AWAITING_APPROVAL} 经 {@code ToolInterrupted} 映成 {@code THINKING}，
     * 而那是非法迁移，会话会被打成 {@code FAILED} —— 一次重启就把等待中的批准变成错误。
     * 名单要的是 {@code ToolApprovalResolved}：批准后重新加入，拒绝后移除。
     */
    private List<String> unfinishedToolCalls(SessionId sessionId) {
        Set<String> pending = new LinkedHashSet<>();
        for (StoredEvent stored : events.readAll(sessionId)) {
            Event event = stored.event();
            // "这条事件把调用推到哪一步"由域层统一说明（见 ToolCallLifecycle），
            // 这里只判断那一步对名单意味着什么；漏掉一种终局在那里编译不过
            ToolCallLifecycle.openedBy(event).ifPresent(pending::add);
            ToolCallLifecycle.closedBy(event).ifPresent(pending::remove);

            // 挂起等人批：调用还没跑，不算"执行到一半"；状态停在 AWAITING_APPROVAL，
            // 重启后那条批准仍应可点
            ToolCallLifecycle.approvalAskedBy(event).ifPresent(pending::remove);

            // 批准后必须重新加入：否则进程死在上一条与真正执行之间的缝隙里时，这个调用会
            // 被安静漏掉，模型永远不知道它批准过的调用没跑。拒绝则是结束，不再加入
            ToolCallLifecycle.approvalGrantedBy(event).ifPresent(pending::add);
            ToolCallLifecycle.approvalRefusedBy(event).ifPresent(pending::remove);
        }
        return List.copyOf(pending);
    }
}
