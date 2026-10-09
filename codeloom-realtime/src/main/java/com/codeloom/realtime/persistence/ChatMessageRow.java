package com.codeloom.realtime.persistence;

import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.chat.ChatMessageId;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;

import java.time.Instant;

/**
 * {@code chat_message} 表的一行，**只用于读**。
 *
 * <p>写走 {@link ChatMessageInsert}，原因见那个类。
 *
 * <p>列名一律加反引号（{@code text} 这类）—— 图的是全表一个写法，而不是"它一定撞上了保留字"。
 */
public record ChatMessageRow(long id,
                             String projectId,
                             String authorId,
                             String text,
                             Long anchorEventSeq,
                             String anchorText,
                             Instant createdAt) {

    static final String COLUMNS = """
            id, project_id AS projectId, author_id AS authorId, `text`,
            anchor_event_seq AS anchorEventSeq, anchor_text AS anchorText,
            created_at AS createdAt
            """;

    ChatMessage toDomain() {
        return new ChatMessage(
                ChatMessageId.of(id),
                ProjectId.of(projectId),
                UserId.of(authorId),
                text,
                anchorEventSeq,
                anchorText,
                createdAt);
    }
}
