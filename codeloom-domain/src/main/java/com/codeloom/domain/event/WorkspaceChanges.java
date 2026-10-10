package com.codeloom.domain.event;

import com.codeloom.domain.workspace.FileChange;

import java.util.List;

/**
 * 这一轮改了哪些文件、各增删多少行 —— **在写的时候算一次**，之后它一直是"当时的事实"。
 *
 * <h2>为什么在写的时候算，而不是读的时候</h2>
 * 这件事一度是**读的时候**算的：每打开一次回滚界面、每跑完一轮，都去问 git
 * "这个提交引入了什么"。三个后果：
 *
 * <ol>
 *   <li><b>代价随会话变老一直涨，而且每个读的人各算一遍。</b> 前台那条流每收尾一轮就要一次，
 *       于是整条会话下来是 O(n²) —— 第 100 轮那次比第 10 轮那次贵十倍。</li>
 *   <li><b>它依赖 git 还找得到那些对象。</b> 提交被 gc 掉、树被回收之后，
 *       那一轮改了什么**就永远问不出来了**（而且表现是整条读路径报错，不只是少一行）。</li>
 *   <li>最要紧的是：它让 git 变成了**第二个事实来源**。这个项目从头到尾写的是
 *       "事件流是唯一的事实来源"，而"这一轮改了什么"明明是当时发生的事，
 *       该在发生的那一刻记下来 —— 记下来之后它就不再依赖任何会变的东西。</li>
 * </ol>
 *
 * <p>我们这边更省事 —— 每轮本来就提交一次，所以 {@code git diff <sha>^ <sha>}
 * 就是这一轮引入的东西，不用另外拍快照。
 *
 * <h2>为什么问 git，而不是数模型调了几次写文件</h2>
 * 因为**命令也会改文件**：格式化器、代码生成、{@code sed -i}、编译产物。
 * 只数文件工具的话，一轮里 agent 跑了 {@code mvn spotless:apply} 改了十二个文件，
 * 界面上会说"这一轮没改什么" —— 而那是一句**不完整**的话，比不说更糟。
 *
 * <h2>它不进上下文</h2>
 * 模型不需要知道"我刚才改了哪几个文件"（它自己做过什么它清楚），而这条事件**每一轮都会出现**
 * —— 一旦进上下文，每轮的消息前缀都不一样，prompt 缓存就此作废。
 *
 * <h2>算不出来的时候：不写这条事件</h2>
 * git 挂了、工作区不在了、改动多到爆 —— 那些情况下**这一轮就是没有记录**，
 * 而不是记一条"什么都没改"（那是在编）。代价说清楚：**"没有记录"和"真的没改动"
 * 在界面上长得一样**（都是"没有代码改动"）。这一条写在这里，免得下一个人以为它是精确的。
 *
 * @param commitSha 这一轮提交出来的位置 —— 事后要复核"当时到底改了什么"时，从它入手
 * @param files     改动的文件，**项目相对路径**（git 说的仓库路径在这里已经翻过一道，
 *                  见 {@code ProjectLayout}）。按路径排序，顺序稳定
 * @param truncated 改动太多、只记下了前一批。界面要如实说"没记全"，
 *                  而不是只报那个偏小的数字 —— 那是在编
 * @param turnIndex **这是第几轮**，和 {@code CheckpointCreated} 记的是同一个号
 *                  （同一个收尾里算出来的那一个）。界面拿它把"这一轮改了什么"挂到对的位置上 ——
 *                  从前它自己数"第几条用户消息"，而两套数法一旦错开，改动就会挂到
 *                  **别人那一轮**头上，看起来完全像真的。
 *
 *                  <p>**可以为空**：加这个字段之前落的事件没有它。用包装类型而不是
 *                  {@code int}，是因为 **0 是一个合法的轮次号** —— 缺字段读成 0 会指向
 *                  第一轮，那比"不知道"坏得多（见那条"只在 0 是个谎时才可空"的规矩）。
 */
public record WorkspaceChanges(String commitSha, List<FileChange> files, boolean truncated,
                               Integer turnIndex)
        implements PersistentEvent {

    /** 没截断（绝大多数情况）。 */
    public WorkspaceChanges(String commitSha, List<FileChange> files) {
        this(commitSha, files, false, null);
    }

    public WorkspaceChanges {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
