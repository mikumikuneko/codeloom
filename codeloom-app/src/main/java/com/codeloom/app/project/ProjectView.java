package com.codeloom.app.project;

import com.codeloom.domain.user.User;

import java.util.List;

/**
 * 项目对外长什么样。
 *
 * <h2>刻意没有 {@code repoPath}</h2>
 * 那是服务端的文件系统路径。客户端拿着它没用（它既不能读也不能写），
 * 而漏出去等于把服务器的目录布局告诉外面 —— 一条没有任何收益的信息泄露。
 * 判断标准不是"这个字段敏感吗"，而是"客户端拿它能做什么"：什么都做不了，就别给。
 *
 * @param ownerId  为什么要给：客户端要用它说清**退出时会发生什么** —— 你是房主且还有别人
 *                 的话，退出之后项目会转给对方。判断在服务端（谁是房主是领域的事），
 *                 客户端只是把它说出来；让它自己推一遍就是第二条真相
 * @param rootName 项目目录叫什么 —— 界面左边那棵树的第一行就是它。名字得从服务端来：
 *                 文件接口给的是**项目里**的路径，而树上要有一个"这是项目"的根节点，
 *                 客户端自己写一个 {@code "untitled"} 会在目录改名那天对不上。
 *                 见 {@link ProjectLayout#DIRECTORY}
 */
public record ProjectView(String id, String name, String ownerId, List<Member> members,
                          String rootName) {

    /** 成员视图：只给身份，不给密码哈希那类内部字段（领域对象里本来也没有）。 */
    public record Member(String id, String username, String displayName) {

        public static Member of(User user) {
            return new Member(user.id().value(), user.username(), user.displayName());
        }
    }
}
