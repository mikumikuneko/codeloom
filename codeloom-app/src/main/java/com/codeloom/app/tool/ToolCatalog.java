package com.codeloom.app.tool;

import com.codeloom.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

/**
 * 进程里唯一的工具集。
 *
 * <p>两套工具都在启动时构造一次：{@link ToolRegistry} 构造时逐个编译 schema 并核对
 * "工具声明的主语真的在参数里"，这些是代码错误，应当启动即失败，而不是等第一条消息发出后
 * 才炸。界面也从这里取同一份声明，不必按工具名自己维护一张表。
 *
 * <p>共享是安全的：工具实现无状态，{@link ToolRegistry} 只持有不可变映射；每轮变化的状态
 * （读过哪些文件）在 {@code ReadLedger} 里，每轮一份。
 */
@Component
public class ToolCatalog {

    private final ToolRegistry registry = ToolRegistry.standard();
    private final ToolRegistry withWeb = ToolRegistry.withWeb();

    /** @return 不含联网工具的注册表 */
    public ToolRegistry registry() {
        return registry;
    }

    /**
     * 带联网工具的那一套。用哪一套由每轮输入决定（见 {@code TurnInputFactory}）：
     * 没有联网能力的会话不该在工具列表里看到它们，否则模型会调用并拿到"没有联网能力"的失败。
     *
     * @return 含联网工具的注册表
     */
    public ToolRegistry withWeb() {
        return withWeb;
    }
}
