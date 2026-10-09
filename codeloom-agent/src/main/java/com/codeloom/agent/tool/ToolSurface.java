package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * 一个工具**这类调用**长什么样 —— 由工具自己声明，不让消费端按工具名去猜。
 *
 * <h2>它解决的是"同一个事实被猜了三遍"</h2>
 * "这次调用的主语是哪一项参数"本来只有一个答案，却一度在三个地方各写了一遍：
 * <ul>
 *   <li>{@code RunCommandTool} 取那行命令去问审批；</li>
 *   <li>{@code AgentTurn.commandOf} 在"批准之后补跑"那条路上再取一遍；</li>
 *   <li>前端显示时也取过一遍（它那张按工具名的顺序表已经撤掉了）。</li>
 * </ul>
 * 三份副本只要有一处走样，症状就是**人批准的命令和实际执行的不是同一条** ——
 * 那是安全边界上的走样，不是显示问题。
 *
 * <h2>为什么是"声明"而不是"函数"</h2>
 * deepseek-harness 让工具提供一个**函数**去算"这次调用长什么样"，由 UI 在两个时机
 * 调用（实时流、会话日志回放），并且要求它是纯函数。
 * 那个形状对得上它的形态：UI 和工具跑在同一个 JS 进程里，拿得到工具对象。
 * <b>我们不行</b>：界面是另一个进程、另一种语言，跑不了 Java 的方法。
 *
 * <p>剩给我们的两条路是把解析好的呈现内容**随每次调用下发**，或者只下发**怎么解析**的声明。
 * 选了后者，理由是前者会把界面烤进不可变的事件流 —— 将来想改一下展示，
 * 得去迁移历史。而声明是静态的：同一个工具的任何一次调用都一样，**送一次就够**
 * （见 {@code ToolRegistry.surfaces()}），不必跟着每次调用抄进 {@code ToolCallRequested}
 * —— 同一场事件流里出现两份真相这条，写在 {@code ToolApprovalRequested} 的注释里。
 *
 * <h2>形状是闭集，词是工具自己的</h2>
 * {@link Shape} 刻意**粗**：它是"客户端该用哪个组件"的开关，不是工具名换一种写法。
 * 两种搜索（找文件、搜内容）共用一个形状 —— 它们要的是同一张卡片，
 * 差别只在那个词上，而词由 {@link #label()} 给。这样以后新增一个工具，
 * 只要它的形状是已有的，客户端**一行都不用改**。
 *
 * @param shape      客户端该拿哪个组件来渲染这次调用
 * @param label      这个工具的动作词（读取 / 新建 / 修改 / 运行 / 查找 / 搜索）。
 *                   界面直接显示它 —— 省掉前端一张按工具名查词的表
 * @param subjectKey 参数里哪一项是这次调用的"主语"（{@code path} / {@code command} /
 *                   {@code pattern}）；没有就是 null。界面显示它，后端拿它做审批与补跑
 */
public record ToolSurface(Shape shape, String label, String subjectKey) {

    /**
     * 这次调用该用什么形状渲染。
     *
     * <p>刻意「少而粗」：多一个值，客户端就多一个分支；而分支只有确实需要不同组件时才值得有。
     * 比如"新建文件"和"改动文件"共用 {@link #EDIT} —— 它们都要一个 diff 卡片，
     * 区别只是那个词的（新建 / 修改），而那由 {@link #label()} 表达。
     */
    public enum Shape {
        /** 读文件：显示路径，可以点开。 */
        READ,
        /** 写入或改动文件：要一张 diff 卡片。 */
        EDIT,
        /** 搜索：显示被搜的那个模式。 */
        SEARCH,
        /** 跑命令：显示命令本身和退出码。 */
        EXECUTE,
        /** 不碰工作区、只产出结构化的事实（目前只有任务清单）。 */
        PLAN,
        /** 没声明形状的兜底：显示工具名和原始参数。 */
        OTHER
    }

    /**
     * 什么形状都没声明的样子。
     *
     * <p>兜底是**能用**而不是**好看**：界面显示工具名和原始参数，人还看得懂，
     * 只是不如声明过的那个体面。新工具忘了声明，症状是"这条看起来糙"，
     * 而不是"这条不显示"。
     */
    public static final ToolSurface PLAIN = new ToolSurface(Shape.OTHER, null, null);

    public ToolSurface {
        if (label != null && label.isBlank()) {
            label = null;
        }
        if (subjectKey != null && subjectKey.isBlank()) {
            subjectKey = null;
        }
    }

    /** 声明了主语键的形状，才有主语的"种类"可言。 */
    public static ToolSurface of(Shape shape, String label, String subjectKey) {
        return new ToolSurface(shape, label, subjectKey);
    }

    /**
     * 主语是不是一个工作区里的路径。
     *
     * <p>这条规则**由形状推出来**，不另设一个字段：读和改的主语必然是路径，
     * 搜索的主语是被搜的模式、执行的主语是命令 —— 再说一遍只会多一个可能对不上的地方。
     * 界面拿它决定"这个主语能不能点开"。
     */
    public boolean subjectIsPath() {
        return subjectKey != null && (shape == Shape.READ || shape == Shape.EDIT);
    }

    /**
     * 从这次调用的参数里取出主语。
     *
     * <p>取不到就是空：参数里没这一项、或它不是字符串、或它是空白。**不抛异常** ——
     * 参数合不合法由 {@code ArgumentValidator} 在更早一步判，这里只做"有没有"。
     * 它会被审批路径调用，而在那条路上抛异常等于把一次"要不要问人"变成一次崩溃。
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
