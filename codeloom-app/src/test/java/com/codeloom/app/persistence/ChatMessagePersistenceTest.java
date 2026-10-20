package com.codeloom.app.persistence;

import com.codeloom.app.support.AbstractPersistenceTest;
import com.codeloom.domain.chat.ChatMessage;
import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.project.ProjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code chat_message} 表的真库测试。
 *
 * <p>必须对着真库验的是自增主键回填：{@code append} 要返回 id 已分配的完整对象，
 * 而 id 由 {@code useGeneratedKeys} 回填，纯单测覆盖不到。
 *
 * <p>每个测试用新生成的 projectId，这个项目下的消息必然只有本测试插的，
 * 断言可以精确到条数和顺序。
 */
class ChatMessagePersistenceTest extends AbstractPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00.500Z");

    @Autowired
    private ChatMessageRepository chat;

    /** 每个测试方法都是一个新实例，所以这个 id 天然是每个测试独有的。 */
    private final ProjectId projectId = ProjectId.generate();

    @Test
    @DisplayName("append 返回存储层分配好 id 的完整消息，且这一行真的在库里")
    void appendReturnsTheAssignedId() {
        ChatMessage message = chat.append(projectId, ALICE, "看一下 OrderService", null, null, NOW);

        assertThat(message.id().value()).isPositive();
        // 用 id-1 做游标只会命中这一条：id 全局自增，前一个号即使属于别人，
        // 也不在这个 projectId 下。
        assertThat(chat.findAfter(projectId, message.id().value() - 1, 10)).containsExactly(message);
    }

    @Test
    @DisplayName("锚点可空：普通消息没有锚点，引用回复带着它指向的那条事件序号")
    void anchorIsOptional() {
        ChatMessage plain = chat.append(projectId, ALICE, "普通消息", null, null, NOW);
        ChatMessage anchored = chat.append(projectId, BOB, "你这段不对", 42L, "跑了一次 mvn test", NOW);

        assertThat(plain.anchorEventSeq()).isNull();
        assertThat(chat.findAfter(projectId, anchored.id().value() - 1, 1))
                .singleElement()
                .satisfies(m -> assertThat(m.anchorEventSeq()).isEqualTo(42L));
    }

    @Test
    @DisplayName("findRecent 取最近 N 条，而且是时间正序（倒序由仓储翻回来了）")
    void findRecentReturnsTheNewestInChronologicalOrder() {
        chat.append(projectId, ALICE, "第一条", null, null, NOW);
        chat.append(projectId, BOB, "第二条", null, null, NOW);
        chat.append(projectId, ALICE, "第三条", null, null, NOW);

        List<String> texts = chat.findRecent(projectId, 2).stream().map(ChatMessage::text).toList();

        // 最新的两条按时间正序回来 —— 不是 ["第三条","第二条"]
        assertThat(texts).containsExactly("第二条", "第三条");
    }

    @Test
    @DisplayName("findAfter 是开区间：游标那条自己不再返回")
    void findAfterIsExclusive() {
        ChatMessage first = chat.append(projectId, ALICE, "甲", null, null, NOW);
        ChatMessage second = chat.append(projectId, BOB, "乙", null, null, NOW);

        assertThat(chat.findAfter(projectId, first.id().value(), 10))
                .extracting(ChatMessage::text)
                .containsExactly("乙");
        assertThat(second.id().value()).isGreaterThan(first.id().value());
    }

    @Test
    @DisplayName("【精度边界】created_at 是 DATETIME(3)：**返回的那条和读回来的那条必须是同一个时刻**")
    void subMillisecondPrecisionIsTruncatedOnTheWayIn() {
        // 列是 DATETIME(3)：微秒以下不存在。关键是在写进去之前就截，否则广播出去的
        // 那条（append 返回值）和历史接口读回来的那条会带着两个时刻。
        Instant withNanos = Instant.parse("2026-09-25T10:00:00.123456789Z");

        ChatMessage saved = chat.append(projectId, ALICE, "精度", null, null, withNanos);

        Instant stored = Instant.parse("2026-09-25T10:00:00.123Z");
        assertThat(saved.createdAt()).isEqualTo(stored);
        assertThat(chat.findAfter(projectId, saved.id().value() - 1, 1).getFirst().createdAt())
                .isEqualTo(stored);
    }
}
