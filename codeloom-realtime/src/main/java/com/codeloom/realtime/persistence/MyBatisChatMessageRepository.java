package com.codeloom.realtime.persistence;

import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.chat.ChatMessageId;
import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * {@link ChatMessageRepository} 的持久化实现。
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
     * <h2>为什么要 {@code @Transactional}</h2>
     * 顺序是「先插库，再用 {@link ChatMessage} 的构造器校验」。空正文、非正的锚点
     * 这些校验在构造器里，也就是说校验**发生在插入之后** —— 靠事务把它们绑在一起，
     * 构造器抛异常时那一行插入跟着回滚，不会留在库里。
     *
     * <p>把校验提到插入之前也可以，那就要在仓储里再抄一遍领域规则；
     * 抄一遍意味着以后有两处要同步，而这里一份事务就能解决。
     */
    @Override
    @Transactional
    public ChatMessage append(ProjectId projectId, UserId authorId, String text,
                              Long anchorEventSeq, String anchorText, Instant occurredAt) {
        ChatMessageInsert insert = new ChatMessageInsert(
                projectId.value(), authorId.value(), text, anchorEventSeq, anchorText, occurredAt);
        mapper.insert(insert);

        Long generatedId = insert.getId();
        if (generatedId == null) {
            // 理论上不会发生。真发生了，这里给出的话比三步之外某个地方的空指针有用得多
            throw new IllegalStateException(
                    "chat_message 插入后没有回填自增主键：项目 " + projectId + " 的这条消息 id 无法确定");
        }
        return new ChatMessage(ChatMessageId.of(generatedId), projectId, authorId,
                text, anchorEventSeq, anchorText, occurredAt);
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
        // 翻回时间正序。上层（渲染历史、断线补齐）都按正序消费，
        // 要它们记住「这个方法的返回是倒的」是把存储的实现细节泄漏出去。
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
