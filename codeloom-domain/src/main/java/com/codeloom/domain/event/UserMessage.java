package com.codeloom.domain.event;

/**
 * 用户发给 agent 的消息。
 *
 * @param text 消息正文
 */
public record UserMessage(String text) implements PersistentEvent {
}
