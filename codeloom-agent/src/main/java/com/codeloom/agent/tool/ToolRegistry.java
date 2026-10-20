package com.codeloom.agent.tool;

import com.codeloom.agent.llm.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具注册表：名字到实现的映射，以及给模型看的定义列表。
 *
 * <p>构造时建立 {@link ToolDefinition}、编译每个 schema 并核对工具声明的主语，
 * 让命名、schema 和声明错误在启动时暴露。
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools;
    private final Map<String, ArgumentValidator> validators;
    private final List<ToolDefinition> definitions;
    private final Map<String, ToolSurface> surfaces;

    public ToolRegistry(List<Tool> tools) {
        Objects.requireNonNull(tools, "tools");
        Map<String, Tool> byName = new LinkedHashMap<>();
        Map<String, ArgumentValidator> compiled = new LinkedHashMap<>();
        Map<String, ToolSurface> declared = new LinkedHashMap<>();
        for (Tool tool : tools) {
            Tool existing = byName.put(tool.name(), tool);
            if (existing != null) {
                throw new IllegalArgumentException("工具名重复: " + tool.name());
            }
            // 启动时编译 schema；写坏属于代码错误。
            ArgumentValidator validator = ArgumentValidator.of(tool.parametersJsonSchema());
            compiled.put(tool.name(), validator);

            // 声明的主语必须存在于 schema（见 ArgumentValidator#declares）。
            ToolSurface surface = tool.surface();
            if (surface.subjectKey() != null && !validator.declares(surface.subjectKey())) {
                throw new IllegalArgumentException("工具 " + tool.name() + " 声明的主语是参数 "
                        + surface.subjectKey() + "，但它的参数 schema 里没有这一项");
            }
            declared.put(tool.name(), surface);
        }
        this.tools = Map.copyOf(byName);
        this.validators = Map.copyOf(compiled);
        // 保留注册顺序供界面稳定显示。
        this.surfaces = Collections.unmodifiableMap(declared);
        this.definitions = tools.stream()
                .map(t -> new ToolDefinition(t.name(), t.description(), t.parametersJsonSchema()))
                .toList();
    }

    /**
     * 默认工具集，不含联网工具；联网工具按会话能力通过 {@link #withWeb()} 提供。
     *
     * @return 注册表
     */
    public static ToolRegistry standard() {
        return new ToolRegistry(standardTools());
    }

    /**
     * 默认工具集加两个联网工具，用于确实有联网能力的会话。两套工具都接受同样的启动检查。
     *
     * @return 注册表
     */
    public static ToolRegistry withWeb() {
        List<Tool> tools = new ArrayList<>(standardTools());
        tools.add(new WebSearchTool());
        tools.add(new WebFetchTool());
        return new ToolRegistry(tools);
    }

    private static List<Tool> standardTools() {
        return List.of(
                new ReadFileTool(),
                new EditFileTool(),
                new WriteFileTool(),
                new GrepTool(),
                new GlobTool(),
                new RunCommandTool(),
                // 不碰工作区，只产出一条领域事实（任务清单），由调用方落成事件。
                new TodoWriteTool());
    }

    /**
     * 工具声明的呈现（形状、动作词、主语键）；未注册时为空。
     * 与 {@link #definitions()} 不同，这是给人看的，两者都来源于 {@link Tool}。
     *
     * @param toolName 工具名
     * @return 呈现声明
     */
    public Optional<ToolSurface> surfaceOf(String toolName) {
        return Optional.ofNullable(surfaces.get(toolName));
    }

    /**
     * 全部工具的呈现声明，按注册顺序。这是交给界面的唯一来源，新工具复用已有形状时界面无需改动。
     *
     * @return 工具名到呈现声明的映射
     */
    public Map<String, ToolSurface> surfaces() {
        return surfaces;
    }

    /**
     * @param name 工具名
     * @return 已注册的工具；不存在时为空
     */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 按该工具自己的 schema 校验参数。未知工具名返回空，由调用方报告“没有这个工具”。
     *
     * @param toolName  工具名
     * @param arguments 已解析参数
     * @return 空 = 通过；有值 = 给模型看的可自修说明
     */
    public Optional<String> validateArguments(String toolName, JsonNode arguments) {
        ArgumentValidator validator = validators.get(toolName);
        return validator == null ? Optional.empty() : validator.validate(arguments);
    }

    /**
     * @return 给模型看的工具定义列表
     */
    public List<ToolDefinition> definitions() {
        return definitions;
    }

    /**
     * @return 按注册顺序排列的工具名
     */
    public List<String> names() {
        return definitions.stream().map(ToolDefinition::name).toList();
    }

}
