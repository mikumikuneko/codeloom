package com.codeloom.app.tool;

import com.codeloom.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

/**
 * 进程里**唯一**那一份工具集。
 *
 * <h2>为什么要有这么一个东西</h2>
 * 每轮输入如果用现造的工具集（每轮七个工具对象、七份 schema 编译），有两个后果：
 *
 * <ol>
 *   <li><b>启动时的检查没人在启动时跑。</b>{@link ToolRegistry} 构造时会逐个编译 schema、
 *       并核对"工具声明的主语真的在它的参数里"，那些都是**写代码时的错**，
 *       本该在启动时就炸。现造的话，第一次真的炸是在第一条消息发出去的时候 ——
 *       那时你已经坐在界面前等结果了。</li>
 *   <li><b>界面要拿到同一份声明。</b>工具长什么样由工具自己声明（见 {@code ToolSurface}），
 *       而界面是另一个进程。它得从某个地方取到那份声明 —— 那个地方就是这里，
 *       而不是让界面自己按工具名再写一张表。</li>
 * </ol>
 *
 * <p>共享它是安全的：工具实现全是无状态的（它们只读参数、只写 {@code ToolContext}
 * 里交进来的东西），而 {@link ToolRegistry} 自己只持有不可变的映射。
 * 每轮会变的那些（读过哪些文件）在 {@code ReadLedger} 里，那是每轮一份的。
 */
@Component
public class ToolCatalog {

    private final ToolRegistry registry = ToolRegistry.standard();

    public ToolRegistry registry() {
        return registry;
    }
}
