package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface TurnRequestMapper {

    /**
     * 占一个幂等键。
     *
     * <p>用 {@code INSERT IGNORE} 而不是捕获主键冲突：冲突异常的抛出会把当前事务标记为
     * rollback-only，即使 catch 住，后续写入也会失败；这里需要的是"重复时干净返回"。
     * 代价是其它错误也可能被吞成 0 行，表结构由本项目 schema.sql 管理，可以接受。
     *
     * @param sessionId 会话 id
     * @param clientMessageId 客户端幂等键
     * @param createdAt 创建时间
     * @return 1 表示第一次占到；0 表示已经存在
     */
    @Insert("INSERT IGNORE INTO turn_request (session_id, client_message_id, created_at) "
            + "VALUES (#{sessionId}, #{clientMessageId}, #{createdAt})")
    int claim(@Param("sessionId") String sessionId,
              @Param("clientMessageId") String clientMessageId,
              @Param("createdAt") Instant createdAt);

    /**
     * 清掉这条会话占过的幂等键，供丢弃会话用。
     *
     * <p>不改变幂等语义：会话不存在后，不会再有人用同一个 {@code clientMessageId} 查询。
     *
     * @param sessionId 会话 id
     * @return 删除的行数
     */
    @Delete("DELETE FROM turn_request WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") String sessionId);

    /**
     * 删掉这个人在这个项目里所有会话的幂等键。
     *
     * <p>必须跑在会话行被删之前：它靠 {@code session} 表定位，提前删除会匹配 0 行且不报错。
     *
     * @param projectId 项目 id
     * @param ownerId 会话所有者
     * @return 删除的行数
     */
    @Delete("""
            DELETE FROM turn_request
             WHERE session_id IN (SELECT id FROM session
                                   WHERE project_id = #{projectId} AND owner_id = #{ownerId})
            """)
    int deleteByProjectAndOwner(@Param("projectId") String projectId,
                                @Param("ownerId") String ownerId);
}
