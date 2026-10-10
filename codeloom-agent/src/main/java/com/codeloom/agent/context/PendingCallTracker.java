package com.codeloom.agent.context;

import com.codeloom.agent.llm.ToolCall;
import com.codeloom.domain.event.CheckpointCreated;
import com.codeloom.domain.event.ContextCompacted;
import com.codeloom.domain.event.Event;
import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.SessionStateChanged;
import com.codeloom.domain.event.StoredEvent;

import com.codeloom.domain.event.ToolApprovalResolved;
import com.codeloom.domain.event.ToolCallRequested;
import com.codeloom.domain.event.TurnTokensUsed;
import com.codeloom.domain.session.ToolCallLifecycle;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 上一轮里**批准了、但还没补跑**的那次调用。
 *
 * <p>它就是"挂起等人批 → 用户点了批准 → 进程在那之后退出了"那条路的入口：
 * 整段历史里那次调用没有结局，而尾部那只改变形状的事件正是批准答复。
 *
 * <p>这个状态是**跟着事件走**的（不是每次回头扫一遍历史）：尾部那只事件是不是批准答复、
 * 那个调用有没有结局、那个调用请求长什么样 —— 三件事各自记住就够了。
 *
 * <h2>回滚：两张表都按序号砍</h2>
 * "有结局"和"最后一次请求"各自带着落库序号，回滚时丢掉序号大于切点的那些 ——
 * 一次调用有没有结局，问的是**这条时间线上**有没有那条收尾记录。
 *
 * <p>"有结局"那张**不能省**：调用 id 是模型生成的，跨轮重复是可能的，于是被退掉的那条
 * 旧收尾会把新调用的批准压成"没有待补跑的" —— 用户点了批准，界面没反应。
 */
final class PendingCallTracker implements Derived {

    /** 每个调用 id 最后一次请求长什么样，以及它落在哪个序号上。 */
    private final Map<String, Request> requests = new LinkedHashMap<>();

    /** 已经有**结局**的那些调用（跑完了 / 被取消 / 被中断）→ 那条收尾记录的序号。 */
    private final Map<String, Long> settled = new HashMap<>();

    /**
     * 尾部那只**改变对话形状**的事件 —— {@link #benignAtTheTail} 那条白名单之外的事件
     * 都会把它改写。
     */
    private Event shapeTail;

    @Override
    public void accept(StoredEvent stored) {
        Event event = stored.event();
        if (event instanceof SessionRewound rewound) {
            long cut = Derived.cutOf(rewound);
            requests.values().removeIf(request -> request.seq() > cut);
            settled.values().removeIf(seq -> seq > cut);
        } else if (event instanceof ToolCallRequested(String id, String toolName, String argumentsJson)) {
            requests.put(id, new Request(new ToolCall(id, toolName, argumentsJson), stored.seq()));
        } else {
            // **"这一条是不是某次调用的终局"由域层那一处说**（见 ToolCallLifecycle）——
            // 这里不再自己列一遍事件类型。它认哪四种是终局、以及为什么，
            // 都写在那边；漏掉一种在那里编译不过
            ToolCallLifecycle.closedBy(event).ifPresent(callId -> settled.put(callId, stored.seq()));
        }

        if (!benignAtTheTail(event)) {
            shapeTail = event;
        }
    }

    /** 那条批准了、但还没补跑的调用。没有就是空。 */
    Optional<ToolCall> pendingApprovedCall() {
        if (shapeTail == null
                || !(shapeTail instanceof ToolApprovalResolved resolved)
                || !resolved.approved()) {
            return Optional.empty();
        }
        String callId = resolved.callId();
        if (settled.containsKey(callId)) {
            return Optional.empty();
        }
        Request request = requests.get(callId);
        return request == null ? Optional.empty() : Optional.of(request.call());
    }

    @Override
    public void clear() {
        requests.clear();
        settled.clear();
        shapeTail = null;
    }

    /** 一次请求和它落在流里的序号 —— 回滚按序号砍，见类注释。 */
    private record Request(ToolCall call, long seq) {
    }

    /**
     * 尾部那些**不改变对话形状**的事件。
     *
     * <p>白名单之外的一律改写 {@code shapeTail}，所以"回滚之后就清空了"这件事不用另写：
     * 回滚本身就是一条改变形状的事件。
     */
    private static boolean benignAtTheTail(Event event) {
        return event instanceof SessionStateChanged
                || event instanceof TurnTokensUsed
                || event instanceof CheckpointCreated
                || event instanceof ContextCompacted;
    }
}
