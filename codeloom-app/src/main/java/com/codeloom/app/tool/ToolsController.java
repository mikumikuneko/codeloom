package com.codeloom.app.tool;

import com.codeloom.agent.tool.ToolSurface;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具目录：一次把全部工具**声明的样子**交给界面。
 *
 * <h2>为什么要这么一个接口</h2>
 * 界面渲染每一条工具调用（读取 xxx、运行 xxx、一张 diff）靠三样东西：
 * 用哪个组件、动作词、主语在参数里的哪一项。这三样曾经由**界面自己按工具名**决定 ——
 * 一张词表、一张"哪些是文件工具"的集合、还有一张参数键顺序表。
 *
 * <p>那三张表的问题不是"重复"，是**它们会过期**：加一个工具时没人记得回来改，
 * 而症状只是"这条调用显示得糙"，看起来不像 bug。
 *
 * <p>现在那三样由工具自己声明（见 {@code ToolSurface}），界面从这里取一次。
 * 声明是静态的 —— 同一个工具的任何一次调用都一样 —— 所以它**不跟着事件流走**，
 * 一次拿全即可。这是刻意的：把这些展示信息写进不可变的事件流，将来想改展示就得迁移历史。
 *
 * <h2>它不需要额外授权</h2>
 * 内容不含任何会话或用户数据（就是七个名字和六个词），而且安全配置里
 * "其余一律要登录"那条已经管住它了。要按项目授权的话，得先有"哪个工具属于哪个项目"
 * 这回事 —— 而那不存在。
 */
@RestController
@RequestMapping("/api/tools")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ToolsController {

    private final ToolCatalog catalog;

    public ToolsController(ToolCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ToolView> list() {
        return catalog.registry().surfaces().entrySet().stream()
                .map(ToolsController::view)
                .toList();
    }

    private static ToolView view(Map.Entry<String, ToolSurface> entry) {
        ToolSurface surface = entry.getValue();
        return new ToolView(
                entry.getKey(),
                surface.shape().name().toLowerCase(Locale.ROOT),
                surface.label(),
                surface.subjectKey(),
                surface.subjectIsPath());
    }
}
