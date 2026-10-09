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
 * <h2>为什么要有这个类型，而不是直接把 Session 当实体</h2>
 * 领域记录不该知道列名、不该有「没有值的列」（{@code SessionState} 是枚举不是字符串、
 * {@code ModelConfig} 是嵌套对象不是 5 个平铺字段）。硬把数据库形状塞进领域类型，
 * 就等于让存储设计反向决定领域模型 —— 那是六边形特意要避免的方向。
 *
 * <p>代价是这个类本身。它有意做得很笨：只有字段和两个方向的转换，没有任何逻辑。
 *
 * <h2>为什么它没有分支、worktree 路径、HEAD，也没有 fencingToken</h2>
 * 前三样是**工作区**的属性（{@link WorkspaceRow}），后一样是并发控制的落点、
 * 也归工作区 —— 因为锁本身锁的就是一棵树（见 {@code WorkspaceFence}）。
 *
 * <p>本类一个都不带，而且这是有意的：一旦这里也带上 {@code fencing_token}，
 * 某次 {@code save()} 就会顺手把它写回旧值、把 fence 抹掉。列清单里没有它，
 * 漏写是看得见的。
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
     * 查询用的列清单。所有读语句都复用它，有两个原因：
     *
     * <ol>
     *   <li>不用 {@code SELECT *}：表加列会悄悄改变结果集，而且这正是阿里手册禁掉的写法；
     *   <li>每个下划线列都显式起别名到 record 的组件名，于是映射**不依赖**
     *       {@code map-underscore-to-camel-case} 这个配置项 —— 那玩意配错了不报错，
     *       只会静默错位，是最难查的一类问题。别名写死在 SQL 里，读代码时就能看出
     *       「哪一列进哪个字段」。
     * </ol>
     *
     * <p>列的先后顺序与 record 组件顺序一致。这样即使 MyBatis 退回到按位置匹配构造函数，
     * 结果也仍然正确 —— 别名和顺序是双保险，不是二选一。
     */
    static final String COLUMNS = """
            id, project_id AS projectId, owner_id AS ownerId, state,
            turn_index AS turnIndex, provider, model_id AS modelId,
            system_prompt AS systemPrompt
            """;

    /** 领域对象 → 行。模型配置在这里被摊平成 3 列。 */
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
     * <p>这里没有任何防御性校验，是故意的：{@code Session} 的紧凑构造器已经把所有不变量
     * 都守住了，这里再抄一遍只会多一处需要同步的地方。真读到脏数据就让它在构造器里炸，
     * 炸得越早越好。
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
