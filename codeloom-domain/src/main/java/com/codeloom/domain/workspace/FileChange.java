package com.codeloom.domain.workspace;

/**
 * 一个文件在一步之内改了多少行。粒度是"这一步"，不是这个文件的全部历史。
 *
 * @param path    路径。{@code changesIntroducedBy} 给的是仓库根相对（如
 *                {@code untitled/a.txt}）；落进
 *                {@link com.codeloom.domain.event.WorkspaceChanges} 前由
 *                {@code ProjectLayout.toProjectPath} 转成项目相对（{@code a.txt}）
 * @param added   新增行数。二进制文件是 0，见 {@link #binary}
 * @param deleted 删除行数。同上
 * @param binary  二进制文件——git 给不出行数，只能告诉你它变了；{@code added} 与
 *                {@code deleted} 都是 0 的文本文件才是"没改"，所以它单列一个字段而
 *                不是拿 0 兼作标记。0/0 也可能是整文件替换（如换行符统一），所以判断
 *                "这次没动它"时不能只看增删是不是 0
 * @param created 这一步新建了文件，而不是修改已有文件。协作提醒只报新建：新建几乎总意味着
 *                "要做一块新东西"，而修改可能只是修 typo，全报会成噪声；也不能靠行数
 *                猜——往空文件里写内容和新建文件在 numstat 里一样。见
 *                {@code docs/decisions/feature/2026-10-02-collaboration-notice-for-new-files.md}
 */
public record FileChange(String path, int added, int deleted, boolean binary, boolean created) {

    public FileChange {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        if (added < 0 || deleted < 0) {
            throw new IllegalArgumentException("增删行数不能为负，收到 " + added + "/" + deleted);
        }
    }

    /**
     * 改动已有的文件（不是新建）。绝大多数调用点要的是这个。
     *
     * @param path    路径
     * @param added   新增行数
     * @param deleted 删除行数
     * @param binary  是否为二进制文件
     */
    public FileChange(String path, int added, int deleted, boolean binary) {
        this(path, added, deleted, binary, false);
    }
}
