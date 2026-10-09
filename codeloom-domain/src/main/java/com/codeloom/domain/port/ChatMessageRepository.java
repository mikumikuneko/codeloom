package com.codeloom.domain.port;

import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;

import java.time.Instant;
import java.util.List;

/**
 * 聊天室消息仓储。实现在 {@code codeloom-realtime}（和事件同属"消息流"）。
 *
 * <p>聊天室是**纯人类通道**，agent 看不见它。想要把某句话给 agent 看，只能手动分享 ——
 * 不做自动注入，理由是闲聊信噪比太低，自动灌进上下文会烧掉窗口还带偏模型。
 *
 * <h2>为什么 append 返回完整对象而不是接收一个 ChatMessage</h2>
 * 消息 id 由存储层分配（自增主键）：聊天要按**全库单调顺序**拉取，
 * 这正是"需要全库排序的标识由存储层分配"那一类（另一个是 event 的 seq）。
 * 所以在落库前，领域里根本构造不出一个 id 完整的 {@code ChatMessage}。
 */
public interface ChatMessageRepository {

    /**
     * 追加一条消息。
     *
     * @param anchorEventSeq 可选锚点，指向某条 agent 事件；null 表示普通消息
     * @param anchorText     锚点那一步**是哪一步**的一句话说明（"编辑了 Foo.java"），
     *                       **和 anchorEventSeq 同生共死**。
     *                       存下来是因为被引用的事件属于对方的会话流，而且会随会话清理而变样 ——
     *                       见 {@code ChatMessage.anchorText} 那段（那里也写了它**不是**什么）
     * @return 落库后的完整消息（含分配到的 id）
     */
    ChatMessage append(ProjectId projectId,
                       UserId authorId,
                       String text,
                       Long anchorEventSeq,
                       String anchorText,
                       Instant occurredAt);

    /** 拉取 id 之后的消息，用于聊天室的断线补齐。 */
    List<ChatMessage> findAfter(ProjectId projectId, long afterId, int limit);

    /** 最近 N 条，用于进入项目时加载历史。 */
    List<ChatMessage> findRecent(ProjectId projectId, int limit);

    /**
     * 删掉这个项目的全部聊天记录 —— 只有一条路会走到这里：**最后一个人退出**。
     *
     * <p>成员一个个退出时聊天记录**不动**：它挂在项目上，而项目还在 ——
     * 走了的人看不到它，留下的人照旧能看到之前聊过什么。
     * 只有项目本身要消失的时候，它才跟着一起消失。
     */
    void deleteByProject(ProjectId projectId);
}
