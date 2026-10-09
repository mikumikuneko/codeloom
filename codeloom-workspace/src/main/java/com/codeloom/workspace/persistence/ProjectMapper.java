package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * {@code project} / {@code project_member} 两张表的读写。
 *
 * <p>成员相关的语句也放在这里，而不是另开一个 {@code ProjectMemberMapper}：两张表没有
 * 独立的生命周期，任何一处提到成员都是「某个项目的成员」，拆开只会让调用方每次都要
 * 同时拿着两个 mapper。
 */
@Mapper
public interface ProjectMapper {

    /**
     * 新建或整体更新项目本身。成员是另一张表，不在这条语句里。
     *
     * <p><strong>{@code owner_id} 在 {@code UPDATE} 那一半里</strong>，这和别的字段
     * 不一样：房主是**会变**的 —— 他退出时项目转给剩下那个人
     * （见 {@code Project.withMemberRemoved}）。少了这一半，房主易主就只在内存里成立。
     */
    @Insert("""
            INSERT INTO project (id, owner_id, name, repo_path)
            VALUES (#{id}, #{ownerId}, #{name}, #{repoPath})
            AS new
            ON DUPLICATE KEY UPDATE owner_id = new.owner_id, name = new.name,
                                    repo_path = new.repo_path
            """)
    int save(ProjectRow row);

    @Select("SELECT " + ProjectRow.COLUMNS + " FROM project WHERE id = #{id}")
    ProjectRow findById(@Param("id") String id);

    @Select("SELECT " + ProjectRow.COLUMNS + " FROM project ORDER BY name, id")
    List<ProjectRow> findAll();

    /**
     * 某人参与的项目，分页。JOIN 而不是先查 id 再 IN，省一次往返，也天然去重。
     *
     * <p>{@code ORDER BY} 必须稳定，否则 {@code OFFSET} 翻页会漏条或重复 ——
     * 姓名相同的项目按 id 再排一次就够了。
     *
     * <p>退出的项目自然不在这张单子上：退出删掉的是他那一行成员关系，JOIN 就接不上了。
     * 没有"还在、但看不见"的第三种状态，所以这里也不需要额外条件。
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

    /** 一次取多个项目的全部成员，避免「按项目逐个查」的 N+1。 */
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
     * 加锁读出项目行，给"改动共享主干"的操作（合并）做互斥用。
     *
     * <p>{@code FOR UPDATE} 不能去掉，理由和 {@code SessionMapper#lockFencingToken} 一样：
     * 普通查询读到的东西在事务提交前就可能过期，而锁会一直挡到事务结束。
     *
     * @return 项目 id；项目不存在时为 null
     */
    @Select("SELECT id FROM project WHERE id = #{id} FOR UPDATE")
    String lockById(@Param("id") String id);

    /**
     * 抹掉项目行。**调用方必须先清成员行**（{@link #deleteMembers}）——
     * 这个项目里没有外键、也没有级联（见 schema.sql 开头那条约定），
     * 顺序写反的话会留下一堆没有项目的成员行。
     */
    @Delete("DELETE FROM project WHERE id = #{id}")
    int delete(@Param("id") String id);

    @Delete("DELETE FROM project_member WHERE project_id = #{projectId}")
    int deleteMembers(@Param("projectId") String projectId);

    @Insert("INSERT INTO project_member (project_id, user_id) VALUES (#{projectId}, #{userId})")
    int insertMember(@Param("projectId") String projectId, @Param("userId") String userId);
}
