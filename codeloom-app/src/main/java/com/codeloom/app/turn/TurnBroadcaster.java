package com.codeloom.app.turn;

import com.codeloom.agent.llm.StreamEvent;
import com.codeloom.agent.loop.AgentTurn;
import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.ReasoningDelta;
import com.codeloom.domain.session.SessionId;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 把一轮中正在生成的正文和思考推送给正在看的人。
 *
 * <p>{@link #liveListener} 只走易失通道；已落库的事件由 {@link EventAnnouncer} 在事务提交
 * 后宣布（两条通道分开的理由见那里：一条丢了是数据丢失，一条丢了只是少刷一段字）。
 * 独立出来是因为它只依赖一个组件，与执行器的租约、状态机、提交没有共享状态。
 */
@Component
public class TurnBroadcaster {

    /**
     * 流式增量攒到这么多字符推一次。发布是一次 Redis 往返加一次 JSON 编码，而回调跑在读取
     * 模型流的线程上；64 字符约十几 token，把往返次数降到原来的十几分之一，前端拿到的仍是
     * 不到一行的文字，看起来连续。
     */
    private static final int DELTA_FLUSH_CHARS = 64;

    private final EventAnnouncer announcer;

    public TurnBroadcaster(EventAnnouncer announcer) {
        this.announcer = announcer;
    }

    /**
     * 流式增量的去向：模型正在生成的正文和思考，各走一路。
     *
     * <p>只有增量帧走这条易失通道；工具调用的完成帧不进这里，它随后会作为
     * {@code ToolCallRequested} 落库并走持久通道，两条都发会让客户端看到两次。
     *
     * <p>回调在读取模型流的线程上同步执行，每次发布都是一次 Redis 往返；逐 token 发会让
     * 一段一千 token 的回复产生一千次往返，并拖慢读流本身。因此攒到
     * {@value #DELTA_FLUSH_CHARS} 字符发一次，并在每条非增量帧之前把缓存推出去，
     * 否则末尾半句会压到下一轮。粒度过小等于没省，过大则前端渲染一跳一跳。
     *
     * @return 喂给 {@code AgentTurn} 的回调；一轮一个，因为内部配额按一次模型调用重置，
     *         跨轮复用会算错
     */
    Consumer<StreamEvent> liveListener(SessionId sessionId) {
        LiveDeltas pending = new LiveDeltas();
        return streamEvent -> {
            if (streamEvent instanceof StreamEvent.TextDelta(String text1) && !text1.isEmpty()) {
                pending.text.append(text1);
                if (pending.text.length() >= DELTA_FLUSH_CHARS) {
                    flushText(sessionId, pending);
                }
                return;
            }
            if (streamEvent instanceof StreamEvent.ReasoningDelta(String text) && !text.isEmpty()) {
                pending.appendReasoning(text);
                if (pending.reasoning.length() >= DELTA_FLUSH_CHARS) {
                    flushReasoning(sessionId, pending);
                }
                return;
            }
            // 其他帧（一次调用结束、要执行工具）之前先推出缓存：否则末尾半句会一直等到
            // 下一条增量，而那可能已是本轮最后一句
            flushText(sessionId, pending);
            flushReasoning(sessionId, pending);
            if (streamEvent instanceof StreamEvent.Finished) {
                // 一次调用结束，思考配额归零：配额按每次调用计，一轮里可能有多次模型调用，
                // 各自落成自己的 AssistantMessage
                pending.reasoningPublished = 0;
            }
        };
    }

    /**
     * 一轮中积攒、尚未推送的流式增量。正文和思考各一个缓冲区，因为它们去界面上两个不同区域。
     */
    private static final class LiveDeltas {

        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();

        /**
         * 本次模型调用已经攒下的思考字符数。到 {@link AgentTurn#MAX_REASONING_CHARS} 停止接收，
         * 与落库截断同上限，避免"打字时看到三万字、刷新后只剩一万六"。
         *
         * <p>超限时不多发"已截断"标记：本轮结束后 {@code AssistantMessage} 会带完整且已截断的
         * 思考到达，前端用落库版本替换流式版本时标记自然出现；在流里再补一次是重复逻辑。
         */
        private int reasoningPublished;

        void appendReasoning(String piece) {
            int room = AgentTurn.MAX_REASONING_CHARS - reasoningPublished;
            if (room <= 0) {
                return;
            }
            String kept = piece.length() <= room ? piece : piece.substring(0, room);
            reasoning.append(kept);
            reasoningPublished += kept.length();
        }
    }

    private void flushText(SessionId sessionId, LiveDeltas pending) {
        if (pending.text.isEmpty()) {
            return;
        }
        String text = pending.text.toString();
        pending.text.setLength(0);
        announcer.announceEphemeral(sessionId, new AssistantDelta(text));
    }

    private void flushReasoning(SessionId sessionId, LiveDeltas pending) {
        if (pending.reasoning.isEmpty()) {
            return;
        }
        String text = pending.reasoning.toString();
        pending.reasoning.setLength(0);
        announcer.announceEphemeral(sessionId, new ReasoningDelta(text));
    }
}
