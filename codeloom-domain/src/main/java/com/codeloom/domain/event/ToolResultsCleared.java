package com.codeloom.domain.event;

import java.util.List;

/**
 * 把若干旧工具结果的正文清掉，只留一个占位符。
 *
 * <p>它和 {@link ContextCompacted} 不同：那个要调模型生成摘要，这个不调模型，只表示"那个调用有
 * 结果，内容不再展示"。代价是彻底丢细节，好处是免费，所以排在完整压缩之前，用来推迟那次
 * 花钱的调用。它同样破坏前缀缓存，只在划算时做（见 {@code ContextCompactor} 的两档阈值）。
 *
 * <p>必须落事件而不能就地改写：投影必须是事件流的纯函数，就地改会让不同时刻、不同实例投影出
 * 不同的历史。见
 * {@code docs/decisions/architecture/2026-10-03-compaction-clears-via-an-event}。
 *
 * @param callIds 被清掉正文的调用 id；不记原文（留着等于没清），原文仍可从事件流查
 * @throws IllegalArgumentException callIds 为空（等于什么都没做）
 */
public record ToolResultsCleared(List<String> callIds) implements PersistentEvent {

    public ToolResultsCleared {
        callIds = List.copyOf(callIds == null ? List.of() : callIds);
        if (callIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "清空一批结果等于「什么都不做」，不该落一条事件 —— 那只会白占一行");
        }
    }
}
