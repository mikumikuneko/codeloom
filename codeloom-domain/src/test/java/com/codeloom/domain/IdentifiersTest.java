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
 * 四个标识值对象的构造校验。它们放在一起是因为同一模式（record、构造器校验、of 工厂）；
 * 校验必须在这里，因为 HTTP 请求体反序列化出的 id 会直接进入领域，没有第二层拦截。
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
