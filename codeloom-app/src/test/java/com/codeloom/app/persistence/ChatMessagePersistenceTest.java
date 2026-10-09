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
 * <p>这个类里唯一必须对着真库验的是**自增主键回填**：{@code append} 要返回一个
 * id 已分配好的完整对象，而那个 id 是 {@code useGeneratedKeys} 塞进
 * {@code ChatMessageInsert} 的。这条路径没有纯单测能覆盖。
 *
 * <p>每个测试用**新生成的 projectId**，于是「这个项目下的消息」必然只有本测试插的，
 * 断言可以精确到条数和顺序；不然就得写成 contains。
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
        // 用 id-1 做游标，命中的必然只有这一条：id 是全局自增的，它前面那个号哪怕是别人的
        // 消息，也不属于这个 projectId
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
    @DisplayName("【精度边界】created_at 是 DATETIME(3)，毫秒以下会被截掉 —— 这是已知且可接受的")
    void subMillisecondPrecisionIsTruncated() {
        // 时间戳用的是 DATETIME(3)（建表时定的：同一会话可能在同一毫秒内落多条事件，
        // 秒级精度排不出先后）。代价就是微秒以下不存在。
        // 这里把它写成一个测试而不是一句注释，是为了让「精度到毫秒为止」这件事
        // 在有人将来想按纳秒比较时间时能立刻被想起来。
        Instant withNanos = Instant.parse("2026-09-25T10:00:00.123456789Z");

        ChatMessage saved = chat.append(projectId, ALICE, "精度", null, null, withNanos);

        assertThat(saved.createdAt()).isEqualTo(withNanos);   // 领域对象里仍是原值
        assertThat(chat.findAfter(projectId, saved.id().value() - 1, 1).getFirst().createdAt())
                .isEqualTo(Instant.parse("2026-09-25T10:00:00.123Z"));   // 库里只剩到毫秒
    }
}
