package com.codeloom.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionSyncedTest {

    @Test
    @DisplayName("HEAD 前后相同直接拒绝 —— 没发生的事不该有条事件")
    void rejectsANoOpSync() {
        // 这条不只是洁癖：同步是"把主干拉进来"，而主干没动、工作区也没动的时候
        // 调它是一次空操作。落了事件的话，事件流里会多出一条"我同步了"但什么都没变的记录，
        // 而回放的人无从分辨它和一次真的同步
        assertThatThrownBy(() -> new SessionSynced("abc123", "abc123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("什么都没发生");
    }

    @Test
    @DisplayName("前后 HEAD 都不能为空 —— 缺一个就说不清这次同步把谁挪到了哪")
    void rejectsMissingShas() {
        assertThatThrownBy(() -> new SessionSynced(null, "b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionSynced("a", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionSynced("  ", "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("正常的一次同步")
    void acceptsARealSync() {
        assertThatCode(() -> new SessionSynced("aaaa111", "bbbb222")).doesNotThrowAnyException();
    }
}
