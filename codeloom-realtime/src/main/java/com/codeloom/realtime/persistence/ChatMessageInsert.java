package com.codeloom.realtime.persistence;

import java.time.Instant;

/**
 * 插入一条聊天消息用的参数对象。可变的原因见 {@link GeneratedKey}
 * —— 一句话：消息 id 由存储层分配，而那个 id 要回填到参数对象上，record 收不下。
 *
 * <p>为什么不让领域侧生成 id：{@code ChatMessage} 的类注释已经解释了 ——
 * 聊天要按**全库单调**顺序拉取，那是自增主键才有的性质。
 */
public class ChatMessageInsert extends GeneratedKey {

    private final String projectId;
    private final String authorId;
    private final String text;
    private final Long anchorEventSeq;
    private final String anchorText;
    private final Instant createdAt;

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
