package com.codeloom.app.web;

/**
 * 列表接口共用的分页上限与默认值。各接口分别维护会让同一个 {@code ?limit=} 在不同端点
 * 得到不同结果，而调用方看不出差异。上限用于防止无上限请求，不是为翻页体验：正常一屏远用
 * 不到 200。
 */
public final class PageLimits {

    /** 一页最多多少条。 */
    public static final int MAX = 200;

    /**
     * 不传 {@code limit} 时的默认值。必须是字符串常量：{@code @RequestParam(defaultValue = ...)}
     * 只接受常量表达式，而参数类型是 String。
     */
    public static final String DEFAULT_PARAM = "50";

    private PageLimits() {
    }
}
