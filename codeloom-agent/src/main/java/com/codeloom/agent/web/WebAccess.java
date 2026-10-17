package com.codeloom.agent.web;

import java.util.List;

/**
 * 联网取资料：搜网页、抓一个页面。
 *
 * <h2>为什么是端口，而不是在工具里直接调某一家</h2>
 * 这两个能力目前只有 DeepSeek 提供（它的 {@code /v1/tools/search} 与 {@code /v1/tools/fetch}），
 * 但工具不该知道那家是谁：换一家有同类能力的、或者以后由我们自己代理一层，
 * 工具一个字都不用改。带密钥、拼地址、认错误码全在实现里 —— 而实现住在 app，
 * 那里才够得到按用户存的密钥。
 *
 * <h2>失败用异常，不由返回值表达</h2>
 * 失败要连同"为什么"一起交给模型：频率超限、余额不足、抓不到正文是三件不同的事，
 * 而工具会把它们翻成一次可以自修的工具结果。用异常是因为一次调用里可能失败在好几处
 * （拼请求、网络、解析），每处都回一个 {@code Optional} 只会让原因在传递中丢掉。
 */
public interface WebAccess {

    /**
     * 搜一次网页。
     *
     * @param query 搜索词
     * @param count 要几条；实现方按自己的上限再夹一遍
     * @return 结果，按相关度排好序。**没有命中时是空列表，不是异常** ——
     *         "什么都没搜到"是一次正常的回答
     */
    List<Hit> search(String query, int count) throws WebAccessException;

    /** 抓一个页面，正文是 Markdown。 */
    Page fetch(String url) throws WebAccessException;

    /**
     * 一条命中。**只有短摘要，没有长摘要**：搜索结果回答的是"去哪儿看"，
     * 不是"看到了什么" —— 长摘要会把模型喂到不需要读原文（见 {@code WebSearchTool}）。
     *
     * @param snippet 短摘要，100～200 字符；实现方拿不到时为 null
     * @param date    发布时间，RFC 3339；未知时为 null
     */
    record Hit(String title, String url, String snippet, String siteName, String date) {
    }

    record Page(String title, String content) {
    }

    /**
     * 这条会话没有联网能力。
     *
     * <p>工具**不该**被交给这样的会话（见 {@code ToolCatalog}：没有联网能力的会话拿不到
     * 这两个工具）。真被调到了就是接线错了，所以这里直接失败 —— 而不是安静地返回空结果，
     * 那会让模型以为"搜了但没搜到"，然后换个说法再搜一次。
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
