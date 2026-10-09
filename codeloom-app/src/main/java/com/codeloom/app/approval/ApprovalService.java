package com.codeloom.app.approval;

import com.codeloom.app.turn.SessionWriter;
import com.codeloom.app.turn.TurnBroadcaster;
import com.codeloom.app.turn.TurnExecutor;
import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolRejected;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.SessionRepository;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.SessionState;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 答复一次挂起的审批，然后让那条会话接着跑。
 *
 * <h2>为什么这里要自己抢一次租约</h2>
 * 落 {@code ToolApprovalResolved} 是**写事件流**，而 {@code EventStore} 的每个写方法
 * 都要求 {@link LeaseToken}（fencing 保护，见那份接口的类注释）。答复请求是个普通
 * HTTP 请求、不持有任何执行权 —— 所以它得像 {@code CrashRecovery} 那样，
 * **临时抢一次**：抢到 → 落事件 → 放掉 → 再让执行器自己抢着跑。
 *
 * <p>抢不到是正常情况（会话刚被别的路径 resume 了），那时该做的是告诉调用方
 * "它现在正忙"，而不是硬写进去。
 *
 * <h2>为什么答复之后要 resume 而不是等用户再发话</h2>
 * 那一轮是**为了等人批才停下的**，不是用户想停。答复一到就该接着走 ——
 * 让用户再敲一句"继续"是多余的仪式，而且那一句会作为新的用户消息进上下文，
 * 把"这本来是在等答复"这件事冲淡。
 *
 * <p>**但拒绝、而且他没留下指示时是例外**：那时候不接着跑，而是把这一轮停在"等用户说话"。
 * Claude Code 也是直接中止整个回合 —— 用户把这件事叫停了、又没说下一步怎么办，
 * 让模型自己猜一个做法往下跑多半是白烧一轮。
 * 详见 {@link #resolve} 里那段注释。
 */
@Component
public class ApprovalService {

    private final SessionRepository sessions;
    private final SessionWriter writer;
    private final ExecutionLease leases;
    private final TurnExecutor executor;
    private final TurnBroadcaster broadcaster;

    public ApprovalService(SessionRepository sessions,
                           SessionWriter writer,
                           ExecutionLease leases,
                           TurnExecutor executor,
                           TurnBroadcaster broadcaster) {
        this.sessions = sessions;
        this.writer = writer;
        this.leases = leases;
        this.executor = executor;
        this.broadcaster = broadcaster;
    }

    /**
     * 记下答复，然后续跑。
     *
     * @param resolvedByUserId 谁批的。**在 controller 里由登录态得到** —— 那是"平等协作"下
     *                         唯一能说清责任的东西，不能让调用方随便填。记 **id 不是用户名**：
     *                         追责要的是身份，而用户名会变 —— 唯一不等于它是身份
     *                        （见 {@link ToolApprovalResolved}）
     */
    @Transactional
    public void resolve(SessionId sessionId, String callId, boolean approved,
                        UserId resolvedByUserId, String reason) {
        // 会话要先读出来：租约是按它所在的**那棵树**抢的（见 ExecutionLease）
        Session session = sessions.findById(sessionId).orElseThrow(
                () -> new IllegalArgumentException("会话不存在：" + sessionId));

        Optional<LeaseToken> acquired = leases.tryAcquire(session);
        if (acquired.isEmpty()) {
            // "正在执行中"现在可能是**同一个人的另一条会话**在执行 —— 它们共用一棵树
            throw new IllegalStateException("这条会话正在执行中，等它跑完再答复");
        }

        LeaseToken token = acquired.get();
        // 这一轮是**就此停住**，还是接着跑。见下面那段注释
        boolean stopHere;
        try {
            SessionWriter.Written written = writer.append(
                    session, new ToolApprovalResolved(callId, approved, resolvedByUserId, reason), token);
            // ★ **必须广播出去。** 漏发的话那条答复写进了库、也醒了执行器，
            //   却**从来没送到界面上** —— 点了「批准」的那条会一直停在"等你批准"，
            //   因为它等的那个 ToolApprovalResolved 只活在库里。
            //
            //   这不算"少了一行提示"：用户看到的是"我批了，但界面没反应"，
            //   而那一轮其实已经在跑了 —— 于是他再点一次，或者以为按钮坏了。
            written.events().forEach(broadcaster::publish);

            SessionWriter.Written latest = written;
            if (!approved) {
                // **拒绝 = 这次调用到此为止**，得给它记一条收尾。
                //
                // 少了它会怎样：所有消费端判"这次调用结束了没有"，用的都是同一句话 ——
                // 有没有一条收尾记录。于是界面上那条调用会永远停在"正在跑"的样子
                // （实际用出来的症状：拒绝之后那一行一直闪），
                // 而事件流里也留下一个答不出"它后来怎么了"的调用。
                //
                // 不带理由：那是"决定"的一部分，已经在上面那条答复里了。见 ToolRejected
                latest = writer.append(written.session(), new ToolRejected(callId), token);
                latest.events().forEach(broadcaster::publish);
            }

            // **拒绝、而且没留下指示 → 这一轮不接着跑。**
            //
            // Claude Code 就是这么分的：留了话（feedback）就把它附进工具结果、接着走；
            // 没留话就**把整个回合中止掉**。理由是那种情况下模型的处境：用户把这件事
            // 叫停了，还没说下一步怎么办 —— 让它自己猜一个做法往下跑多半白烧一轮，
            // 而他叫停的往往正是那个方向。
            //
            // 停下来的方式：`abortTurn` 只把轮次号推一格（这一轮对他已经结束了，
            // 下一句话该是新的一轮），**不打 checkpoint** —— 那一轮没有新的产出位置，
            // 可回的点还是挂起时那条（它就在这次批准之前）
            stopHere = !approved && blank(reason);
            if (stopHere) {
                // 用 latest.session()，不是最上面那个快照：状态已经被 append 推到 WAITING_USER 了，
                // 拿旧的那份去收尾会再落一条一模一样的迁移事件。
                // commitSha 传 null：这一轮没有新的产出位置可打（要回退的话，点还是挂起时那条）
                SessionWriter.Written closed = writer.abortTurn(latest.session(), null,
                        SessionState.WAITING_USER, "REJECTED", token);
                // ★ **也必须广播。** 漏发的话状态迁移只落在库里，
                //   于是界面永远停在"停下来等你批准一次调用"，而那一轮其实已经收尾了。
                //   只要落库而没广播，用户看到的就是"我操作了，界面没反应" ——
                //   和 TurnExecutor.apply(...) 是同一条规矩
                closed.events().forEach(broadcaster::publish);
            }
        } finally {
            // 先把自己的锁放掉再叫执行器 —— 它自己会去抢，而我们持有的话它只会拿到 Busy，
            // 于是这次答复**静默地什么都没干**（用户以为批了，其实那一轮根本没被唤醒）
            leases.release(token);
        }

        if (!stopHere) {
            executor.resume(sessionId);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
