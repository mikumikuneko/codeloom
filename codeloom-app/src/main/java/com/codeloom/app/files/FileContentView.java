package com.codeloom.app.files;

/**
 * 一个文件的内容。
 *
 * @param path      相对项目根的路径
 * @param content   文本内容；二进制文件时为空串，见 {@code binary}
 * @param truncated 内容被上限截断过；截断点在最后一个换行，不是字节位置
 * @param binary    是二进制文件（开头一段里发现了 NUL 字节）；此时 {@code content} 为空。
 *                  这不是错误，前端应显示“看不了”，而不是报失败
 * @param sizeBytes 文件在磁盘上的真实大小，与 {@code content} 的长度无关
 */
public record FileContentView(String path, String content, boolean truncated,
                              boolean binary, long sizeBytes) {
}
