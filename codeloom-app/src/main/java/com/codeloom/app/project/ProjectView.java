package com.codeloom.app.project;

import com.codeloom.domain.user.User;

import java.util.List;

/**
 * 项目对外的视图。
 *
 * <p>刻意没有 {@code repoPath}：那是服务端文件系统路径，客户端既不能读也不能写，
 * 泄露出去只会暴露目录布局。判据是客户端拿它能做什么，而不是字段是否敏感。
 *
 * @param id      项目 id
 * @param name    项目名
 * @param ownerId 房主 id。客户端用它说明退出时会发生什么（房主还有别人时，退出后项目转给对方）；
 *                谁是房主由服务端判定，客户端不自己推
 * @param members 成员
 * @param rootName 项目目录名，界面文件树的第一行；由服务端给出，见 {@link ProjectLayout#DIRECTORY}
 */
public record ProjectView(String id, String name, String ownerId, List<Member> members,
                          String rootName) {

    /**
     * 成员视图，只暴露身份字段。
     *
     * @param id          用户 id
     * @param username    登录用的账号
     * @param displayName 界面上显示的用户名
     */
    public record Member(String id, String username, String displayName) {

        /**
         * @param user 领域用户
         * @return 成员视图
         */
        public static Member of(User user) {
            return new Member(user.id().value(), user.username(), user.displayName());
        }
    }
}
