package com.codeloom.agent.web;

import java.util.List;

/**
 * 联网取资料的端口：搜网页、抓页面。工具只依赖本接口，不感知具体服务商；
 * 带密钥、拼地址和错误分类都在实现里，实现位于能取得按用户密钥的 app 模块。
 *
 * <p>失败通过异常携带原因，由工具转换成可自修的工具结果。见
 * {@code docs/decisions/feature/2026-10-17-web-search-and-fetch.md}。
 */
public interface WebAccess {

    /**
     * 搜一次网页。
     *
     * @param query 搜索词
     * @param count 请求条数；实现方按自己的上限截断
     * @return 按相关度排序的结果；没有命中时为空列表，不是异常
     * @throws WebAccessException 请求失败
     */
    List<Hit> search(String query, int count) throws WebAccessException;

    /**
     * 抓取一个页面，正文为 Markdown。
     *
     * @param url 完整网址
     * @return 标题与正文
     * @throws WebAccessException 请求失败
     */
    Page fetch(String url) throws WebAccessException;

    /**
     * 一条命中，只含短摘要；长摘要会让模型跳过原文。
     *
     * @param title    标题
     * @param url      链接
     * @param snippet  短摘要；实现方拿不到时为 null
     * @param siteName 站点名
     * @param date     发布时间，RFC 3339；未知时为 null
     */
    record Hit(String title, String url, String snippet, String siteName, String date) {
    }

    record Page(String title, String content) {
    }

    /**
     * 这条会话没有联网能力。这样的会话不应拿到联网工具；真被调用说明接线错误，
     * 因此直接失败，不能返回空结果让模型误以为搜索无命中。
     *
     * @return 总是抛异常的实现
     */
    static WebAccess none() {
        return None.INSTANCE;
    }

    final class None implements WebAccess {

        static final None INSTANCE = new None();

        private None() {
        }

        @Override
        public List<Hit> search(String query, int count) {
            throw new WebAccessException("这条会话没有联网能力");
        }

        @Override
        public Page fetch(String url) {
            throw new WebAccessException("这条会话没有联网能力");
        }
    }
}
