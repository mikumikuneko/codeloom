package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * {@code project} 与 {@code project_member} 两张表的读写。
 *
 * <p>成员语句放在同一接口：两张表没有独立生命周期，成员总是"某个项目的成员"。
 */
@Mapper
public interface ProjectMapper {

    /**
     * 新建或整体更新项目本身；成员在另一张表，不在这条语句里。
     *
     * <p>{@code owner_id} 也在 {@code UPDATE} 中：房主会变化，他退出时项目转给剩下的
     * 成员（见 {@code Project.withMemberRemoved}）。少了这一半，易主只在内存里成立。
     *
     * @param row 要保存的项目
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO project (id, owner_id, name, repo_path)
            VALUES (#{id}, #{ownerId}, #{name}, #{repoPath})
            AS new
            ON DUPLICATE KEY UPDATE owner_id = new.owner_id, name = new.name,
                                    repo_path = new.repo_path
            """)
    int save(ProjectRow row);

    /**
     * @param id 项目 id
     * @return 项目行；不存在时为 null
     */
    @Select("SELECT " + ProjectRow.COLUMNS + " FROM project WHERE id = #{id}")
    ProjectRow findById(@Param("id") String id);

    /**
     * 加锁读出项目行。
     *
     * <p>与 {@link #findById} 的区别是读取最新已提交版本，并把行锁持有到事务结束；
     * 普通查询在先读过别的东西的事务里会一直看到旧快照。
     *
     * @param id 项目 id
     * @return 项目行；不存在时为 null
     */
    @Select("SELECT " + ProjectRow.COLUMNS + " FROM project WHERE id = #{id} FOR UPDATE")
    ProjectRow findByIdForUpdate(@Param("id") String id);

    /**
     * 某人参与的项目，分页。
     *
     * <p>用 JOIN 省一次往返并天然去重。{@code ORDER BY} 必须稳定，否则
     * {@code OFFSET} 翻页会漏条或重复。退出项目会删掉成员行，JOIN 自然把它排除，
     * 不存在"还在但看不见"的第三种状态。
     *
     * @param userId 用户 id
     * @param limit 每页条数
     * @param offset 偏移量
     * @return 项目行，按名称再按 id 排序
     */
    @Select("""
            SELECT p.id, p.owner_id AS ownerId, p.name, p.repo_path AS repoPath
              FROM project p
              JOIN project_member m ON m.project_id = p.id
             WHERE m.user_id = #{userId}
             ORDER BY p.name, p.id
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<ProjectRow> findByMember(@Param("userId") String userId,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    /**
     * 一次取多个项目的全部成员，避免 N+1。
     *
     * @param projectIds 项目 id 集合
     * @return 成员行
     */
    @Select("""
            <script>
            SELECT project_id AS projectId, user_id AS userId
              FROM project_member
             WHERE project_id IN
               <foreach item="id" collection="projectIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<ProjectMemberRow> findMembers(@Param("projectIds") Collection<String> projectIds);

    /**
     * 加锁读出一个项目的成员行，给"读到之后要基于成员集合做决定"的调用方用。
     *
     * @param projectId 项目 id
     * @return 成员行，锁持有到事务结束
     */
    @Select("""
            SELECT project_id AS projectId, user_id AS userId
              FROM project_member
             WHERE project_id = #{projectId}
             FOR UPDATE
            """)
    List<ProjectMemberRow> findMembersForUpdate(@Param("projectId") String projectId);

    /**
     * 加锁读出项目行，给改动共享主干的操作（合并）做互斥。
     *
     * <p>{@code FOR UPDATE} 不能去掉：普通查询读到的值在事务提交前就可能过期，而锁会
     * 持有到事务结束。
     *
     * @param id 项目 id
     * @return 项目 id；项目不存在时为 null
     */
    @Select("SELECT id FROM project WHERE id = #{id} FOR UPDATE")
    String lockById(@Param("id") String id);

    /**
     * 删除项目行；调用方必须先清成员行（{@link #deleteMembers}）。
     *
     * <p>这里没有外键与级联（见 schema.sql 开头的约定），顺序写反会留下孤儿成员行。
     *
     * @param id 项目 id
     * @return 删除的行数
     */
    @Delete("DELETE FROM project WHERE id = #{id}")
    int delete(@Param("id") String id);

    /**
     * @param projectId 项目 id
     * @return 删除的行数
     */
    @Delete("DELETE FROM project_member WHERE project_id = #{projectId}")
    int deleteMembers(@Param("projectId") String projectId);

    /**
     * @param projectId 项目 id
     * @param userId 成员用户 id
     * @return 插入的行数
     */
    @Insert("INSERT INTO project_member (project_id, user_id) VALUES (#{projectId}, #{userId})")
    int insertMember(@Param("projectId") String projectId, @Param("userId") String userId);
}
