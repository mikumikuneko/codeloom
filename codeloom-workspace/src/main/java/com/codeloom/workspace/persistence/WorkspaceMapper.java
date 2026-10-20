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
 * <p>{@code fencing_token} 只由下面三个专用方法读写；{@link #save} 一旦带上它，就会
 * 把号写回旧值，让过期租约重新获得写权限。理由见
 * {@code docs/decisions/architecture/2026-09-25-handwritten-sql-keeps-fencing-out-of-reach.md}。
 *
 * <p>这一层不做判断：不校验、不拼装领域对象、不吞异常，只搬行。
 */
@Mapper
public interface WorkspaceMapper {

    /**
     * 新建或整体更新。
     *
     * <p>列清单里没有 {@code fencing_token}：插入时取默认值 0，更新时保持原样，这是
     * 有意的。使用行别名写法（{@code AS new}），不是已废弃的 {@code VALUES(列)} 函数。
     *
     * @param row 工作区行
     * @return 影响行数：新插为 1，命中并实际更新为 2，命中但值未变为 0
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

    /**
     * @param ownerId 树的主人
     * @param projectId 项目 id
     * @return 工作区行；不存在时为 null，由仓储层决定是否包成 {@code Optional}
     */
    @Select("SELECT " + WorkspaceRow.COLUMNS + " FROM workspace"
            + " WHERE owner_id = #{ownerId} AND project_id = #{projectId}")
    WorkspaceRow findById(@Param("ownerId") String ownerId, @Param("projectId") String projectId);

    /**
     * 这个项目下所有人的工作区。
     *
     * <p>{@code ORDER BY owner_id} 让同一份数据每次读回的顺序一致；这里不分页，行数上限
     * 是项目成员数。
     *
     * @param projectId 项目 id
     * @return 工作区行
     */
    @Select("SELECT " + WorkspaceRow.COLUMNS + " FROM workspace"
            + " WHERE project_id = #{projectId} ORDER BY owner_id")
    List<WorkspaceRow> findByProject(@Param("projectId") String projectId);

    // ------------------------------------------------------------------
    // 以下是 fencing token 专用；本接口里只有这三个方法允许碰那一列。
    // ------------------------------------------------------------------

    /**
     * 把 {@code fencing_token} 加一；新值由 {@link #lastIssuedToken()} 在同一连接上取回。
     *
     * <p>用 {@code LAST_INSERT_ID(expr)} 而不是"先 UPDATE 再 SELECT"：后者两条语句之间
     * 可能有别的连接推进号码，本实例会拿到已过期的号。本表没有自增列，借用该语义不会冲突。
     * 见 {@link com.codeloom.domain.port.WorkspaceFence#issue}。
     *
     * @param ownerId 树的主人
     * @param projectId 项目 id
     * @return 1 表示推进成功；0 表示这棵树不存在
     */
    @Update("UPDATE workspace SET fencing_token = LAST_INSERT_ID(fencing_token + 1)"
            + " WHERE owner_id = #{ownerId} AND project_id = #{projectId}")
    int bumpFencingToken(@Param("ownerId") String ownerId, @Param("projectId") String projectId);

    /**
     * 取回本连接上一次由 {@link #bumpFencingToken} 算出的号。
     *
     * <p>只在上一步更新了 1 行时有意义：没有命中时 {@code LAST_INSERT_ID(expr)} 未被求值，
     * 这里读到的是该连接更早的残留值。
     *
     * @return 本连接上一次发出的号
     */
    @Select("SELECT LAST_INSERT_ID()")
    long lastIssuedToken();

    /**
     * 加锁读出当前的有效号，用于校验。
     *
     * <p>{@code FOR UPDATE} 不能去掉：锁会在整个事务期间挡住任何想推进这一行的人，
     * 也就是阻止另一个执行者接管这棵树。见
     * {@link com.codeloom.domain.port.WorkspaceFence#assertValid}。
     *
     * @param ownerId 树的主人
     * @param projectId 项目 id
     * @return 当前号；这棵树不存在时为 null
     */
    @Select("SELECT fencing_token FROM workspace"
            + " WHERE owner_id = #{ownerId} AND project_id = #{projectId} FOR UPDATE")
    Long lockFencingToken(@Param("ownerId") String ownerId, @Param("projectId") String projectId);

    /**
     * 删掉一棵树那一行；主键是"人 + 项目"，两个都要给。
     *
     * @param ownerId 树的主人
     * @param projectId 项目 id
     * @return 删除的行数
     */
    @Delete("DELETE FROM workspace WHERE owner_id = #{ownerId} AND project_id = #{projectId}")
    int deleteById(@Param("ownerId") String ownerId, @Param("projectId") String projectId);
}
