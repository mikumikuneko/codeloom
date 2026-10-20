package com.codeloom.workspace.persistence;

import com.codeloom.domain.llm.ProviderId;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.session.SessionState;
import com.codeloom.domain.user.UserId;

/**
 * {@code session} 表的一行。
 *
 * <p>领域记录不携带列名和数据库形状（枚举、嵌套的模型配置），所以需要这个只负责搬行的
 * 类型。它不含分支、worktree 路径和 HEAD，那些属于 {@link WorkspaceRow}；也不含
 * {@code fencing_token}，那是并发控制的落点。列清单里没有它，写入时漏掉是看得见的，
 * 而一旦带上就会把号写回旧值。
 */
public record SessionRow(String id,
                         String projectId,
                         String ownerId,
                         String state,
                         int turnIndex,
                         String provider,
                         String modelId,
                         String systemPrompt) {

    /**
     * 查询用的列清单，所有读语句复用它。
     *
     * <p>不用 {@code SELECT *}，避免表加列悄悄改变结果集；每个下划线列显式起别名到
     * record 组件名，因此映射不依赖 {@code map-underscore-to-camel-case} 配置。
     * 列顺序与 record 组件顺序一致，作为别名之外的第二重保证。
     */
    static final String COLUMNS = """
            id, project_id AS projectId, owner_id AS ownerId, state,
            turn_index AS turnIndex, provider, model_id AS modelId,
            system_prompt AS systemPrompt
            """;

    /** 领域对象 → 行；模型配置在这里摊平成 3 列。 */
    static SessionRow of(Session session) {
        ModelConfig model = session.model();
        return new SessionRow(
                session.id().value(),
                session.projectId().value(),
                session.ownerId().value(),
                session.state().name(),
                session.turnIndex(),
                model.provider().value(),
                model.modelId(),
                model.systemPrompt());
    }

    /**
     * 行 → 领域对象。
     *
     * <p>不做防御性校验：不变量由 {@link Session} 的构造器守住，这里重复只会多一处需要
     * 同步的代码。
     *
     * @return 领域会话
     * @throws IllegalArgumentException 行里的值不满足领域不变量
     */
    Session toDomain() {
        return new Session(
                SessionId.of(id),
                ProjectId.of(projectId),
                UserId.of(ownerId),
                SessionState.valueOf(state),
                turnIndex,
                new ModelConfig(ProviderId.of(provider), modelId, systemPrompt));
    }
}
