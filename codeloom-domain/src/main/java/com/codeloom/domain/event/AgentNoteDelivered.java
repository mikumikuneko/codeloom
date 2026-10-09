package com.codeloom.domain.event;

import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;

/**
 * 一条从**别的会话**送来的留言被投递到了这条会话。
 *
 * <h2>它和 {@link UserMessage} 的区别，以及为什么不能合并</h2>
 * 两者最终都会投影成一条 {@code user} 角色的消息（消息格式里没有"第三方"这个角色），
 * 但它们**不是同一件事**：一条是这条会话的所有者说的，另一条是**别人的 agent** 说的。
 * 合并成一个类型的话，审计流里就再也分不出"这句是我自己用户的要求"还是
 * "这是协作者那边发过来的消息"，而回滚、追责、复盘全都建立在这个区分上。
 *
 * <h2>投影时必须标明来源</h2>
 * 模型默认会把 user 角色的消息当成"我的用户在跟我说话"。不标来源的话，它会
 * 照着**别人的请求**去动自己这边的代码 —— 而这可能完全违背它自己用户的意图。
 * 所以投影那一层会加上出处，见 {@code ContextAssembler}。
 *
 * <h2>只手动分享，不做自动注入</h2>
 * 两条会话各自跑各自的，"顺手把对方的进展灌进来"听起来美好，实际是两个问题：
 * 闲聊的信噪比太低（大量"我改完了"对另一边的下一步毫无用处），
 * 而且它会**污染对方用户和 agent 之间的对话记录** ——
 * 那份记录是那个人自己的，不该被第三方的东西塞满。
 *
 * @param fromSessionId 留言来自哪条会话。记 id 而不是只记名字：
 *                      名字可以改，而"这条留言是从哪条会话发出来的"要能一直查得到
 * @param fromUserId    留言发起者。**同样是 id，不是用户名** —— 名字会变，
 *                      而唯一不等于不变（见 {@link SessionRewound}）。
 *                      标注来源时由读的那一侧按它现查：
 *                      界面那条前缀、以及投影里那句 "[来自 X 的 agent 的留言]"
 * @param text          留言正文。**不含发起者的名字** —— 名字由上面那两处各自拼，
 *                      写进正文等于同一句话里说两遍，而且改过名之后就永远是对不上的旧名字
 */
public record AgentNoteDelivered(SessionId fromSessionId, UserId fromUserId, String text)
        implements PersistentEvent {

    public AgentNoteDelivered {
        if (fromSessionId == null) {
            throw new IllegalArgumentException("留言必须记清来自哪条会话");
        }
        if (fromUserId == null) {
            throw new IllegalArgumentException("留言必须记清是谁发的 —— 模型要靠它判断该不该照做");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("空的留言没有意义");
        }
    }
}
