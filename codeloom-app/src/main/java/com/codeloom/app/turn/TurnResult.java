package com.codeloom.app.turn;

import com.codeloom.agent.loop.TurnOutcome;
import com.codeloom.domain.session.SessionId;

/**
 * 一轮执行的结果。
 *
 * <p>各情况分开而不是揉成成功/失败，因为它们触发完全不同的上层反应。{@link Busy} 尤其不是
 * 错误，而是"这一刻轮不到你"（树上已有执行者，可能是同一人的另一条会话）；报成失败会让
 * 调用方重试，而重试只会一直失败。
 */
public sealed interface TurnResult {

    /**
     * 一轮跑完了。具体是完成、打转、取消还是验证未过，看 {@link TurnOutcome#status()}；
     * 它们都算正常收尾，会话回到等用户指示。
     */
    record Completed(TurnOutcome outcome) implements TurnResult {
    }

    /** 会话已被其他执行者持有（另一实例或同一实例的另一轮）；不阻塞、不排队。 */
    record Busy(SessionId sessionId) implements TurnResult {
    }

    /**
     * 执行权在本轮中途被接管：续约失败，租约已过期并被别人拿走。本轮剩余写入不可信，
     * 执行器立即停下，并且不释放锁（那是别人的锁）。
     */
    record LeaseLost(SessionId sessionId) implements TurnResult {
    }

    /**
     * 这个请求已经处理过了，什么都没做。与 {@link Busy} 不同：Busy 是等会儿可能就行，
     * 这里是再来多少次都一样；客户端应停止重试，去拉事件流看上次结果。
     */
    record Duplicate(SessionId sessionId) implements TurnResult {
    }

    /**
     * 消息已收下，但这一轮还轮不到，排在队里等本轮跑完再交给模型。
     *
     * <p>它取代了两个更糟的答案：一轮在跑时回 {@link Busy}，用户得自己盯着再点一次；
     * 审批挂着时回错误更糟 —— 每轮开头要迁到 {@code THINKING}，而状态机只允许
     * {@code AWAITING_APPROVAL → WAITING_USER}，于是会话被打成 {@link Failed}，
     * 用户的话一个字都没落库。
     *
     * @param sessionId 排在哪条会话下
     * @param ahead     前面还排着几句（不含自己）；0 表示下一个就是它
     */
    record Queued(SessionId sessionId, int ahead) implements TurnResult {
    }

    /**
     * 本轮出错，会话已尽力记成 {@code FAILED}，用户可以重试。
     *
     * @param reason 出错原因的摘要
     */
    record Failed(String reason) implements TurnResult {
    }

    /**
     * 用户把这一轮停了。
     *
     * <p>不能并进 {@link Failed}：否则按 Esc 后会话变成 FAILED、界面出现红字和 Java 异常类名，
     * 而停止是用户自己的操作。会话按正常收尾回到等用户指示，事件记录谁停了它，而不是异常原文。
     */
    record Interrupted() implements TurnResult {
    }
}
