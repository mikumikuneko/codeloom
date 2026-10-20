package com.codeloom.domain.event;

import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;

/**
 * 别的会话送来的一条留言被投递到了这条会话。来源可以是用户手动分享，也可以是平台在对方
 * 新建文件时的自动提醒（见
 * {@code docs/decisions/feature/2026-10-02-collaboration-notice-for-new-files}）。
 *
 * <p>它和 {@link UserMessage} 不能合并：两者都投影成 {@code user} 角色（消息格式里没有"第三方"
 * 角色），但一条是本会话所有者说的，另一条是别人的 agent 说的。回滚、追责、复盘都依赖这个
 * 区分；投影时必须标明来源，否则模型会把别人的请求当成自己用户的（见
 * {@code docs/decisions/architecture/2026-10-10-one-assembler-configured-in-one-place}）。
 *
 * @param fromSessionId 留言来自哪条会话。记 id：名字会改，来源要一直查得到
 * @param fromUserId    留言发起者的 id；读的一侧按它现查用户名，拼出界面前缀和投影里的
 *                      "[来自 X 的 agent 的留言]"（见
 *                      {@code docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider}）
 * @param text          留言正文，不含发起者名字；名字由读取方各自拼，写进正文会在改名后成为
 *                      对不上人的旧名字
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
