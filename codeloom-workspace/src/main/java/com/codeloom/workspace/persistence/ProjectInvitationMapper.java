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
 * <h2>{@code save} 的 upsert 只更新三个列，而且只让它们前进</h2>
 * {@code accepted_by} / {@code accepted_at} / {@code revoked_at} —— 那是这张表上**唯一会变**的东西。
 * token、项目、生成人、生成时间、过期时间在 {@code INSERT} 之后永远不动，
 * 所以它们不出现在 {@code ON DUPLICATE KEY UPDATE} 里。
 *
 * <p>这不只是"省几列"：一张邀请的**有效期**如果能被后来的写入改掉，
 * 那"它什么时候过期"就取决于最后一次写是什么时候 —— 而那是没人能推理的规则。
 * 同样的道理也适用于撤销：一旦写上了 {@code revoked_at}，后来的写入不能把它用
 * {@code NULL} 覆盖回去，否则一次撤销会被一个拿着旧快照的写入者悄悄抹掉。
 * 接受与撤销也互斥：先到的那个状态生效，后到的不能改写。
 *
 * <p>服务层还会用项目锁把"读邀请 → 改邀请"串起来（见 {@code InvitationService}）；
 * 这里的条件更新是最后一道防线，防的是以后有人绕开那把锁直接调 {@code save}。
 *
 * <h2>读后要改状态时用 {@link #findByTokenForUpdate}</h2>
 * 普通查询在 REPEATABLE READ 下读到的是事务开始时的快照；拿到项目锁之后再用普通查询
 * 重读，仍然会看到旧值。{@code SELECT ... FOR UPDATE} 读最新已提交版本，并把行锁到
 * 事务结束。
 *
 * <h2>为什么 {@code INSERT} 的列名不写成 {@code ProjectInvitationRow.COLUMNS}</h2>
 * 那份清单是给 {@code SELECT} 用的投影，每一项都带 {@code AS} 别名
 * （{@code project_id AS projectId}）—— 放进 {@code INSERT INTO ... ( ... )} 的括号里
 * 是语法错误。写路径的列名一直是在语句里显式列出来的，和
 * {@code SessionMapper}/{@code WorkspaceMapper} 的 {@code save} 同一种写法。
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

    @Select("SELECT " + ProjectInvitationRow.COLUMNS
            + " FROM project_invitation WHERE token = #{token}")
    ProjectInvitationRow findByToken(@Param("token") String token);

    /**
     * 加锁读一行，给"读到之后要改状态"的调用方用。
     *
     * <p>调用方必须先拿到项目锁，再调它 —— 本表所有写者都按「项目 → 邀请」的顺序加锁。
     * 不在事务里调它没有意义：语句结束锁就释放了。
     */
    @Select("SELECT " + ProjectInvitationRow.COLUMNS
            + " FROM project_invitation WHERE token = #{token} FOR UPDATE")
    ProjectInvitationRow findByTokenForUpdate(@Param("token") String token);

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
