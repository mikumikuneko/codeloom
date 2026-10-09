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
 * <p>聊天室是**纯人类通道**，agent 看不见它，所以这里的读写不参与任何 agent 循环 ——
 * 它是本项目里少见的、纯粹的增查两件事。
 */
@Mapper
public interface ChatMessageMapper {

    /**
     * 插入并回填自增主键。
     *
     * <p>参数类型是 {@link ChatMessageInsert} 而不是 record，见那个类的注释 ——
     * 一句话：{@code useGeneratedKeys} 要往参数对象里写回 id，而 record 写不进去。
     *
     * @return 影响行数，恒为 1
     */
    @Insert("""
            INSERT INTO chat_message (project_id, author_id, `text`, anchor_event_seq, anchor_text, created_at)
            VALUES (#{projectId}, #{authorId}, #{text}, #{anchorEventSeq}, #{anchorText}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ChatMessageInsert insert);

    /** 拉 {@code afterId} 之后的消息，用于聊天室的断线补齐。 */
    @Select("SELECT " + ChatMessageRow.COLUMNS
            + " FROM chat_message WHERE project_id = #{projectId} AND id > #{afterId}"
            + " ORDER BY id LIMIT #{limit}")
    List<ChatMessageRow> findAfter(@Param("projectId") String projectId,
                                   @Param("afterId") long afterId,
                                   @Param("limit") int limit);

    /**
     * 最近的 N 条，**按 id 倒序**（最新的在前）。
     *
     * <p>倒序是因为 {@code LIMIT} 只能从一头切，而我们要的是「最新的 N 条」。
     * 方法名里带 {@code Descending} 是为了让调用方知道回来的是倒的 ——
     * 仓储层负责翻正，别让存储的顺序泄漏到上层去。
     */
    @Select("SELECT " + ChatMessageRow.COLUMNS
            + " FROM chat_message WHERE project_id = #{projectId}"
            + " ORDER BY id DESC LIMIT #{limit}")
    List<ChatMessageRow> findRecentDescending(@Param("projectId") String projectId,
                                              @Param("limit") int limit);

    /** 删掉一个项目的全部聊天记录。聊天室本来就挂在项目上，不需要经由别的表。 */
    @Delete("DELETE FROM chat_message WHERE project_id = #{projectId}")
    int deleteByProject(@Param("projectId") String projectId);
}
