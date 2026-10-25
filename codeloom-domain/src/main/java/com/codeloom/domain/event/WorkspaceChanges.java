package com.codeloom.domain.event;

import com.codeloom.domain.workspace.FileChange;

import java.util.List;

/**
 * 这一轮改了哪些文件、各增删多少行 —— 写时算一次，之后它一直是"当时的事实"。
 *
 * <p>不在读时问 git：那样代价随会话变老增长，每个读者各算一遍（整条会话 O(n²)），还会因 git
 * 对象被回收而永远算不出来；更根本的是会让 git 成为第二个事实来源。见
 * {@code docs/decisions/architecture/2026-09-24-one-event-log-as-the-source-of-truth}。
 *
 * <p>用 {@code git diff SHA^ SHA} 取改动，而不是数文件工具调用：格式化器、代码生成、
 * {@code sed -i} 和编译产物也会改文件，只数文件工具会把"spotless 改了十二个文件"报成没改。
 *
 * <p>不进上下文：模型知道自己做过什么；它只在造出新提交且确实有改动时才写，进去会让前缀缓存
 * 时不时失效。
 *
 * <p>算不出来时不写这条事件（git 挂了、工作区没了）：这一轮没有记录，而不是记一条
 * "什么都没改"。改动太多时照写，但只记前一批并把 {@code truncated} 置真。代价是界面上
 * "没有记录"与"真的没改动"看起来一样。
 *
 * @param commitSha 这一轮提交出来的位置；事后复核"当时改了什么"从它入手
 * @param files     改动的文件，项目相对路径（git 路径已映射，见 {@code ProjectLayout}），
 *                  按路径排序
 * @param truncated 改动太多、只记下了前一批；界面要如实说"没记全"，不能只报那个偏小的数字
 * @param turnIndex 第几轮，和同一收尾里的 {@link CheckpointCreated} 同号。界面靠它把改动挂到
 *                  正确的轮次；界面自己数会错位。可为空（后加字段），且不能默认成 0 —— 0 是
 *                  合法轮次号（见
 *                  {@code docs/decisions/architecture/2026-10-04-nullable-only-when-zero-is-a-lie}，
 *                  以及 {@code docs/decisions/bug-fix/2026-10-10-frontend-stops-counting-turns-itself}）
 */
public record WorkspaceChanges(String commitSha, List<FileChange> files, boolean truncated,
                               Integer turnIndex)
        implements PersistentEvent {

    /** 复制文件列表；{@code null} 视为空表。 */
    public WorkspaceChanges {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
