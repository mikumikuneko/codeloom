package com.codeloom.app.turn;

import com.codeloom.agent.llm.StreamEvent;
import com.codeloom.agent.loop.AgentTurn;
import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.ReasoningDelta;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.session.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 把一轮里产生的东西推给正在看的人 —— **两条通道，别混**。
 *
 * <ul>
 *   <li>{@link #publish} —— <b>持久事件</b>。落库之后才推，订阅者断线重连时能从库里补齐
 *       （SSE 的 {@code Last-Event-ID} 就是干这个的）。</li>
 *   <li>{@link #liveListener} —— <b>流式增量</b>。模型正在打字的那半截，不落库、不保证送达。</li>
 * </ul>
 *
 * <h2>为什么从 TurnExecutor 里拿出来</h2>
 * 这段只碰 {@link EventBus} 一个依赖，和执行器的租约、状态机、提交没有半点共享状态。
 *
 * <h2>广播一律尽力而为</h2>
 * 失败只记日志，绝不往外抛。理由两条通道一致 —— **数据本身已经安全了**：
 * 持久事件已经落库，推送失败只是"这一刻没推到"；流式增量本来就"不保证送达"。
 * 把这类会自愈的问题抛出去，会让 agent 循环以为是自己出错了，
 * 从而把一个能跑的 turn 判死。
 */
@Component
public class TurnBroadcaster {

    /**
     * 流式增量攒到这么多字符就推一次。
     *
     * <p>为什么是 64：一次发布是一次 Redis 往返 + 一次 JSON 编码，而这条回调跑在
     * 读模型流的线程上；64 个字符约十几 token，等于把往返次数降到原来的十几分之一，
     * 而前端每次拿到的是不到一行的文字 —— 看起来还是连续的。
     */
    private static final int DELTA_FLUSH_CHARS = 64;

    private static final Logger log = LoggerFactory.getLogger(TurnBroadcaster.class);

    private final EventBus bus;

    public TurnBroadcaster(EventBus bus) {
        this.bus = bus;
    }

    /**
     * 一条持久事件。
     *
     * <h2>调用点必须保证**事务已经提交**</h2>
     * 在事务里发的话，订阅者可能先看到一条、然后事务回滚 —— 于是所有人的界面上都出现了
     * 一条**并不存在**的事实，而库里没有。{@code SessionWriter.Written} 之所以把事件
     * 交回给调用方、而不是自己发，就是为了把这个时机摆在调用处看得见的地方。
     *
     * <h2>为什么它是 public</h2>
     * 凡是**写进了事件流、而人正看着**的地方，都该能调它。{@code ApprovalService}
     * 就是一处：它落了一条 {@code ToolApprovalResolved}，漏发的话用户点了「批准」之后
     * 那一行还停在"等你批准"，而命令其实已经在跑了。可见性收在 {@code app.turn} 里
     * 就会造成这种漏发，而它的症状不是报错，是"界面不动"，最容易被当成"按钮坏了"。
     */
    public void publish(StoredEvent event) {
        quietly("事件 seq=" + event.seq(), () -> bus.publish(event));
    }

    /**
     * 流式增量的去向：模型正在打字的那半截（正文和思考各走一路）。
     *
     * <p>只有**增量帧**走这条易失通道。工具调用的完成帧不进这里 ——
     * 它随后会作为 {@code ToolCallRequested} 落库并走持久通道广播，
     * 两条路都发的话客户端会看到两次。
     *
     * <h2>为什么要攒一下再发</h2>
     * 这个回调是**在读模型那条流上同步执行**的，而每一次发布都是一次 Redis 往返。
     * 逐 token 发的话，一段一千 token 的回复就是一千次往返，而且全部加在
     * "读流"这条路径上 —— 那会拖慢消费本身，也就拖慢了整轮。
     *
     * <p>攒到 {@value #DELTA_FLUSH_CHARS} 个字符发一次，并且在每一条非增量帧之前
     * 把攒着的推出去。粒度不能再小（等于没省），也不宜太大 ——
     * 一帧就是前端一次渲染，太大看起来会一跳一跳的。
     *
     * @return 喂给 {@code AgentTurn} 构造器的那个回调。**一轮一个** ——
     *         它内部的配额状态是按"一次模型调用"重置的，跨轮复用会算错
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
            // 别的帧（一次模型调用结束、有工具调用要执行）之前把攒着的推出去 ——
            // 不推的话，末尾那半句会一直压着等下一条增量，而那可能就是这一轮最后一句
            flushText(sessionId, pending);
            flushReasoning(sessionId, pending);
            if (streamEvent instanceof StreamEvent.Finished) {
                // 一次调用结束，**思考的配额归零**：配额是每次调用的，不是整轮的。
                // 一轮里可能调好几次模型（工具往返之间），每次都有自己的思考，
                // 也各自会落成一条自己的 AssistantMessage
                pending.reasoningPublished = 0;
            }
        };
    }

    /**
     * 一轮里积攒着、还没推出去的流式增量。
     *
     * <p>正文和思考**各自一个缓冲区**：它们去的是界面上两个不同的区域，
     * 混在一个 StringBuilder 里就没法分开发了。
     */
    private static final class LiveDeltas {

        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();

        /**
         * **本次模型调用**已经攒下的思考字符数。
         *
         * <p>到 {@link AgentTurn#MAX_REASONING_CHARS} 就不再收了。这和落库那边的截断是
         * 同一个上限，为的是不出现"正在打字时看到三万字、刷新后只剩一万六"。
         *
         * <p>超限时**不多发一个"已截断"的标记**：这一轮结束后 {@code AssistantMessage}
         * 会带着完整（且已截断）的思考到达，前端那时会用落库的版本替换掉流式的那份，
         * 标记自然就出现了。在流里再补一次，反而多一条要维护的重复逻辑。
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
        quietly("流式正文增量", () -> bus.publishEphemeral(sessionId, new AssistantDelta(text)));
    }

    private void flushReasoning(SessionId sessionId, LiveDeltas pending) {
        if (pending.reasoning.isEmpty()) {
            return;
        }
        String text = pending.reasoning.toString();
        pending.reasoning.setLength(0);
        quietly("流式思考增量", () -> bus.publishEphemeral(sessionId, new ReasoningDelta(text)));
    }

    /** 见类注释最后一段：广播失败只记日志，绝不往外抛。 */
    private static void quietly(String what, Runnable push) {
        try {
            push.run();
        } catch (RuntimeException e) {
            log.warn("{} 广播失败 —— 数据本身没问题，只是这一刻没推出去", what, e);
        }
    }
}
