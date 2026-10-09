package com.codeloom.domain.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.codeloom.domain.session.SessionState.EXECUTING_TOOL;
import static com.codeloom.domain.session.SessionState.FAILED;
import static com.codeloom.domain.session.SessionState.IDLE;
import static com.codeloom.domain.session.SessionState.THINKING;
import static com.codeloom.domain.session.SessionState.WAITING_USER;

/**
 * 这个测试类不需要 Spring、不需要数据库、不需要 mock 任何东西 ——
 * 这正是把 domain 做成零依赖模块换来的。
 */
class SessionStateTest {

    @Nested
    @DisplayName("正常流转")
    class HappyPath {

        @Test
        @DisplayName("完整一轮：IDLE → THINKING → EXECUTING_TOOL → THINKING → WAITING_USER")
        void fullTurn() {
            SessionState s = IDLE;
            s = s.transitionTo(THINKING);
            s = s.transitionTo(EXECUTING_TOOL);
            s = s.transitionTo(THINKING);
            s = s.transitionTo(WAITING_USER);

            assertThat(s).isEqualTo(WAITING_USER);
        }

        @Test
        @DisplayName("直接给最终回复（不调工具）：THINKING → WAITING_USER")
        void thinkingToWaitingUserWithoutTool() {
            assertThat(THINKING.transitionTo(WAITING_USER)).isEqualTo(WAITING_USER);
        }

        @Test
        @DisplayName("用户接着说下一句：WAITING_USER → THINKING")
        void userSpeaksAgain() {
            assertThat(WAITING_USER.transitionTo(THINKING)).isEqualTo(THINKING);
        }

        @Test
        @DisplayName("transitionTo 返回目标状态，便于链式写法")
        void transitionReturnsTarget() {
            assertThat(IDLE.transitionTo(THINKING)).isSameAs(THINKING);
        }
    }

    @Nested
    @DisplayName("逃生舱：任意非终态都能进 FAILED")
    class EscapeHatch {

        @ParameterizedTest
        @EnumSource(value = SessionState.class, names = {"IDLE", "THINKING", "EXECUTING_TOOL", "WAITING_USER"})
        @DisplayName("四个非终态都能进 FAILED")
        void anyNonTerminalCanFail(SessionState from) {
            assertThat(from.canTransitionTo(FAILED)).isTrue();
        }

        @Test
        @DisplayName("但 FAILED → FAILED 不算 —— 自迁移一律拒绝，逃生舱不能漏出这个空洞")
        void failedToItselfIsRejected() {
            assertThat(FAILED.canTransitionTo(FAILED)).isFalse();
        }

        @Test
        @DisplayName("FAILED 可以由用户重试回到 IDLE")
        void failedCanRetry() {
            assertThat(FAILED.transitionTo(IDLE)).isEqualTo(IDLE);
        }

        @Test
        @DisplayName("FAILED 不能直接跳到 THINKING —— 必须先回 IDLE")
        void failedCannotJumpToThinking() {
            assertThat(FAILED.canTransitionTo(THINKING)).isFalse();
        }
    }

    @Nested
    @DisplayName("非法迁移必须被拒绝")
    class IllegalTransitions {

        @Test
        @DisplayName("IDLE → EXECUTING_TOOL 非法：没有正在跑的 turn，哪来的工具调用")
        void idleCannotExecuteTool() {
            assertThatThrownBy(() -> IDLE.transitionTo(EXECUTING_TOOL))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("IDLE")
                    .hasMessageContaining("EXECUTING_TOOL");
        }

        @Test
        @DisplayName("THINKING → IDLE 非法：一轮没结束不能直接空闲")
        void thinkingCannotGoIdle() {
            assertThat(THINKING.canTransitionTo(IDLE)).isFalse();
        }

        @Test
        @DisplayName("异常里带着 from / to，排障时不用读堆栈猜")
        void exceptionCarriesEndpoints() {
            IllegalStateTransitionException ex = assertThrows(
                    IllegalStateTransitionException.class,
                    () -> EXECUTING_TOOL.transitionTo(IDLE));

            assertThat(ex.from()).isEqualTo(EXECUTING_TOOL);
            assertThat(ex.to()).isEqualTo(IDLE);
        }

        @Test
        @DisplayName("canTransitionTo(null) 返回 false 而不是抛 NPE")
        void nullTargetIsFalse() {
            assertThat(IDLE.canTransitionTo(null)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(SessionState.class)
        @DisplayName("任何状态都不能迁移到自己")
        void noSelfTransition(SessionState s) {
            assertThat(s.canTransitionTo(s)).isFalse();
        }
    }
}
