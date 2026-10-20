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
 * 工具目录：一次把全部工具的声明形状交给界面。
 *
 * <p>界面渲染工具调用需要的三样（组件形状、动作词、主语参数）由工具自己声明
 * （见 {@code ToolSurface}）。界面自己按工具名维护这些表会随新增工具而过期，症状只是
 * 显示变糙，不像 bug。声明是静态的，因此不跟着事件流走、一次取全即可；写进不可变事件流
 * 会让以后修改展示变成历史迁移。
 *
 * <p>不需要额外授权：内容只有工具名和声明词，不含会话或用户数据，安全配置的
 * "其余一律要登录"已经覆盖；按项目授权需要"工具属于哪个项目"，而这不成立。
 *
 * <p>理由见 {@code docs/decisions/architecture/2026-10-06-tool-surface-is-declarative.md}。
 */
@RestController
@RequestMapping("/api/tools")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ToolsController {

    private final ToolCatalog catalog;

    public ToolsController(ToolCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @return 全部工具声明；包含联网工具，因为界面只用它决定如何渲染，
     *         某条会话里是否可用由每轮输入决定（见 {@code TurnInputFactory}）
     */
    @GetMapping
    public List<ToolView> list() {
        // 用带联网的一套：渲染声明与"这条会话能不能用"无关
        return catalog.withWeb().surfaces().entrySet().stream()
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
