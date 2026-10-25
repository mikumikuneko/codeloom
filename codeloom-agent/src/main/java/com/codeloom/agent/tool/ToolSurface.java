package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * 一个工具这类调用在界面上长什么样，由工具自己声明，消费端不按工具名猜测。
 *
 * <p>“主语是哪项参数”只应有一个来源：审批、补跑和界面都读这份声明，避免被批准的命令
 * 与实际执行的命令不一致。声明是静态数据，不随每次调用下发，也不进入不可变事件流。
 *
 * @param shape      客户端用来渲染这次调用的形状
 * @param label      动作词（读取、新建、修改、运行、查找、搜索、抓取）
 * @param subjectKey 主语参数键（如 {@code path}、{@code command}、{@code pattern}）；没有则为 null
 */
public record ToolSurface(Shape shape, String label, String subjectKey) {

    /**
     * 渲染形状。刻意少而粗，只在确实需要不同组件时新增；动作差异由 {@link #label()} 表达。
     */
    public enum Shape {
        /** 读文件：显示路径，可以点开。 */
        READ,
        /** 写入或改动文件：要一张 diff 卡片。 */
        EDIT,
        /** 搜索：显示被搜的那个模式。 */
        SEARCH,
        /** 抓取网址：显示 URL 和正文。不能复用 {@link #READ}，否则界面会把 URL 当成工作区路径。 */
        WEB,
        /** 跑命令：显示命令本身和退出码。 */
        EXECUTE,
        /** 不碰工作区、只产出结构化的领域事实（见 {@code ToolOutcome#todoUpdate}）。 */
        PLAN,
        /** 没声明形状的兜底：显示工具名和原始参数。 */
        OTHER
    }

    /** 未声明形状时的兜底：显示工具名和原始参数，仍然可用。 */
    public static final ToolSurface PLAIN = new ToolSurface(Shape.OTHER, null, null);

    public ToolSurface {
        if (label != null && label.isBlank()) {
            label = null;
        }
        if (subjectKey != null && subjectKey.isBlank()) {
            subjectKey = null;
        }
    }

    /**
     * 构造呈现声明。
     *
     * @param shape      渲染形状
     * @param label      动作词
     * @param subjectKey 主语参数键
     * @return 呈现声明
     */
    public static ToolSurface of(Shape shape, String label, String subjectKey) {
        return new ToolSurface(shape, label, subjectKey);
    }

    /**
     * 主语是否是工作区内的路径，由形状推出；界面据此决定主语能否点开。
     *
     * @return READ 或 EDIT 且声明了主语时为 true
     */
    public boolean subjectIsPath() {
        return subjectKey != null && (shape == Shape.READ || shape == Shape.EDIT);
    }

    /**
     * 从调用参数中取主语；缺失、非字符串或空白时为空。不抛异常，因为审批路径会调用它。
     *
     * @param arguments 调用参数
     * @return 主语文本
     */
    public Optional<String> subjectIn(JsonNode arguments) {
        if (subjectKey == null || arguments == null) {
            return Optional.empty();
        }
        JsonNode node = arguments.path(subjectKey);
        return node.isTextual() && !node.asText().isBlank()
                ? Optional.of(node.asText())
                : Optional.empty();
    }
}
