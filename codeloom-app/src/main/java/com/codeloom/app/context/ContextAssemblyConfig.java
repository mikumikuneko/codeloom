package com.codeloom.app.context;

import com.codeloom.agent.context.ContextAssembler;
import com.codeloom.domain.port.UserRepository;
import com.codeloom.domain.user.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配器的唯一配置点：把一条会话的事件流装配成发给模型的消息。
 *
 * <p>装配器带一层“按 id 查用户名”。漏装它的调用方只会得到“协作者”，而压缩生成的摘要
 * 会顶替整段历史，此后再也分不清留言是谁提的，且不会报错。收成一个 bean 后，
 * 每个调用方不必各自回答“装配器配成什么样”。它住在 app 是因为装配器所在模块不带 Spring。
 * 见 {@code docs/decisions/architecture/2026-10-10-one-assembler-configured-in-one-place.md}。
 */
@Configuration
public class ContextAssemblyConfig {

    /**
     * 名字从用户表查，查不到给 null，由 {@code MessageFold} 回退成“协作者”。
     *
     * <p>查发生在折进去的那一刻，折好的消息不再变（前缀必须逐字节稳定）。所以改过用户名
     * 之后，模型看到的仍是旧名字；界面每次渲染都现查，显示新名字。
     *
     * @param users 用户仓储
     * @return 按用户 id 查显示名的装配器
     */
    @Bean
    public ContextAssembler contextAssembler(UserRepository users) {
        return new ContextAssembler(id -> users.findById(id).map(User::displayName).orElse(null));
    }
}
