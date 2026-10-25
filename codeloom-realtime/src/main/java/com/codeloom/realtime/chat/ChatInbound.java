package com.codeloom.realtime.chat;

/**
 * 浏览器发上来的聊天消息。
 *
 * <p>刻意没有 {@code authorId}：作者取自连接上的已认证身份（见
 * {@code ChatWebSocketHandler}）。允许消息体携带作者就等于允许冒充别人。
 *
 * @param text           消息正文
 * @param anchorEventSeq 可选锚点，指向某条 agent 事件（引用回复）；空表示普通消息
 * @param anchorText     锚点那一步的动作说明（例如“编辑了 Foo.java”），不是对那条事件
 *                       正文的概括。协议允许客户端带上它，服务端不反查被引用的事件：
 *                       那属于对方的 agent 流，而且事件被清理后就查不到了。
 */
public record ChatInbound(String text, Long anchorEventSeq, String anchorText) {
}
