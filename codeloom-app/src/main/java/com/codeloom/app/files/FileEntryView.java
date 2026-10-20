package com.codeloom.app.files;

/**
 * 目录树里的一个条目。
 *
 * @param name      文件或目录名，不含路径
 * @param path      相对项目根、用正斜杠的路径，前端用它请求下一层或读内容。
 *                  它是接口协议的一部分，不是本地路径
 * @param directory 是不是目录，前端据此决定点开是展开还是打开。
 *                  符号链接一律为 false —— 展开它会走到工作区外面
 * @param sizeBytes 文件大小；目录、符号链接或读不到时为 null。显示“未知”比编一个 0 诚实
 */
public record FileEntryView(String name, String path, boolean directory, Long sizeBytes) {

    static FileEntryView ofDirectory(String name, String path) {
        return new FileEntryView(name, path, true, null);
    }
}
