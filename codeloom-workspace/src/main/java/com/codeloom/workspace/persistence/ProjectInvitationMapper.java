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
 * <p>{@link #save} 是 upsert，但只更新 {@code accepted_by}、{@code accepted_at} 和
 * {@code revoked_at}，且状态只能单向前进：接受与撤销互斥，先到者生效；已撤销的邀请不能
 * 被改回未撤销，有效期也不能被后续写入改写。token、项目、生成人、生成时间和过期时间在
 * 插入后不再变化。服务层用项目锁串行化"读邀请 → 改状态"，这里的条件更新是绕开锁时的
 * 最后防线。
 *
 * <p>读到最新值再改状态时用 {@link #findByTokenForUpdate}：普通查询在 REPEATABLE READ
 * 下读到的是事务开始时的快照。
 */
@Mapper
public interface ProjectInvitationMapper {

    /**
     * 插入邀请，或只推进接受/撤销状态，见类注释。
     *
     * @param row 要保存的邀请
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO project_invitation
                (token, project_id, created_by, created_at, expires_at,
                 accepted_by, accepted_at, revoked_at)
            VALUES
                (#{token}, #{projectId}, #{createdBy}, #{createdAt}, #{expiresAt},
                 #{acceptedBy}, #{acceptedAt}, #{revokedAt})
            AS new
            ON DUPLICATE KEY UPDATE
                accepted_by = IF(project_invitation.revoked_at IS NULL
                                 AND project_invitation.accepted_by IS NULL
                                 AND new.revoked_at IS NULL,
                                 new.accepted_by, project_invitation.accepted_by),
                accepted_at = IF(project_invitation.revoked_at IS NULL
                                 AND project_invitation.accepted_at IS NULL
                                 AND new.revoked_at IS NULL,
                                 new.accepted_at, project_invitation.accepted_at),
                revoked_at  = IF(project_invitation.accepted_by IS NULL,
                                 COALESCE(project_invitation.revoked_at, new.revoked_at),
                                 project_invitation.revoked_at)
            """)
    int save(ProjectInvitationRow row);

    /**
     * @param token 邀请 token
     * @return 对应行；不存在时为 null
     */
    @Select("SELECT " + ProjectInvitationRow.COLUMNS
            + " FROM project_invitation WHERE token = #{token}")
    ProjectInvitationRow findByToken(@Param("token") String token);

    /**
     * 加锁读一行，给"读到之后要改状态"的调用方用。
     *
     * <p>调用方必须先拿到项目锁：本表所有写者都按"项目 → 邀请"的顺序加锁。必须在事务里
     * 调用，否则语句结束锁就释放了。
     *
     * @param token 邀请 token
     * @return 对应行；不存在时为 null
     */
    @Select("SELECT " + ProjectInvitationRow.COLUMNS
            + " FROM project_invitation WHERE token = #{token} FOR UPDATE")
    ProjectInvitationRow findByTokenForUpdate(@Param("token") String token);

    /**
     * 这个项目下还没被接受的邀请，新的在前。
     *
     * <p>只过滤"已接受"，不过滤过期和撤销；可用性由调用方再判（见
     * {@code InvitationService.pending}）。已接受的邀请已经兑现成成员身份，不再返回。
     *
     * @param projectId 项目 id
     * @return 待处理邀请，按创建时间倒序
     */
    @Select("SELECT " + ProjectInvitationRow.COLUMNS
            + " FROM project_invitation WHERE project_id = #{projectId} AND accepted_by IS NULL"
            + " ORDER BY created_at DESC")
    List<ProjectInvitationRow> findPendingByProject(@Param("projectId") String projectId);

    /**
     * 删掉一个项目的全部邀请，不过滤状态。
     *
     * <p>项目不存在后留一张仍有效的凭据是个洞：它能换到成员身份，而项目已经不存在。
     *
     * @param projectId 项目 id
     * @return 删除的行数
     */
    @Delete("DELETE FROM project_invitation WHERE project_id = #{projectId}")
    int deleteByProject(@Param("projectId") String projectId);
}
