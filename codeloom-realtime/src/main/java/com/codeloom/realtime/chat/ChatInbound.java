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
 *                       <p>**协议允许客户端带上它**（服务端不反查那条事件：被引用的
 *                       事件属于对方 agent 的流，而且事件被清理之后就查不到了）。
 *                       **界面目前还没有引用回复，所以发的一直是 null** ——
 *                       这不矛盾：字段是协议的一部分，生产者是将来那个功能。
 */
public record ChatInbound(String text, Long anchorEventSeq, String anchorText) {
}
