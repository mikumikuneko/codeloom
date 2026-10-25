package com.codeloom.agent.tool;

/**
 * 按字符数截断文本。
 *
 * <p>不能直接 {@code substring}：上限正好落在一个代理对中间时，截出来的是半个字符，
 * 序列化之后会变成 U+FFFD。抓取和检索共用这一份。
 */
final class TextLimits {

    private TextLimits() {
    }

    /**
     * @param text  原文
     * @param limit 字符上限
     * @return 不超过 limit 个字符的前缀；不会切开代理对
     */
    static String head(String text, int limit) {
        int end = Math.min(limit, text.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
