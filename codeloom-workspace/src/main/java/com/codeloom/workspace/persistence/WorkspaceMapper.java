package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code workspace} 表的读写。
 *
 * <h2>它是 {@code fencing_token} 那一列在**发号与校验路径**上的唯一写入者</h2>
 * 这个类里只有下面那三个方法允许碰那一列。上面的 {@link #save} 一旦带上它，
 * 就会把号写回旧值，僵尸写入者于是又能落数据 —— 这正是当初给 {@code session}
 * 手写 SQL 而不是继承 {@code BaseMapper} 的那条理由，现在跟着这一列搬到了这里；
 * 它的由来见 {@code docs/decisions/architecture/2026-09-25-handwritten-sql-keeps-fencing-out-of-reach.md}。
 *
 * <h2>这一层不做任何判断</h2>
 * 不校验、不拼装领域对象、不吞异常。只搬行。
 */
@Mapper
public interface WorkspaceMapper {

    /**
     * 新建或整体更新。
     *
     * <p><strong>注意列清单里没有 {@code fencing_token}</strong>：插入时它取默认值 0，
     * 更新时保持原样。这不是漏写，是本类注释里说的那条约束。
     *
     * <p>用行别名写法（{@code AS new}），不是已废弃的 {@code VALUES(列)} 函数。
     *
     * @return 影响行数：新插为 1，命中已有行并真的更新了为 2，命中但值没变为 0
     */
    @Insert("""
            INSERT INTO workspace
                (owner_id, project_id, branch, worktree_path, head_commit)
            VALUES
                (#{ownerId}, #{projectId}, #{branch}, #{worktreePath}, #{headCommit})
            AS new
            ON DUPLICATE KEY UPDATE
                branch        = new.branch,
                worktree_path = new.worktree_path,
                head_commit   = new.head_commit
            """)
    int save(WorkspaceRow row);

    /** @return 不存在时返回 null；由仓储层决定要不要包成 {@code Optional} */
    @Select("SELECT " + WorkspaceRow.COLUMNS + " FROM workspace"
            + " WHERE owner_id = #{ownerId} AND project_id = #{projectId}")
    WorkspaceRow findById(@Param("ownerId") String ownerId, @Param("projectId") String projectId);

    /**
     * 这个项目下所有人的工作区。
     *
     * <p>{@code ORDER BY owner_id} 让它稳定 —— 不是给分页用的（这张表的行数上限是成员数），
     * 而是让"同一份数据每次读回来顺序一样"，排查时能直接对。
     */
    @Select("SELECT " + WorkspaceRow.COLUMNS + " FROM workspace"
            + " WHERE project_id = #{projectId} ORDER BY owner_id")
    List<WorkspaceRow> findByProject(@Param("projectId") String projectId);

    // ------------------------------------------------------------------
    // 以下是 fencing token 专用。本接口里**只有这三个方法**允许碰那一列。
    // ------------------------------------------------------------------

    /**
     * 把 {@code fencing_token} 加一并**返回新值**。发号的实现，见
     * {@link com.codeloom.domain.port.WorkspaceFence#issue}。
     *
     * <p>用 MySQL 的 {@code LAST_INSERT_ID(expr)} 而不是「先 UPDATE 再 SELECT」：
     * 后者两条语句之间会有别的连接插进来把号又推一格，于是这个实例拿到一个
     * 已经过期的号并立刻失去写入权。{@code LAST_INSERT_ID(expr)} 把「计算新值」
     * 和「把新值记为本次连接的 last_insert_id」放在同一条语句里，随后读
     * {@link #lastIssuedToken()} 拿到的必然是**本连接这一步算出来的那个值**。
     *
     * <p>本表没有自增列，所以借 {@code LAST_INSERT_ID} 的语义不会和别的地方打架。
     *
     * @return 1 表示推进成功；0 表示这棵树不存在
     */
    @Update("UPDATE workspace SET fencing_token = LAST_INSERT_ID(fencing_token + 1)"
            + " WHERE owner_id = #{ownerId} AND project_id = #{projectId}")
    int bumpFencingToken(@Param("ownerId") String ownerId, @Param("projectId") String projectId);

    /**
     * 取回本连接上一次由 {@link #bumpFencingToken} 算出的号。
     *
     * <p><strong>只在上一步 UPDATE 影响了 1 行时才有意义</strong>：一行都没命中时
     * {@code LAST_INSERT_ID(expr)} 根本没被求值，这里读回来的是这条连接更早的残留值。
     */
    @Select("SELECT LAST_INSERT_ID()")
    long lastIssuedToken();

    /**
     * 加锁读出当前的有效号，用于校验。
     *
     * <p>{@code FOR UPDATE} 不能去掉，它是这一整套得以成立的地方：普通查询读到的值
     * 在事务提交前就可能过期，而锁会在整个事务期间挡住任何想推进这一行的人
     * （也就是挡住"另一个执行者接管了这棵树"）。见
     * {@link com.codeloom.domain.port.WorkspaceFence#assertValid}。
     *
     * @return 这棵树不存在时为 null
     */
    @Select("SELECT fencing_token FROM workspace"
            + " WHERE owner_id = #{ownerId} AND project_id = #{projectId} FOR UPDATE")
    Long lockFencingToken(@Param("ownerId") String ownerId, @Param("projectId") String projectId);

    /** 删掉一棵树那一行。主键是「人 + 项目」，所以两个都要给。 */
    @Delete("DELETE FROM workspace WHERE owner_id = #{ownerId} AND project_id = #{projectId}")
    int deleteById(@Param("ownerId") String ownerId, @Param("projectId") String projectId);
}
