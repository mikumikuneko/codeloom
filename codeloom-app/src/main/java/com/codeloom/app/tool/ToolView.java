package com.codeloom.app.tool;

/**
 * 一个工具**声明**的样子，给界面用。
 *
 * <p>它不是一个工具的"定义" —— 给模型看的定义在 {@code ToolDefinition}（名字、说明、
 * 参数 schema），那三样模型要，界面不要。界面要的是另外三样：用什么形状渲染、
 * 动作词叫什么、主语在参数里的哪一项。
 *
 * <p>为什么界面不自己写一张按工具名查的表：那张表在**加第八个工具的那天不会跟着变**，
 * 而症状是"这条调用的显示退化了"，没人会想到是前端少了一行。
 * 声明由工具自己给出，界面就永远不用认识任何工具名。
 *
 * @param name          工具名。界面拿它和事件里的 {@code toolName} 对上
 * @param shape         什么形状：{@code read} / {@code edit} / {@code search} /
 *                      {@code execute} / {@code plan} / {@code other}。
 *                      界面按它选组件 —— **闭集**，所以新增工具只要形状是已有的，
 *                      界面一行都不用改
 * @param label         动作词（读取 / 新建 / 修改 / 运行 / 查找 / 搜索）。界面直接显示它。
 *                      <b>没声明时这个字段在响应里是缺席的</b>（见下）
 * @param subjectKey    参数里哪一项是这次调用的主语；<b>没有主语时缺席</b>
 * @param subjectIsPath 主语是不是一个工作区里的路径 —— 界面拿它决定"能不能点开"。
 *                      它是基本类型，所以永远在场
 *
 *                      <h2>null 的字段在响应里是缺席的</h2>
 *                      这是全局约定（{@code spring.jackson.default-property-inclusion: non_null}），
 *                      整个 API 都这样。所以"这个工具没有主语"在线上是**这一项不存在**，
 *                      而不是 {@code "subjectKey": null}。前端按"可选"读它，
 *                      别写成 {@code === null} 那种只认一种空值的判据 ——
 *                      那样写出来的判据在真实响应上永远不成立。
 */
public record ToolView(String name,
                       String shape,
                       String label,
                       String subjectKey,
                       boolean subjectIsPath) {
}
