package com.codeloom.realtime.chat;

import com.codeloom.domain.chat.ChatMessage;

import java.time.Instant;

/**
 * 推给浏览器的聊天消息，与领域对象 {@link ChatMessage} 分开的传输格式。
 *
 * <p>不能直接序列化领域对象：{@link ChatMessage#id()} 是值对象，直接序列化会变成
 * {@code {"id":{"value":1}}}；而且领域字段一改，线上格式就会跟着变。隔一层之后，
 * 改领域模型和改协议可以分别进行。
 *
 * <p>使用 Spring 容器里的 mapper：聊天只在 Web 层使用，其 JSON 跟随 Web 层序列化配置。
 *
 * @param id             消息 id
 * @param projectId      所属项目
 * @param authorId       作者
 * @param text           消息正文
 * @param anchorEventSeq 可选锚点
 * @param anchorText     锚点动作说明
 * @param createdAt      发送时间
 */
public record ChatMessagePayload(long id,
                                 String projectId,
                                 String authorId,
                                 String text,
                                 Long anchorEventSeq,
                                 String anchorText,
                                 Instant createdAt) {

    /**
     * @param message 领域消息
     * @return 可序列化的传输对象
     */
    public static ChatMessagePayload of(ChatMessage message) {
        return new ChatMessagePayload(
                message.id().value(),
                message.projectId().value(),
                message.authorId().value(),
                message.text(),
                message.anchorEventSeq(),
                message.anchorText(),
                message.createdAt());
    }
}
