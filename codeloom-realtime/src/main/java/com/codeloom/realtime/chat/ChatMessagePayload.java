package com.codeloom.realtime.chat;

import com.codeloom.domain.chat.ChatMessage;

import java.time.Instant;

/**
 * 推给浏览器的聊天消息。
 *
 * <h2>为什么不直接把领域对象序列化出去</h2>
 * 两个原因，第一个是硬的：
 * <ul>
 *   <li>{@code ChatMessage.id} 是值对象 {@code ChatMessageId}，直接序列化会变成
 *       {@code {"id":{"value":1}}} —— 前端得为此多剥一层。
 *   <li>领域对象一改（加个字段、改个名字），线上格式就跟着变。中间隔一个传输对象，
 *       "改领域模型"和"改协议"就是两次可以分开做的决定。
 * </ul>
 *
 * <p>用的 mapper 是 Spring 容器里那个（不像事件那套自制一份）：聊天是纯 Web 层的东西，
 * 它的 JSON 只给自家前端看，跟着 Web 层的序列化配置走是对的。
 */
public record ChatMessagePayload(long id,
                                 String projectId,
                                 String authorId,
                                 String text,
                                 Long anchorEventSeq,
                                 String anchorText,
                                 Instant createdAt) {

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
