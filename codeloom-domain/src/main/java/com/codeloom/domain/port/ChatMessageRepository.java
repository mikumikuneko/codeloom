package com.codeloom.domain.port;

import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;

import java.time.Instant;
import java.util.List;

/**
 * 聊天室消息仓储。实现在 {@code codeloom-realtime}，和事件同属消息流。
 *
 * <p>聊天室是纯人类通道，agent 看不见它；要给 agent 看只能手动分享。不做自动注入：
 * 闲聊信噪比太低，写入上下文会占满窗口并误导模型。
 *
 * <p>{@code append} 返回完整对象而不是接收 {@code ChatMessage}：消息 id 由存储层分配
 * （自增主键），聊天要按全库单调顺序拉取；需要全库排序的标识由存储层分配（另一个是
 * 事件的 seq），所以落库前领域里构造不出 id 完整的消息。
 */
public interface ChatMessageRepository {

    /**
     * 追加一条消息。
     *
     * @param projectId       项目
     * @param authorId        作者
     * @param text            消息正文
     * @param anchorEventSeq  可选锚点，指向某条 agent 事件；null 表示普通消息
     * @param anchorText      锚点那一步是哪一步的一句话说明（例如「编辑了 Foo.java」），
     *                        与 {@code anchorEventSeq} 同时存在；存下来是因为被引用的事件
     *                        属于对方的会话流，而且会随会话清理而变样
     * @param occurredAt      发生时间
     * @return 落库后的完整消息，含分配到的 id
     */
    ChatMessage append(ProjectId projectId,
                       UserId authorId,
                       String text,
                       Long anchorEventSeq,
                       String anchorText,
                       Instant occurredAt);

    /**
     * 拉取 id 之后的消息，用于聊天室的断线补齐。
     *
     * @param projectId 项目
     * @param afterId   只返回 id 大于它的消息
     * @param limit     最多返回多少条
     * @return 按 id 升序的消息
     */
    List<ChatMessage> findAfter(ProjectId projectId, long afterId, int limit);

    /**
     * 最近 N 条，用于进入项目时加载历史。
     *
     * @param projectId 项目
     * @param limit     最多返回多少条
     * @return 按 id 升序的消息（最新的在最后）
     */
    List<ChatMessage> findRecent(ProjectId projectId, int limit);

    /**
     * 删掉这个项目的全部聊天记录。只有一条路会走到这里：最后一个人退出。
     *
     * <p>成员一个个退出时聊天记录不动：它挂在项目上，而项目还在 —— 走了的人看不到它，
     * 留下的人照旧能看到之前聊过什么。只有项目本身要消失时，它才跟着一起消失。
     *
     * @param projectId 项目
     */
    void deleteByProject(ProjectId projectId);
}
