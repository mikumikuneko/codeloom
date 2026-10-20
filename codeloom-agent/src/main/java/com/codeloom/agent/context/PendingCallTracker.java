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
 * 尾部那次已批准、但还没补跑的调用。
 *
 * <p>它处理“挂起等待批准 → 用户批准 → 进程退出”这条路径：这次调用在历史里没有结局，
 * 而尾部最后一条改变对话形状的事件是批准答复。
 *
 * <p>状态跟着事件走：记录尾部事件、有结局的调用、每个调用的最后一次请求。回滚时按 seq 砍掉
 * 切点之后的部分；“有结局”那张表必须保留，因为调用 id 由模型生成、可能跨时间线重复，
 * 旧收尾会把新批准的调用误判为已结束。
 */
final class PendingCallTracker implements Derived {

    /** 调用 id → 最后一次请求及其 seq。 */
    private final Map<String, Request> requests = new LinkedHashMap<>();

    /** 已有结局的调用 id → 收尾记录的 seq。 */
    private final Map<String, Long> settled = new HashMap<>();

    /** 尾部最后一条改变对话形状的事件；白名单之外的事件都会覆盖它。 */
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
            // 哪种事件终结一次调用由域层统一判定（见 ToolCallLifecycle），这里不再重复列举。
            ToolCallLifecycle.closedBy(event).ifPresent(callId -> settled.put(callId, stored.seq()));
        }

        if (!benignAtTheTail(event)) {
            shapeTail = event;
        }
    }

    /** 那条已批准、但还没补跑的调用；没有就是空。 */
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

    /** 一次请求及其 seq；回滚按 seq 砍。 */
    private record Request(ToolCall call, long seq) {
    }

    /**
     * 尾部这些事件不改变对话形状，不覆盖 {@code shapeTail}。
     * 白名单之外的一律覆盖，因此回滚会自然让形状尾部失效。
     */
    private static boolean benignAtTheTail(Event event) {
        return event instanceof SessionStateChanged
                || event instanceof TurnTokensUsed
                || event instanceof CheckpointCreated
                || event instanceof ContextCompacted;
    }
}
