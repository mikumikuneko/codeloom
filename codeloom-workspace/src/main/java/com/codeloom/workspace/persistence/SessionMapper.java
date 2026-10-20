package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code session} 表的读写。
 *
 * <p>手写 SQL 而不是继承通用 mapper：{@code save} 的语义是"不存在就插、存在就整体
 * 更新"，并且语句里出现哪些列必须看得见 —— {@code fencing_token} 不能出现在普通写入
 * 路径上，那一列已随专用方法搬到 {@link WorkspaceMapper}。理由见
 * {@code docs/decisions/architecture/2026-09-25-handwritten-sql-keeps-fencing-out-of-reach.md}。
 *
 * <p>这一层不做判断：不校验状态迁移、不拼装领域对象、不吞异常，只负责搬行。
 */
@Mapper
public interface SessionMapper {

    /**
     * 新建或整体更新（{@code INSERT ... ON DUPLICATE KEY UPDATE}）。
     *
     * <p>使用 MySQL 8.0.19+ 的行别名写法（{@code AS new}），不是已废弃的
     * {@code VALUES(列)} 函数。
     *
     * @param row 会话行
     * @return 影响行数：新插为 1，命中并实际更新为 2，命中但值未变为 0
     */
    @Insert("""
            INSERT INTO session
                (id, project_id, owner_id, state, turn_index,
                 provider, model_id, system_prompt)
            VALUES
                (#{id}, #{projectId}, #{ownerId}, #{state}, #{turnIndex},
                 #{provider}, #{modelId}, #{systemPrompt})
            AS new
            ON DUPLICATE KEY UPDATE
                project_id    = new.project_id,
                owner_id      = new.owner_id,
                state         = new.state,
                turn_index    = new.turn_index,
                provider      = new.provider,
                model_id      = new.model_id,
                system_prompt = new.system_prompt
            """)
    int save(SessionRow row);

    /**
     * @param id 会话 id
     * @return 会话行；不存在时为 null，由仓储层决定是否包成 {@code Optional}
     */
    @Select("SELECT " + SessionRow.COLUMNS + " FROM session WHERE id = #{id}")
    SessionRow findById(@Param("id") String id);

    /**
     * 项目下的会话，分页；{@code ORDER BY id} 稳定，{@code OFFSET} 翻页不会漏条。
     *
     * @param projectId 项目 id
     * @param limit 每页条数
     * @param offset 偏移量
     * @return 会话行
     */
    @Select("SELECT " + SessionRow.COLUMNS + " FROM session WHERE project_id = #{projectId}"
            + " ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<SessionRow> findByProject(@Param("projectId") String projectId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    /**
     * 全部会话，崩溃恢复的入口。
     *
     * <p>不过滤状态：状态机没有终态（见 {@link com.codeloom.domain.session.SessionState}），
     * 需要恢复的就是所有会话；
     * 恢复逻辑自行判断该做什么，空闲会话是空操作。状态白名单会在新增状态时静默漏掉它。
     *
     * @return 全部会话行
     */
    @Select("SELECT " + SessionRow.COLUMNS + " FROM session ORDER BY id")
    List<SessionRow> findAll();

    /**
     * 删掉这条会话。
     *
     * <p>只服务用户主动丢弃对话。事件、幂等键和这一行三条语句合起来才是"丢弃"，
     * 事务边界在 {@code SessionErasure}。
     *
     * @param id 会话 id
     * @return 删除的行数
     */
    @Delete("DELETE FROM session WHERE id = #{id}")
    int deleteById(@Param("id") String id);

    /**
     * 删掉这个人在这个项目里的所有会话行。
     *
     * <p>必须最后执行：事件和幂等键都按 {@code session_id} 定位，提前删除会让那两条
     * DELETE 匹配 0 行且不报错。见 {@code ProjectDeparture}。
     *
     * @param projectId 项目 id
     * @param ownerId 会话所有者
     * @return 删除的行数
     */
    @Delete("DELETE FROM session WHERE project_id = #{projectId} AND owner_id = #{ownerId}")
    int deleteByProjectAndOwner(@Param("projectId") String projectId,
                                @Param("ownerId") String ownerId);
}
