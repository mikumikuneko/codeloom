package com.codeloom.app.files;

/**
 * 目录树里的一个条目。
 *
 * @param name      文件/目录名（只有名字，不含路径）
 * @param path      **相对会话工作区**的路径，前端拿它去请求下一层或读内容。
 *                  用正斜杠，不管宿主机是什么系统 —— 它是接口协议的一部分，不是本地路径
 * @param directory 是不是目录。前端靠它决定"点开是展开还是打开"。
 *                  **符号链接一律是 false** —— 展开它会走到工作区外面去
 * @param sizeBytes 文件大小。为 null 有三种情况：目录、符号链接、或者读不到大小。
 *                  前端显示"未知"比显示一个编出来的 0 诚实
 */
public record FileEntryView(String name, String path, boolean directory, Long sizeBytes) {

    static FileEntryView ofDirectory(String name, String path) {
        return new FileEntryView(name, path, true, null);
    }
}
