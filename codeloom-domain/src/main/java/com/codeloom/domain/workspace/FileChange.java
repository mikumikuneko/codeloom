package com.codeloom.domain.workspace;

/**
 * 一个文件**一步之内**改了多少行。
 *
 * <p>粒度是"这一步"，不是"这个文件从开天辟地到现在"。所以它回答的是
 * "刚才那一下动了什么"，而那个问题在翻旧账时才有意义。
 *
 * @param path    相对工作区根目录的路径
 * @param added   新增行数。二进制文件是 0，见 {@link #binary}
 * @param deleted 删除行数。同上
 * @param binary  二进制文件 —— git 对它给不出行数，只能告诉你"它变了"。
 *                <p>{@code added} 和 {@code deleted} 都是 0 的**文本**文件才是"没改"，
 *                这两件事必须分得开，所以它单列一个字段而不是拿 0 兼作标记。
 *                <p>补一句：0/0 也**可能是整文件替换**（比如换行符统一）——
 *                git 数不出行数上的差别，但它确实改了。所以想判"这次没动它"时，
 *                别只看增删是不是 0
 * @param created **这一步是新建了这个文件**，不是改了一个已有的。
 *
 *                <h2>为什么要分出来</h2>
 *                因为"新建一个文件"和"改一个已有文件"是两种不同强度的信号：
 *                前者几乎总意味着"我要做一块新东西"（新建 {@code AuthService.java}），
 *                而后者可能只是修个 typo。协作提醒（"对方也在做这块"）只报前者，
 *                报后者会把它淹成噪声。
 *
 *                <p>**不能拿行数去猜**（比如"只增不减"）：往一个空文件里写内容
 *                和从头建一个文件，在 numstat 里长得一模一样。
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

    /** 改动已有的文件（不是新建）。绝大多数调用点要的是这个。 */
    public FileChange(String path, int added, int deleted, boolean binary) {
        this(path, added, deleted, binary, false);
    }
}
