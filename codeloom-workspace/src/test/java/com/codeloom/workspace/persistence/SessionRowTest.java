package com.codeloom.workspace.persistence;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.SessionState;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行与领域对象的转换。
 *
 * <p>转换错误是逻辑问题，和 SQL 是否正确相互独立，因此不跑数据库就能测、也应该这样测。
 * 分支、worktree 路径和 HEAD 属于工作区，其往返见 {@link WorkspaceRowTest}。
 */
class SessionRowTest {

    private static final SessionId SESSION_ID = SessionId.of("11111111-1111-1111-1111-111111111111");
    private static final ProjectId PROJECT_ID = ProjectId.of("22222222-2222-2222-2222-222222222222");
    private static final UserId OWNER_ID = UserId.of("33333333-3333-3333-3333-333333333333");

    private static final ModelConfig MODEL = new ModelConfig(
            ProviderId.of("deepseek"), "deepseek-chat", "你是协作开发助手。");

    @Test
    @DisplayName("往返转换不丢字段：每个组件都原样回来")
    void roundTripPreservesEveryField() {
        Session original = Session.create(SESSION_ID, PROJECT_ID, OWNER_ID, MODEL)
                .withState(SessionState.THINKING)
                .nextTurn();

        assertThat(SessionRow.of(original).toDomain()).isEqualTo(original);
    }

    @Test
    @DisplayName("可空字段真的可空：system_prompt 为空时也往返得了")
    void nullableFieldsSurviveTheRoundTrip() {
        // systemPrompt 为 null 表示不加系统提示词，这是真实状态，
        // 也最容易在映射里被误写成空串
        ModelConfig noSystemPrompt = new ModelConfig(
                ProviderId.of("kimi"), "some-model", null);
        Session original = Session.create(SESSION_ID, PROJECT_ID, OWNER_ID, noSystemPrompt);

        SessionRow row = SessionRow.of(original);
        assertThat(row.systemPrompt()).isNull();
        assertThat(row.toDomain()).isEqualTo(original);
    }

    @Test
    @DisplayName("状态以枚举名落库，读回来还原成枚举而不是字符串")
    void stateIsStoredAsEnumName() {
        Session session = Session.create(SESSION_ID, PROJECT_ID, OWNER_ID, MODEL)
                .withState(SessionState.THINKING);

        SessionRow row = SessionRow.of(session);

        assertThat(row.state()).isEqualTo("THINKING");
        assertThat(row.toDomain().state()).isEqualTo(SessionState.THINKING);
    }

    @Test
    @DisplayName("模型配置被摊平成 3 列，读回来重新组装成 ModelConfig")
    void modelConfigIsFlattenedIntoColumns() {
        SessionRow row = SessionRow.of(Session.create(SESSION_ID, PROJECT_ID, OWNER_ID, MODEL));

        assertThat(row.provider()).isEqualTo("deepseek");
        assertThat(row.modelId()).isEqualTo("deepseek-chat");
        assertThat(row.systemPrompt()).isEqualTo("你是协作开发助手。");
        assertThat(row.toDomain().model()).isEqualTo(MODEL);
    }

    @Test
    @DisplayName("工作区键是从「谁 + 哪个项目」推出来的，不走存储")
    void workspaceIdIsDerivedFromOwnerAndProject() {
        Session session = Session.create(SESSION_ID, PROJECT_ID, OWNER_ID, MODEL);

        assertThat(session.workspaceId().ownerId()).isEqualTo(OWNER_ID);
        assertThat(session.workspaceId().projectId()).isEqualTo(PROJECT_ID);
    }
}
