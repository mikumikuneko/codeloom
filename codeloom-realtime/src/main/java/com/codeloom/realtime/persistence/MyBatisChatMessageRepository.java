package com.codeloom.realtime.persistence;

import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.chat.ChatMessageId;
import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@link ChatMessageRepository} 的持久化实现。
 *
 * <p>消息 id 由存储层分配，返回给调用方的对象携带生成后的 id 与截断到毫秒的时间。
 */
@Repository
public class MyBatisChatMessageRepository implements ChatMessageRepository {

    private final ChatMessageMapper mapper;

    public MyBatisChatMessageRepository(ChatMessageMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 追加一条消息，返回存储层分配好 id 的完整对象。
     *
     * <p>本方法必须是事务性的：顺序是先插库、再用 {@link ChatMessage} 的构造器校验正文
     * 与锚点，构造器抛异常时插入要跟着回滚。把校验提前则要在仓储里再抄一遍领域规则。
     *
     * @param projectId     所属项目
     * @param authorId      作者，取自已认证身份
     * @param text          消息正文
     * @param anchorEventSeq 可选锚点
     * @param anchorText    锚点动作说明
     * @param occurredAt    发送时间
     * @return 携带生成后 id 的消息
     * @throws NullPointerException 正文为 null
     * @throws IllegalArgumentException 正文为空白或锚点不合法（由领域构造器校验，事务回滚）
     * @throws IllegalStateException 插入后没有回填自增主键
     */
    @Override
    @Transactional
    public ChatMessage append(ProjectId projectId, UserId authorId, String text,
                              Long anchorEventSeq, String anchorText, Instant occurredAt) {
        // created_at 是 DATETIME(3)，Instant 通常精确到微秒。不截断的话，广播出去的那一条
        // 与之后从历史接口读回来的那一条时间不相等，同一条消息会出现两个时刻。
        // 与事件落库使用同一做法。
        Instant storedAt = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        ChatMessageInsert insert = new ChatMessageInsert(
                projectId.value(), authorId.value(), text, anchorEventSeq, anchorText, storedAt);
        mapper.insert(insert);

        Long generatedId = insert.getId();
        if (generatedId == null) {
            // 理论上不会发生；真发生时这里的报错比下游的空指针有用。
            throw new IllegalStateException(
                    "chat_message 插入后没有回填自增主键：项目 " + projectId + " 的这条消息 id 无法确定");
        }
        return new ChatMessage(ChatMessageId.of(generatedId), projectId, authorId,
                text, anchorEventSeq, anchorText, storedAt);
    }

    @Override
    public List<ChatMessage> findAfter(ProjectId projectId, long afterId, int limit) {
        return mapper.findAfter(projectId.value(), afterId, limit).stream()
                .map(ChatMessageRow::toDomain)
                .toList();
    }

    @Override
    public List<ChatMessage> findRecent(ProjectId projectId, int limit) {
        List<ChatMessageRow> newestFirst = mapper.findRecentDescending(projectId.value(), limit);
        // 翻回时间正序：上层都按正序消费，不能把存储层的倒序泄漏出去。
        return newestFirst.reversed().stream()
                .map(ChatMessageRow::toDomain)
                .toList();
    }

    @Override
    public void deleteByProject(ProjectId projectId) {
        // 不看影响行数：一个从没说过话的项目删出 0 行是正常结果，不是错误
        mapper.deleteByProject(projectId.value());
    }
}
