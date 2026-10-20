package com.codeloom.realtime.persistence;

import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.chat.ChatMessageId;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;

import java.time.Instant;

/**
 * {@code chat_message} 表的一行，只用于读。写走 {@link ChatMessageInsert}。
 *
 * <p>列名一律加反引号（例如 {@code text}），是为了全表统一写法，而不是因为它们一定是保留字。
 *
 * @param id             存储层分配的消息 id
 * @param projectId      所属项目
 * @param authorId       作者
 * @param text           消息正文
 * @param anchorEventSeq 可选锚点
 * @param anchorText     锚点动作说明
 * @param createdAt      发送时间
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
