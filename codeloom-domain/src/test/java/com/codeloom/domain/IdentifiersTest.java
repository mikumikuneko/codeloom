package com.codeloom.domain;

import com.codeloom.domain.chat.ChatMessageId;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四个标识值对象：只做一件事 —— **拒绝不合法的自己**。
 *
 * <h2>为什么四个放在一个文件里</h2>
 * 它们是同一个模式（{@code record} + 构造器里一条校验 + 一个 {@code of} 工厂），
 * 各写一个测试类会得到四份几乎相同的文件。这里的价值在于把"每个 id 都拒空值"
 * 这件事**一次性钉住**：哪天新增第五个 id 类型时，读这个文件的人会知道它有这个义务。
 *
 * <p>校验之所以必须在这里，是因为它**没有第二个地方兜底**：从 HTTP 请求体里
 * 反序列化出来的 id 会直接进领域，没有一层 Spring 校验拦着。
 */
class IdentifiersTest {

    @Test
    @DisplayName("SessionId / ProjectId / UserId：空、空白、null 一律拒")
    void stringIdentifiersRejectBlanks() {
        assertThatThrownBy(() -> SessionId.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionId.of("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectId.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectId.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserId.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserId.of(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ChatMessageId：非正数拒 —— 它不是 UUID，是存储层分配的自增主键")
    void chatMessageIdMustBePositive() {
        assertThatThrownBy(() -> ChatMessageId.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatMessageId.of(-1)).isInstanceOf(IllegalArgumentException.class);

        assertThat(ChatMessageId.of(1).value()).isEqualTo(1);
    }

    @Test
    @DisplayName("generate 出来的是合法值，而且每次都不一样")
    void generatedIdsAreValidAndDistinct() {
        assertThat(SessionId.generate()).isNotEqualTo(SessionId.generate());
        assertThat(ProjectId.generate().value()).isNotBlank();
        assertThat(SessionId.generate().value()).isNotBlank();
    }

    @Test
    @DisplayName("toString 就是那个值 —— 日志和错误信息里不会出现 UserId[value=…] 这种噪声")
    void toStringIsTheRawValue() {
        assertThat(SessionId.of("s-1")).hasToString("s-1");
        assertThat(ProjectId.of("p-1")).hasToString("p-1");
        assertThat(UserId.of("u-1")).hasToString("u-1");
        assertThat(ChatMessageId.of(7)).hasToString("7");
    }
}
