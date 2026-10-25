package com.codeloom.realtime.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code event} 表的读写。append-only：本接口没有 UPDATE，也没有逐条 DELETE。
 *
 * <p>这是表的性质，不是暂时没写：回放、断线补齐、审计、崩溃恢复都建立在“已经写下的行
 * 不会再变”上。需要修正历史时只能追加一条事件。见
 * {@code docs/decisions/architecture/2026-09-24-one-event-log-as-the-source-of-truth.md}。
 *
 * <p>例外只有整条会话一起删（{@link #deleteBySession} 与
 * {@link #deleteByProjectAndOwner}）：删除整条流后剩余部分仍然自洽，删流里的某几条则会让
 * 回放讲不通。它们服务用户主动丢弃会话和退出项目两件事，理由见 {@code EventDiscard} 的类注释。
 */
@Mapper
public interface EventMapper {

    /**
     * 批量插入并逐个回填自增主键。单条追加也走这里（传一个元素的列表），避免单条与批量
     * 各有一套可能走岔的代码。
     *
     * <p>参数必须是不带 {@code @Param} 的裸 {@code List}：{@code keyProperty = "id"} 要作用
     * 在列表的每个元素上，MyBatis 只有在参数本身就是集合时才会遍历它。加上 {@code @Param}
     * 后参数变成 Map，属性路径要改成 {@code events.id}；因此 {@code <foreach>} 的集合名
     * 固定为 {@code list}。
     *
     * @param events 要插入的事件
     * @return 影响行数，等于 {@code events.size()}
     */
    @Insert("""
            <script>
            INSERT INTO `event` (session_id, `type`, payload, occurred_at) VALUES
            <foreach item="e" collection="list" separator=",">
                (#{e.sessionId}, #{e.type}, #{e.payload}, #{e.occurredAt})
            </foreach>
            </script>
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertAll(List<EventInsert> events);

    /**
     * 拉取 {@code afterSeq} 之后的事件，按 id 升序；用于断线重连补齐。开区间。
     *
     * @param sessionId 会话
     * @param afterSeq  已收到的最后一个 seq
     * @param limit     本批最多取多少条
     * @return 事件行，按 id 升序
     */
    @Select("SELECT " + EventRow.COLUMNS
            + " FROM `event` WHERE session_id = #{sessionId} AND id > #{afterSeq}"
            + " ORDER BY id LIMIT #{limit}")
    List<EventRow> findAfter(@Param("sessionId") String sessionId,
                             @Param("afterSeq") long afterSeq,
                             @Param("limit") int limit);

    /**
     * 拉全量事件，用于回放。按 id 升序，也就是它们发生的顺序。
     *
     * @param sessionId 会话
     * @return 该会话的全部事件
     */
    @Select("SELECT " + EventRow.COLUMNS
            + " FROM `event` WHERE session_id = #{sessionId} ORDER BY id")
    List<EventRow> readAll(@Param("sessionId") String sessionId);

    /**
     * 这条会话最后一条指定类型的事件；一条都没有时返回 null。
     *
     * <p>沿 {@code (session_id, id)} 索引倒序扫描。调用的事件类型不保证每轮都写；有记录时
     * 通常几行之内就命中。
     *
     * @param sessionId 会话
     * @param type      事件类型名
     * @return 最后一条该类型事件；没有时为 null
     */
    @Select("SELECT " + EventRow.COLUMNS
            + " FROM `event` WHERE session_id = #{sessionId} AND `type` = #{type}"
            + " ORDER BY id DESC LIMIT 1")
    EventRow findLastOfType(@Param("sessionId") String sessionId, @Param("type") String type);

    /**
     * 最后一次开启一轮的事件：用户发言、别的 agent 的留言，或批准后继续跑。
     *
     * <p>这三种之外的事件都是轮内产物；平台指令尤其不是开轮——它由 {@code AgentTurn}
     * 在轮内注入（输出被截断、验证失败时）。用量事件只有在轮真正收尾时才写，拿它和
     * 最后一条开启事件比先后，就能判断最后一轮有没有读数；只看最后一条用量事件会把
     * 上一轮的读数当成这一轮的。
     *
     * @param sessionId 会话
     * @return 最后一条开启事件；没有时为 null
     */
    @Select("SELECT " + EventRow.COLUMNS
            + " FROM `event` WHERE session_id = #{sessionId}"
            + " AND `type` IN ('USER_MESSAGE', 'AGENT_NOTE_DELIVERED', 'TOOL_APPROVAL_RESOLVED')"
            + " ORDER BY id DESC LIMIT 1")
    EventRow findLastTurnStart(@Param("sessionId") String sessionId);

    /**
     * 删掉这条会话的全部事件。
     *
     * <p>这会让 seq 出现空洞：删掉的 seq 不会再分配给别人。读路径不受影响，断线补齐按
     * {@code id > cursor} 拉、回放按会话拉，都不假设 seq 连续；schema.sql 里“分配顺序等于
     * 提交顺序”的论证针对并发写入，与删除无关。
     *
     * @param sessionId 会话
     * @return 删除的行数
     */
    @Delete("DELETE FROM `event` WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") String sessionId);

    /**
     * 删掉这个人在这个项目里所有会话的事件。
     *
     * <p>必须在 {@code session} 行被删之前执行：这条语句靠 {@code session} 表定位事件。
     * 会话行先没了它一条也匹配不上，而 DELETE 影响 0 行不会报错，那些事件会静默地留在库里。
     *
     * @param projectId 项目
     * @param ownerId   会话拥有者
     * @return 删除的行数
     */
    @Delete("""
            DELETE FROM `event`
             WHERE session_id IN (SELECT id FROM session
                                   WHERE project_id = #{projectId} AND owner_id = #{ownerId})
            """)
    int deleteByProjectAndOwner(@Param("projectId") String projectId,
                                @Param("ownerId") String ownerId);
}
