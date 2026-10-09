package com.codeloom.domain.event;

/**
 * 会落库的事件 —— 也就是「这条会话的历史」。
 *
 * <p>只有这一类能传给 {@code EventStore.append(...)}。回放、断线补齐、审计
 * 全部建立在这些事件之上，所以它们一旦落库就不能修改（append-only）。
 */
public sealed interface PersistentEvent extends Event
        permits SessionStarted, SessionStateChanged, UserMessage, PlatformInstruction,
                AssistantMessage, ToolCallRequested, ToolResult, ToolCancelled,
                ToolInterrupted, CheckpointCreated, WorkspaceChanges, SessionRewound,
                VerificationResult, TurnTokensUsed, ContextCompacted, AgentNoteDelivered,
                ToolApprovalRequested, ToolApprovalResolved, ToolRejected, ToolResultsCleared,
                ModelChanged, SessionSynced, TodoListUpdated, LlmRetryScheduled {
}
