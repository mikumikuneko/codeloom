package com.codeloom.app.files;

/**
 * 一个文件的内容。
 *
 * @param path      相对会话工作区的路径
 * @param content   文本内容。**二进制文件时是空串**，看 {@code binary}
 * @param truncated 内容被上限截断过。截断点是**最后一个换行**，不是字节位置
 * @param binary    是二进制文件（在开头一段里发现了 NUL 字节）。此时 {@code content} 为空 ——
 *                  它不是错误，前端该显示"这是个二进制文件，看不了"，而不是弹一个失败
 * @param sizeBytes 文件在磁盘上的真实大小。**和 {@code content} 的长度无关** ——
 *                  截断过、或者是二进制时，两者差得很远，而前端多半想显示的是这个
 */
public record FileContentView(String path, String content, boolean truncated,
                              boolean binary, long sizeBytes) {
}
