package com.codeloom.app.web;

/**
 * 列表接口的分页上限与默认值。
 *
 * <h2>为什么是共用的</h2>
 * 三个列表接口（项目、会话、聊天记录）本来各写了一份 {@code MAX_PAGE = 200} 和
 * {@code defaultValue = "50"}。那种写法的坏处不是"多打几个字"：给某一个接口调上限时，
 * 另两个不动，于是**同一个 {@code ?limit=} 在不同的端点上得到不同结果** ——
 * 而调用方看不出自己踩的是哪一个。
 *
 * <p>它拦的是**"没上限"**，不是"翻页体验"：正常一屏远用不到 200。
 */
public final class PageLimits {

    /** 一页最多多少条。 */
    public static final int MAX = 200;

    /**
     * 不传 {@code limit} 时给多少。
     *
     * <p>它是**字符串**常量：{@code @RequestParam(defaultValue = …)} 只收常量表达式，
     * 而那个参数是 String。
     */
    public static final String DEFAULT_PARAM = "50";

    private PageLimits() {
    }
}
