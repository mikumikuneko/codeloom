package com.codeloom.app.session;

import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;

/**
 * 会话对外长什么样。
 *
 * <h2>刻意没有 {@code worktreePath}</h2>
 * 和 {@link com.codeloom.app.project.ProjectView} 同理：那是服务端的文件系统路径，
 * 客户端拿它什么都做不了。判断标准是"客户端拿它能做什么"，不是"它敏感吗"。
 *
 * <h2>{@code branch} 和 {@code headCommit} 是**工作区**的属性</h2>
 * 它们出现在这里，是因为界面上要显示"这条会话的代码现在在哪个 commit"，而回滚也是按它
 * 来选的。但归属要说清楚：这两个值来自工作区，而工作区挂在「用户 × 项目」上 ——
 * 所以**同一个人的几条会话会显示同一对值**。那不是 bug，是"换会话不换代码"的直接表现。
 *
 * <h2>{@code firstMessage} 是"这是哪一条会话"</h2>
 * 历史列表拿它当每一行的标题：同一栏里列的是同一个人的会话，"谁在说"区分不了它们，
 * 而**用户开口的第一句**正是人自己记得住的那个开头。还没人说过话时为空。
 */
public record SessionView(String id,
                          String projectId,
                          String ownerId,
                          String branch,
                          String headCommit,
                          String state,
                          int turnIndex,
                          Model model,
                          String firstMessage) {

    public static SessionView of(Session session, Workspace workspace, String firstMessage) {
        return new SessionView(
                session.id().value(),
                session.projectId().value(),
                session.ownerId().value(),
                workspace.branch(),
                workspace.headCommit(),
                session.state().name(),
                session.turnIndex(),
                Model.of(session.model()),
                firstMessage);
    }

    /**
     * 模型配置。**里面没有、也永远不该有密钥**——密钥不进入任何领域对象。
     *
     * <p>也没有请求地址：会话记的是"哪一家"，地址是那一家的属性（见 {@code Providers}）。
     * 前端拿地址没用（请求不由它发），而露出来只会让人以为那是可以改的。
     */
    public record Model(String provider, String modelId, String systemPrompt) {

        static Model of(ModelConfig config) {
            return new Model(config.provider().value(), config.modelId(), config.systemPrompt());
        }
    }
}
