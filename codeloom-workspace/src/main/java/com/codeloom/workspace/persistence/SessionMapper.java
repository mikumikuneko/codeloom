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
 * <h2>为什么是手写 SQL 而不是继承 {@code BaseMapper}</h2>
 * 两个理由，都不是「手写更酷」：
 *
 * <ol>
 *   <li>本表的 {@code save} 语义是「不存在就插、存在就整体更新」，而领域里的
 *       {@code Session} 没有「是不是新的」这个标志，所以它只对应一条 upsert 语句。
 *   <li>写显式 SQL 时，**语句里出现了哪些列是看得见的**。这张表有一列
 *       （{@code fencing_token}）绝对不能出现在普通写入路径上，而"哪些列在里面"
 *       正是靠这个可见性守住的 —— 见 {@link WorkspaceMapper}：那一列连同它的
 *       三个专用方法一起搬到了工作区那边。
 * </ol>
 *
 * <p>这条约束为什么值得用"少一层自动映射"去换，见
 * {@code docs/decisions/architecture/2026-09-25-handwritten-sql-keeps-fencing-out-of-reach.md}。
 *
 * <h2>这一层不做任何判断</h2>
 * 不校验状态迁移、不拼装领域对象、不吞异常。它只负责把行搬进搬出，把
 * {@link SessionRow} 与领域对象的转换交给调用方 —— 于是「SQL 对不对」和
 * 「领域规则对不对」是两个可以分开验证的问题。
 */
@Mapper
public interface SessionMapper {

    /**
     * 新建或整体更新（{@code INSERT ... ON DUPLICATE KEY UPDATE}）。
     *
     * <p>用的是 MySQL 8.0.19+ 的行别名写法（{@code AS new}），不是已废弃的
     * {@code VALUES(列)} 函数。
     *
     * @return 影响行数：新插为 1，命中已有行并真的更新了为 2，命中但值没变为 0
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

    /** @return 不存在时返回 null；由仓储层决定要不要包成 {@code Optional} */
    @Select("SELECT " + SessionRow.COLUMNS + " FROM session WHERE id = #{id}")
    SessionRow findById(@Param("id") String id);

    /** 项目下的会话，分页。{@code ORDER BY id} 是稳定的，{@code OFFSET} 翻页不会漏条。 */
    @Select("SELECT " + SessionRow.COLUMNS + " FROM session WHERE project_id = #{projectId}"
            + " ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<SessionRow> findByProject(@Param("projectId") String projectId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    /**
     * 全部会话 —— 崩溃恢复的入口。
     *
     * <p>不过滤状态：状态机里**没有终态**（见 {@code SessionState} 的注释），
     * 所以"需要恢复的会话"就是"所有会话"。恢复逻辑自己按状态和未完成的工具调用
     * 判断该做什么，一条空闲着的会话对它来说是空操作。
     *
     * <p>刻意不写成"列出那几个可能未完成的状态"：那种白名单会在新增状态时
     * **静默漏掉**它，而全量扫描只是多读几行 —— 会话表的数量级很小。
     */
    @Select("SELECT " + SessionRow.COLUMNS + " FROM session ORDER BY id")
    List<SessionRow> findAll();

    /**
     * 删掉这条会话。
     *
     * <p>本接口里**唯一**一条 DELETE，而它只服务一件事：用户主动丢弃一条对话。
     * 三条语句（事件、幂等键、这一行）合起来才是"丢弃"，事务边界在
     * {@code SessionErasure} 那边。
     */
    @Delete("DELETE FROM session WHERE id = #{id}")
    int deleteById(@Param("id") String id);

    /**
     * 删掉**这个人在这个项目里**的所有会话行。
     *
     * <p><strong>它必须最后跑</strong>：事件和幂等键都是按下标 {@code session_id} 找的，
     * 这里删早了，那两条 DELETE 就成了"匹配 0 行"，而且不报错。
     * 见 {@code ProjectDeparture}。
     */
    @Delete("DELETE FROM session WHERE project_id = #{projectId} AND owner_id = #{ownerId}")
    int deleteByProjectAndOwner(@Param("projectId") String projectId,
                                @Param("ownerId") String ownerId);
}
