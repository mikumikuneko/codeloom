package com.codeloom.domain.event;

/** 用户发给 agent 的消息。 */
public record UserMessage(String text) implements PersistentEvent {
}
