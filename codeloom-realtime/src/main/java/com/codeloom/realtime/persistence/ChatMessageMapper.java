package com.codeloom.realtime.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code chat_message} 表的读写。
 *
 * <p>聊天室是纯人类通道，agent 看不见它，因此这里的读写不参与 agent 循环。
 */
@Mapper
public interface ChatMessageMapper {

    /**
     * 插入并回填自增主键。
     *
     * <p>参数是 {@link ChatMessageInsert} 而不是 record：{@code useGeneratedKeys} 要往
     * 参数对象里写回 id，record 写不进去。
     *
     * @param insert 插入参数；调用后其 id 被回填
     * @return 影响行数，恒为 1
     */
    @Insert("""
            INSERT INTO chat_message (project_id, author_id, `text`, anchor_event_seq, anchor_text, created_at)
            VALUES (#{projectId}, #{authorId}, #{text}, #{anchorEventSeq}, #{anchorText}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ChatMessageInsert insert);

    /**
     * 拉取 {@code afterId} 之后的消息，按 id 升序；用于聊天室断线补齐。开区间。
     *
     * @param projectId 所属项目
     * @param afterId   已收到的最后一条 id
     * @param limit     本批最多取多少条
     * @return 消息行，按 id 升序
     */
    @Select("SELECT " + ChatMessageRow.COLUMNS
            + " FROM chat_message WHERE project_id = #{projectId} AND id > #{afterId}"
            + " ORDER BY id LIMIT #{limit}")
    List<ChatMessageRow> findAfter(@Param("projectId") String projectId,
                                   @Param("afterId") long afterId,
                                   @Param("limit") int limit);

    /**
     * 最近的 N 条，按 id 倒序（最新的在前）。
     *
     * <p>倒序是因为 {@code LIMIT} 只能从一头切，而需要的是最新的 N 条。仓储层负责翻正，
     * 不把存储顺序泄漏给上层。
     *
     * @param projectId 所属项目
     * @param limit     最多取多少条
     * @return 消息行，最新的在前
     */
    @Select("SELECT " + ChatMessageRow.COLUMNS
            + " FROM chat_message WHERE project_id = #{projectId}"
            + " ORDER BY id DESC LIMIT #{limit}")
    List<ChatMessageRow> findRecentDescending(@Param("projectId") String projectId,
                                              @Param("limit") int limit);

    /**
     * 删掉一个项目的全部聊天记录。聊天室挂在项目上，不需要经由别的表。
     *
     * @param projectId 所属项目
     * @return 删除的行数
     */
    @Delete("DELETE FROM chat_message WHERE project_id = #{projectId}")
    int deleteByProject(@Param("projectId") String projectId);
}
