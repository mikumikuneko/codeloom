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
     * <h2>为什么是 {@code INSERT IGNORE}，而不是让主键冲突抛出去再 catch</h2>
     * 主键冲突抛的是 {@code DuplicateKeyException}，而它会让**当前事务**被标记成
     * rollback-only —— catch 住了也没用，这一轮后面任何一次写入都会以
     * "Transaction rolled back because it has been marked as rollback-only" 收场。
     * 而我们要的恰恰是"重复了，但这一轮什么都没做，干净地返回"。
     *
     * <p>代价是它会把别的错误（比如列不存在）也吞成 0 行。这里的表结构由我们自己
     * 的 schema.sql 管，不是用户输入，所以那个风险可以接受 —— 换成用户传进来的
     * 数据就不能这么写。
     *
     * @return 1 = 占到了（第一次）；0 = 已经有了（重复）
     */
    @Insert("INSERT IGNORE INTO turn_request (session_id, client_message_id, created_at) "
            + "VALUES (#{sessionId}, #{clientMessageId}, #{createdAt})")
    int claim(@Param("sessionId") String sessionId,
              @Param("clientMessageId") String clientMessageId,
              @Param("createdAt") Instant createdAt);

    /**
     * 清掉这条会话占过的幂等键，供「丢弃会话」用。
     *
     * <p>它不改变幂等语义：会话都没了，就不会再有人带同一个 {@code clientMessageId}
     * 来问"这个请求处理过了吗"。留着只是垃圾。
     */
    @Delete("DELETE FROM turn_request WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") String sessionId);

    /**
     * 删掉**这个人在这个项目里**所有会话的幂等键。
     *
     * <p>和 {@code EventMapper.deleteByProjectAndOwner} 一样，<strong>必须跑在会话行被删之前</strong>
     * ——它靠 {@code session} 表定位，删早了就是匹配 0 行，而且不报错。
     */
    @Delete("""
            DELETE FROM turn_request
             WHERE session_id IN (SELECT id FROM session
                                   WHERE project_id = #{projectId} AND owner_id = #{ownerId})
            """)
    int deleteByProjectAndOwner(@Param("projectId") String projectId,
                                @Param("ownerId") String ownerId);
}
