package com.codeloom.app.session;

import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.session.Session;

/**
 * 会话对外的视图。
 *
 * <p>刻意没有 {@code worktreePath}：服务端文件系统路径对客户端没有用途，判据是客户端
 * 拿它能做什么（参见 {@link com.codeloom.app.project.ProjectView}）。
 *
 * <p>{@code branch} 与 {@code headCommit} 是工作区的属性，而工作区挂在「用户 × 项目」上，
 * 因此同一个人的几条会话会显示同一对值；这是"换会话不换代码"的直接结果。
 * {@code firstMessage} 是历史列表的标题，取用户开口的第一句；还没说过话时为空。
 *
 * @param id           会话 id
 * @param projectId    所属项目
 * @param ownerId      会话所有者
 * @param branch       工作区分支
 * @param headCommit   工作区当前 HEAD
 * @param state        会话状态
 * @param turnIndex    对话位置
 * @param model        当前模型配置
 * @param firstMessage 用户第一句话；没有时为 null
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

    /**
     * @param session      会话
     * @param workspace    会话所在工作区
     * @param firstMessage 用户第一句话，可为 null
     * @return 会话视图
     */
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
     * 模型配置。绝不含密钥，密钥不进入任何领域对象。
     *
     * <p>也没有请求地址：会话记的是"哪一家"，地址是那家的属性（见 {@code Providers}）；
     * 请求不由前端发，地址对它没有用途。
     *
     * @param provider     供应商 id
     * @param modelId      模型 id
     * @param systemPrompt 平台写入的系统提示词
     */
    public record Model(String provider, String modelId, String systemPrompt) {

        static Model of(ModelConfig config) {
            return new Model(config.provider().value(), config.modelId(), config.systemPrompt());
        }
    }
}
