package com.codeloom.workspace.persistence;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.project.ProjectInvitation;
import com.codeloom.domain.user.UserId;

import java.time.Instant;

/**
 * {@code project_invitation} 表的一行：只有字段和两个方向的转换，没有逻辑。
 *
 * <p>"这张邀请还能不能用"由领域对象判断（见 {@link ProjectInvitation#isUsableAt}）。
 */
public record ProjectInvitationRow(String token,
                                   String projectId,
                                   String createdBy,
                                   Instant createdAt,
                                   Instant expiresAt,
                                   String acceptedBy,
                                   Instant acceptedAt,
                                   Instant revokedAt) {

    /** 查询列清单：不用 {@code SELECT *}，别名写死，理由同 {@code SessionRow.COLUMNS}。 */
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
                // acceptedBy 与 acceptedAt 由领域对象保证同时有值或同时为空
                invitation.acceptedBy() == null ? null : invitation.acceptedBy().value(),
                invitation.acceptedAt(),
                invitation.revokedAt());
    }

    /** 行 → 领域对象；不做防御性校验，直接由领域构造器拒绝脏数据。 */
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
