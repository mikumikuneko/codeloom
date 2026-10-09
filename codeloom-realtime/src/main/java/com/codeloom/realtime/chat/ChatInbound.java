package com.codeloom.realtime.chat;

/**
 * 浏览器发上来的那条消息。
 *
 * <p>**刻意没有 authorId 字段。** 说话的人取自连接上的已认证身份（见
 * {@code ChatWebSocketHandler}）—— 从消息体里取的话，谁都能冒充别人发言，
 * 而那是聊天室最容易出的问题。少一个字段，就少一次"忘了校验"的机会。
 *
 * @param anchorEventSeq 可选锚点，指向某条 agent 事件（引用回复）。空表示普通消息
 * @param anchorText     锚点那一步**是哪一步**的一句话说明（"编辑了 Foo.java"）。
 *                       它是那一步的**动作**，不是把那条事件的正文概括成一句话 ——
 *                       见 {@code ChatMessage.anchorText} 那段。
 *
 *                       <p>**由发消息的人在客户端带上**，而不是服务端去查那条事件：
 *                       被引用的事件属于**对方 agent 的流**，服务端这一侧要按 seq 反查
 *                       得先知道是哪条会话 —— 而 event 的 seq 是全局唯一的，查得到，
 *                       但那要多一次查询、而且事件被清理之后就查不到了。
 *                       引用该留住的是"当时指的是什么"，那是已经说过的话的一部分。
 */
public record ChatInbound(String text, Long anchorEventSeq, String anchorText) {
}
