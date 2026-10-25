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

    /** 把调用方给的 {@code limit} 夹到 1 到 {@link #MAX} 之间。 */
    public static int limit(int requested) {
        return Math.clamp(requested, 1, MAX);
    }

    /** 负的 offset 当 0：仓储不校验，传下去会变成非法 SQL 偏移。 */
    public static int offset(int requested) {
        return Math.max(0, requested);
    }
}
