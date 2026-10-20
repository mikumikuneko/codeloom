package com.codeloom.realtime.persistence;

import java.time.Instant;

/**
 * 插入一条聊天消息用的参数对象。可变的原因见 {@link GeneratedKey}：消息 id 由存储层
 * 分配并回填到参数对象上，record 收不下。
 *
 * <p>聊天要按全库单调的顺序拉取，只有自增主键具备这个性质，所以 id 不能由领域侧生成。
 * 见 {@code docs/decisions/architecture/2026-09-24-ids-storage-issued-vs-domain-issued.md}。
 */
public class ChatMessageInsert extends GeneratedKey {

    private final String projectId;
    private final String authorId;
    private final String text;
    private final Long anchorEventSeq;
    private final String anchorText;
    private final Instant createdAt;

    /**
     * @param projectId      所属项目
     * @param authorId       作者
     * @param text           消息正文
     * @param anchorEventSeq 可选锚点
     * @param anchorText     锚点动作说明
     * @param createdAt      发送时间
     */
    public ChatMessageInsert(String projectId, String authorId, String text,
                             Long anchorEventSeq, String anchorText, Instant createdAt) {
        this.projectId = projectId;
        this.authorId = authorId;
        this.text = text;
        this.anchorEventSeq = anchorEventSeq;
        this.anchorText = anchorText;
        this.createdAt = createdAt;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getText() {
        return text;
    }

    public Long getAnchorEventSeq() {
        return anchorEventSeq;
    }

    public String getAnchorText() {
        return anchorText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
