package com.codeloom.realtime.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code event} 表的读写。**append-only：本接口里没有 UPDATE，也没有逐条的 DELETE。**
 *
 * <p>这不是"暂时没写"，是这张表的性质：回放、断线补齐、审计、崩溃恢复全都建立在
 * 「已经写下的行不会再变」上面。需要修正历史时，做法是再追加一条事件，
 * 而不是改旧的 —— 事件溯源里没有"改历史"这个操作。
 *
 * <h2>例外：整条会话一起删</h2>
 * 本接口只有两个 DELETE（{@link #deleteBySession} 与 {@link #deleteByProjectAndOwner}），
 * 删的都是**整条会话的流**，不是流里的某几条。这个区别是要紧的：改历史会让"回放到一半"
 * 讲不通，而整条流一起消失，剩下的话仍然自洽。
 *
 * <p>它们服务两件事：用户主动丢弃一条会话、一个人退出项目。前者见 {@code EventDiscard}
 * 的类注释 —— 那里写了为什么这件事正当，以及为什么不把它挂在 {@code EventStore} 上。
 */
@Mapper
public interface EventMapper {

    /**
     * 批量插入并逐个回填自增主键。单条追加也用这个（传一个元素的列表）——
     * 一条语句走两条路径，比"单条一个方法 + 批量一个方法"少一份会走岔的代码。
     *
     * <p>参数是**裸的 {@code List} 而不是 {@code @Param("events") List}**：
     * {@code keyProperty = "id"} 要作用在**列表的每个元素**上，MyBatis 只有在参数
     * 就是那个集合时才会去遍历它；加了 {@code @Param} 之后参数变成 Map，
     * 属性路径就得改成 {@code "events.id"} 这类写法，是同一件事的两种拼法，
     * 没必要多记一个。所以下面 {@code <foreach>} 里的集合名是固定的 {@code list}。
     *
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

    /** 拉 {@code afterSeq} 之后的事件，用于断线重连补齐。开区间。 */
    @Select("SELECT " + EventRow.COLUMNS
            + " FROM `event` WHERE session_id = #{sessionId} AND id > #{afterSeq}"
            + " ORDER BY id LIMIT #{limit}")
    List<EventRow> findAfter(@Param("sessionId") String sessionId,
                             @Param("afterSeq") long afterSeq,
                             @Param("limit") int limit);

    /** 拉全量事件，用于回放。按 id 升序 —— 那是它们发生的顺序。 */
    @Select("SELECT " + EventRow.COLUMNS
            + " FROM `event` WHERE session_id = #{sessionId} ORDER BY id")
    List<EventRow> readAll(@Param("sessionId") String sessionId);

    /**
     * 当前最后一条事件的 seq；一条都没有时是 0。
     *
     * <p>{@code COALESCE} 不能省：{@code MAX()} 在空集上返回 {@code NULL}，
     * 而领域那边约定"没有事件时返回 0"，让 null 漏到 Java 层会变成一次空指针。
     */
    @Select("SELECT COALESCE(MAX(id), 0) FROM `event` WHERE session_id = #{sessionId}")
    long lastSeq(@Param("sessionId") String sessionId);

    /**
     * 这条会话**最后一条**指定类型的事件；一条都没有时返回 null。
     *
     * <p>走 {@code (session_id, id)} 那条索引**倒着**扫：问它的都是"最近怎么样"，
     * 而它要找的那类事件通常每轮都写，所以几行之内就命中。
     */
    @Select("SELECT " + EventRow.COLUMNS
            + " FROM `event` WHERE session_id = #{sessionId} AND `type` = #{type}"
            + " ORDER BY id DESC LIMIT 1")
    EventRow findLastOfType(@Param("sessionId") String sessionId, @Param("type") String type);

    /**
     * 删掉这条会话的全部事件 —— 见类注释，这是两条 DELETE 里按会话的那一条。
     *
     * <p>它**除了让 seq 出现空洞，不做任何事**：删掉的那段 seq 不会再被分配给别人，
     * 于是 event.id 上留下了一段空洞。这不影响任何读法 —— 断线补齐按
     * {@code id > cursor} 拉、回放按会话拉，两者都不假设 seq 连续；
     * 而真正要求"分配顺序 = 提交顺序"的那个论证（见 schema.sql）说的是并发写入，
     * 与删除无关。
     */
    @Delete("DELETE FROM `event` WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") String sessionId);

    /**
     * 删掉**这个人在这个项目里**所有会话的事件。
     *
     * <p><strong>必须跑在 {@code session} 行被删之前</strong>：这条语句靠 {@code session}
     * 表定位"哪些事件属于这个人"，会话行没了它就一条都匹配不上 ——
     * 而 DELETE 影响 0 行不会报错，于是那些事件会**静默地**永远留在库里。
     */
    @Delete("""
            DELETE FROM `event`
             WHERE session_id IN (SELECT id FROM session
                                   WHERE project_id = #{projectId} AND owner_id = #{ownerId})
            """)
    int deleteByProjectAndOwner(@Param("projectId") String projectId,
                                @Param("ownerId") String ownerId);
}
