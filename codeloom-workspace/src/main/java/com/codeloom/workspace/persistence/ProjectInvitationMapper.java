package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code project_invitation} 表的读写。
 *
 * <h2>{@code save} 的 upsert 只更新三个列</h2>
 * {@code accepted_by} / {@code accepted_at} / {@code revoked_at} —— 那是这张表上**唯一会变**的东西。
 * token、项目、生成人、生成时间、过期时间在 {@code INSERT} 之后永远不动，
 * 所以它们不出现在 {@code ON DUPLICATE KEY UPDATE} 里。
 *
 * <p>这不只是"省几列"：一张邀请的**有效期**如果能被后来的写入改掉，
 * 那"它什么时候过期"就取决于最后一次写是什么时候 —— 而那是没人能推理的规则。
 */
@Mapper
public interface ProjectInvitationMapper {

    @Insert("""
            INSERT INTO project_invitation
                (token, project_id, created_by, created_at, expires_at,
                 accepted_by, accepted_at, revoked_at)
            VALUES
                (#{token}, #{projectId}, #{createdBy}, #{createdAt}, #{expiresAt},
                 #{acceptedBy}, #{acceptedAt}, #{revokedAt})
            AS new
            ON DUPLICATE KEY UPDATE
                accepted_by = new.accepted_by,
                accepted_at = new.accepted_at,
                revoked_at  = new.revoked_at
            """)
    int save(ProjectInvitationRow row);

    @Select("SELECT " + ProjectInvitationRow.COLUMNS
            + " FROM project_invitation WHERE token = #{token}")
    ProjectInvitationRow findByToken(@Param("token") String token);

    /**
     * 这个项目下**还没被接受的**邀请，新的在前。
     *
     * <p>过滤的是「接受」这一件事（{@code accepted_by IS NULL}），**不过滤过期也不过滤撤销**：
     * 那两种在界面上是"已失效，原因某某"，让人看得见比让它凭空消失好。
     * 而接受过的那些是真的没用了 —— 那张凭据已经兑现成成员身份。
     */
    @Select("SELECT " + ProjectInvitationRow.COLUMNS
            + " FROM project_invitation WHERE project_id = #{projectId} AND accepted_by IS NULL"
            + " ORDER BY created_at DESC")
    List<ProjectInvitationRow> findPendingByProject(@Param("projectId") String projectId);

    /**
     * 删掉一个项目的全部邀请，**不过滤状态**。
     *
     * <p>和上面那条"过期和撤销的也要返回"不矛盾：那条规矩保护的是对证，
     * 项目没了对证就没意义了 —— 而留一张还在有效期内的凭据反而是个洞，
     * 它能换到成员身份，而那个项目已经不存在。
     */
    @Delete("DELETE FROM project_invitation WHERE project_id = #{projectId}")
    int deleteByProject(@Param("projectId") String projectId);
}
