package com.codeloom.app.tool;

/**
 * 一个工具声明的呈现形状，给界面用。
 *
 * <p>这不是给模型看的定义（{@code ToolDefinition} 的名字、说明、参数 schema）；界面需要的
 * 是渲染形状、动作词和主语参数。声明由工具自己给出，界面因此不需要认识具体工具名，
 * 新增工具时也不会因为漏改前端而悄悄退化显示。
 *
 * <p>null 字段在响应里缺席（全局配置
 * {@code spring.jackson.default-property-inclusion: non_null}），所以"没有主语"表现为
 * {@code subjectKey} 这一项不存在，而不是值为 null；前端必须按可选字段读取，
 * 不能只判断 {@code === null}。
 *
 * @param name          工具名，界面用它和事件里的 {@code toolName} 对上
 * @param shape         渲染形状：{@code read}、{@code edit}、{@code search}、{@code execute}、
 *                      {@code plan}、{@code web} 或 {@code other}。闭集，界面按它选组件，
 *                      新增工具只要形状已有，界面不用改
 * @param label         动作词；未声明时在响应里缺席
 * @param subjectKey    参数中作为主语的那一项；没有主语时缺席
 * @param subjectIsPath 主语是否为工作区路径，决定界面能否点开；基本类型，永远在场
 */
public record ToolView(String name,
                       String shape,
                       String label,
                       String subjectKey,
                       boolean subjectIsPath) {
}
