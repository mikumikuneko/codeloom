package com.codeloom.domain.chat;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * 项目内双方聊天室的一条消息。纯人类通道，agent 不参与。
 *
 * <p>{@code id} 由存储层自增主键分配：消息要按全库单调顺序拉取，与 event 的 {@code seq}
 * 同属"需要全库排序的标识"。见
 * {@code docs/decisions/architecture/2026-09-24-ids-storage-issued-vs-domain-issued.md}。
 *
 * @param id             消息标识，由存储层分配
 * @param projectId      所属项目
 * @param authorId       发送者
 * @param text           消息正文，不能为空白
 * @param anchorEventSeq 可选锚点，指向被引用方会话流里的一条 agent 事件；null 表示普通消息
 * @param anchorText     锚点那一步的说明，是动作（动词 + 对象），即会话流里工具调用那一行；
 *                       被引用事件的正文可能很长，这里只回答"指的是哪一步"。发送时抄下来
 *                       一起存：那条事件属于对方的 agent 流，聊天室看不到它，且会随会话
 *                       清理而变样甚至消失。由引用方前端在按下"引用"时渲染，服务端原样
 *                       落库、原样推送，不生成也不校验。目前引用界面不存在，前端永远发
 *                       null；字段与"同生共死"的校验都还在，以上是设计意图。
 * @param createdAt      消息创建时间
 */
public record ChatMessage(ChatMessageId id,
                          ProjectId projectId,
                          UserId authorId,
                          String text,
                          Long anchorEventSeq,
                          String anchorText,
                          Instant createdAt) {

    public ChatMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(authorId, "authorId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(createdAt, "createdAt");

        if (text.isBlank()) {
            throw new IllegalArgumentException("聊天消息不能为空");
        }
        if (anchorEventSeq != null && anchorEventSeq <= 0) {
            throw new IllegalArgumentException("anchorEventSeq 必须是正数或 null，收到 " + anchorEventSeq);
        }
        // 有锚点没摘要，界面上只剩"引用了第 42 条"；有摘要没锚点，则指向空气。
        // 两者要么都在要么都不在
        if ((anchorEventSeq == null) != (anchorText == null)) {
            throw new IllegalArgumentException(
                    "锚点和它的摘要必须同时有或同时没有：seq=" + anchorEventSeq + " text=" + anchorText);
        }
    }
}
