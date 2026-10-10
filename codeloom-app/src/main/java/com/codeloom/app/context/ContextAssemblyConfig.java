package com.codeloom.app.context;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 「把一条会话的事件流装配成发给模型的消息」那一个 —— **配置在这里定死**。
 *
 * <h2>为什么它是一个 bean，而不是谁用谁自己造一个</h2>
 * 装配器上有一层配置：**按 id 查用户名**。装上它的那份，别人捎来的留言会写明是谁说的；
 * 没装的那份只会说"协作者"。从前有没有这层由两个调用方各自决定，而其中一处（压缩）
 * **漏了** —— 它装出来的那份输入会被写进摘要、**顶替整段历史**，于是从那以后模型
 * 再也分不清那条留言是谁提的。这件事不会报错，也没有任何地方会提醒。
 *
 * <p>收成一个 bean 之后，"装配器配成什么样"就不再是一个每个调用方都要重新回答一遍的问题。
 * 它住在 app 而不是装配器自己那个模块里，是因为那边不带 Spring。
 */
@Configuration
public class ContextAssemblyConfig {

    /**
     * 名字从用户表查。**查不到给 null**，由 {@code MessageFold} 回退成"协作者" ——
     * 宁可说"协作者"，也不编一个名字。
     *
     * <p>查发生在**折进去的那一刻**，折好的消息不再变（前缀必须逐字节稳定）。
     * 所以改过用户名之后，模型看到的仍是折进去时的那个名字；而**界面**每次渲染都现查、
     * 显示的是新名字。
     */
    @Bean
    public ContextAssembler contextAssembler(UserRepository users) {
        return new ContextAssembler(id -> users.findById(id).map(User::displayName).orElse(null));
    }
}
