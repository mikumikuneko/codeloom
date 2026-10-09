package com.codeloom.app.turn;

import com.codeloom.agent.loop.TurnOutcome;
import com.codeloom.domain.session.SessionId;

/**
 * 一轮执行的结果。
 *
 * <p>这些情况各自分开，而不是揉成一个"成功/失败"：它们要触发**完全不同的上层反应**。
 * 尤其 {@link Busy} —— 它根本不是错误，是"这一刻轮不到你"（同一条会话已经有执行者了）。
 * 把它报成失败会让用的人以为是 bug，去重试，而重试只会一直失败。
 */
public sealed interface TurnResult {

    /**
     * 一轮跑完了。模型是完成了、打转了、被取消了还是验证没过，看
     * {@link TurnOutcome#status()} —— 它们都属于"正常收了尾"，会话回到等用户指示的状态。
     */
    record Completed(TurnOutcome outcome) implements TurnResult {
    }

    /** 会话已被别的执行者持有（别的实例，或同一实例的另一轮）。不阻塞、不排队。 */
    record Busy(SessionId sessionId) implements TurnResult {
    }

    /**
     * 执行权在本轮中途被接管了：续约失败，说明租约已经过期且别人拿走了。
     *
     * <p>这种情况下**本轮剩下的写入已经不再可信**，执行器会立刻停下、
     * 并且不会去释放锁（那是别人的锁）。
     */
    record LeaseLost(SessionId sessionId) implements TurnResult {
    }

    /**
     * 这个请求**已经处理过了**，所以什么都没做。
     *
     * <p>和 {@link Busy} 是两回事：Busy 是"这一刻轮不到你"（等会儿再来可能就成了），
     * 这个是"你这件事我已经办过了"（再来多少次都一样）。客户端该做的是**别再重试**，
     * 去拉事件流看上次跑出了什么。
     */
    record Duplicate(SessionId sessionId) implements TurnResult {
    }

    /**
     * 这句话**收下了，但还轮不到它** —— 排在队里，等这一轮跑完再交给模型。
     *
     * <h2>它取代了两个更糟的答案</h2>
     * <ul>
     *   <li><b>一轮在跑</b> → {@link Busy}（409，"等它跑完再试"）：用户得自己盯着、
     *       自己再点一次。</li>
     *   <li><b>审批挂着</b> → 更糟：每一轮开头那一步是迁到 {@code THINKING}，
     *       而状态机只允许 {@code AWAITING_APPROVAL → WAITING_USER} ——
     *       于是抛异常、被执行器收成 {@link Failed}，**整条会话变成 FAILED，
     *       用户那句话一个字都没落库**，接口回的还是一句 Java 异常原文。</li>
     * </ul>
     *
     * <p>参考实现（Claude Code）的答案在它自己的文档里写着：干活的时候你照样能发，
     * 消息排队、在对话里显示成灰的，等轮到自己了再交给模型。这里对齐那个。
     *
     * @param sessionId 排在哪条会话下
     * @param ahead     它前面还排着几句（不含自己）。0 表示下一个就是它
     */
    record Queued(SessionId sessionId, int ahead) implements TurnResult {
    }

    /**
     * 本轮出错，会话已尽力记成 {@code FAILED}（用户可以重试）。
     *
     * @param reason 出错原因的摘要
     */
    record Failed(String reason) implements TurnResult {
    }

    /**
     * 用户把这一轮停了。
     *
     * <h2>为什么它不能并进 {@link Failed}</h2>
     * 并进 {@code Failed} 的话，按 Esc 停下来之后会话会被打成 FAILED、界面上出现一行红字
     * 加一个 Java 异常类名 —— 而**停是用户自己做的**，不是出事。参考实现（Claude Code）
     * 里按下 Esc 的样子是"这一轮就停在这儿"，没有任何错误。
     *
     * <p>会话按正常收尾走（回到等用户指示），事件里记的是"谁停了它"而不是异常原文。
     */
    record Interrupted() implements TurnResult {
    }
}
