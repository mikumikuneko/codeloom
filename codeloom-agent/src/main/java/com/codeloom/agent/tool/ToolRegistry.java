package com.codeloom.agent.tool;

import com.codeloom.agent.llm.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具的注册表：名字 → 实现，以及转成给模型看的定义列表。
 *
 * <p>构造时就把 {@link ToolDefinition} 建好、并把每个工具的 schema 编译成
 * {@link ArgumentValidator} —— 工具名不合法、schema 不是合法 JSON、属性漏写 type
 * 这类问题会在**启动时**暴露，而不是等模型第一次调用它才 400。
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
            // 顺手把 schema 编译好。放这里而不是调用时，是因为「schema 写坏了」是
            // 写代码时的错，应该在启动时就炸出来
            ArgumentValidator validator = ArgumentValidator.of(tool.parametersJsonSchema());
            compiled.put(tool.name(), validator);

            // 声明的主语必须真的在 schema 里 —— 见 ArgumentValidator#declares
            ToolSurface surface = tool.surface();
            if (surface.subjectKey() != null && !validator.declares(surface.subjectKey())) {
                throw new IllegalArgumentException("工具 " + tool.name() + " 声明的主语是参数 "
                        + surface.subjectKey() + "，但它的参数 schema 里没有这一项");
            }
            declared.put(tool.name(), surface);
        }
        this.tools = Map.copyOf(byName);
        this.validators = Map.copyOf(compiled);
        // 保留注册顺序（Map.copyOf 不保证）：这份要发给界面，顺序稳定才好读
        this.surfaces = Collections.unmodifiableMap(declared);
        this.definitions = tools.stream()
                .map(t -> new ToolDefinition(t.name(), t.description(), t.parametersJsonSchema()))
                .toList();
    }

    /** 默认工具集。 */
    public static ToolRegistry standard() {
        return new ToolRegistry(List.of(
                new ReadFileTool(),
                new EditFileTool(),
                new WriteFileTool(),
                new GrepTool(),
                new GlobTool(),
                new RunCommandTool(),
                // 它不碰工作区，产出的是**一条事实**（任务清单）——
                // 由调用方落成事件，见 ToolOutcome.todoUpdate()
                new TodoWriteTool()));
    }

    /**
     * 这个工具声明的样子（形状 / 动作词 / 主语键）。没注册过的工具是空。
     *
     * <p>它和 {@link #definitions()} 是两个方向：那个是**给模型看的**，
     * 这个是**给人看的**。两者唯一的共同来源是工具自己 —— 所以任何一边要加字段，
     * 都得先加到 {@link Tool} 上。
     */
    public Optional<ToolSurface> surfaceOf(String toolName) {
        return Optional.ofNullable(surfaces.get(toolName));
    }

    /**
     * 全部工具声明的样子，按注册顺序。
     *
     * <p>这是**唯一**一处把工具声明交给界面的地方（见 {@code /api/tools}）：
     * 界面拿它渲染每一条工具调用，于是新增一个工具只要形状是已有的，界面一行都不用改。
     */
    public Map<String, ToolSurface> surfaces() {
        return surfaces;
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 校验模型给的参数合不合这个工具声明的 schema。
     *
     * <p>工具名认不出来时返回空 —— 「没有这个工具」由调用方自己报，不在这里代劳。
     * 对模型来说那是另一个问题，合成一句话反而不好自修。
     *
     * @return 空 = 通过；有值 = 一句给模型看的、可自修的说明
     */
    public Optional<String> validateArguments(String toolName, JsonNode arguments) {
        ArgumentValidator validator = validators.get(toolName);
        return validator == null ? Optional.empty() : validator.validate(arguments);
    }

    /** 给模型看的工具定义列表。 */
    public List<ToolDefinition> definitions() {
        return definitions;
    }

    public List<String> names() {
        return definitions.stream().map(ToolDefinition::name).toList();
    }

}
