package com.codeloom.domain.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReasoningEffortTest {

    @Test
    @DisplayName("四个规范值原样解析")
    void parsesCanonicalValues() {
        assertThat(ReasoningEffort.parse("none")).contains(ReasoningEffort.NONE);
        assertThat(ReasoningEffort.parse("low")).contains(ReasoningEffort.LOW);
        assertThat(ReasoningEffort.parse("high")).contains(ReasoningEffort.HIGH);
        assertThat(ReasoningEffort.parse("max")).contains(ReasoningEffort.MAX);
    }

    @Test
    @DisplayName("兼容别名归到规范档：minimal→low，medium/xhigh→high")
    void mapsAliases() {
        assertThat(ReasoningEffort.parse("minimal")).contains(ReasoningEffort.LOW);
        assertThat(ReasoningEffort.parse("medium")).contains(ReasoningEffort.HIGH);
        assertThat(ReasoningEffort.parse("xhigh")).contains(ReasoningEffort.HIGH);
    }

    @Test
    @DisplayName("空值表示没有配置，不发送")
    void blankMeansUnset() {
        assertThat(ReasoningEffort.parse(null)).isEmpty();
        assertThat(ReasoningEffort.parse("  ")).isEmpty();
    }

    @Test
    @DisplayName("不认识的值当场失败，不静默回落到默认")
    void rejectsUnknownValues() {
        assertThatThrownBy(() -> ReasoningEffort.parse("ultra"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("思考强度");
    }
}
