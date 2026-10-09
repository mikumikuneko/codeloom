package com.codeloom.workspace.persistence;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.project.ProjectInvitation;
import com.codeloom.domain.user.UserId;

import java.time.Instant;

/**
 * {@code project_invitation} 表的一行。
 *
 * <p>和别的 Row 一样：只有字段和两个方向的转换，没有任何逻辑。
 * 「这张邀请还能不能用」不在这里判断 —— 那是领域对象的事（见
 * {@link ProjectInvitation#isUsableAt}），这一层只知道怎么把它搬进搬出。
 */
public record ProjectInvitationRow(String token,
                                   String projectId,
                                   String createdBy,
                                   Instant createdAt,
                                   Instant expiresAt,
                                   String acceptedBy,
                                   Instant acceptedAt,
                                   Instant revokedAt) {

    /** 查询用的列清单。理由同 {@code SessionRow.COLUMNS}：不用 {@code SELECT *}，且别名写死在 SQL 里。 */
    static final String COLUMNS = """
            token, project_id AS projectId, created_by AS createdBy, created_at AS createdAt,
            expires_at AS expiresAt, accepted_by AS acceptedBy, accepted_at AS acceptedAt,
            revoked_at AS revokedAt
            """;

    static ProjectInvitationRow of(ProjectInvitation invitation) {
        return new ProjectInvitationRow(
                invitation.token(),
                invitation.projectId().value(),
                invitation.createdBy().value(),
                invitation.createdAt(),
                invitation.expiresAt(),
                // acceptedBy 和 acceptedAt 是一起有值/一起为空的（领域对象守着），
                // 所以这里分开取包不会出现"只剩一半"的行
                invitation.acceptedBy() == null ? null : invitation.acceptedBy().value(),
                invitation.acceptedAt(),
                invitation.revokedAt());
    }

    /** 行 → 领域对象。不在这里做防御性校验，让它在紧凑构造器里炸，炸得越早越好。 */
    ProjectInvitation toDomain() {
        return new ProjectInvitation(
                token,
                ProjectId.of(projectId),
                UserId.of(createdBy),
                createdAt,
                expiresAt,
                acceptedBy == null ? null : UserId.of(acceptedBy),
                acceptedAt,
                revokedAt);
    }
}
