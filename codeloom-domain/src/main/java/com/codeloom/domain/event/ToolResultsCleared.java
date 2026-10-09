package com.codeloom.domain.event;

import java.util.List;

/**
 * 把若干**旧工具结果的正文**清掉，只留一个占位符。
 *
 * <h2>它和 {@link ContextCompacted} 不是一回事</h2>
 * 那个要**调一次模型生成摘要**，会换来一段能代表原文的话；这个**不调模型**，
 * 换来的是"这里曾经有个结果，内容已经不看了"。代价是彻底丢细节，
 * 好处是免费 —— 所以它排在完整压缩**前面**，用来推迟那次花钱的调用。
 *
 * <h2>为什么不就地改写，而要落一条事件</h2>
 * 就地改 {@code tool_result} 的内容对我们**违规**：投影必须是
 * 事件流的纯函数，同一段历史任何时候投影出来都得逐字符一样。就地改会让
 * "刚才投影出来的"和"现在投影出来的"不同，而那种不一致在多实例下就是事故。
 *
 * <p>所以记成事件：投影时先扫出"哪些调用被清过"，再把它们的结果换成占位符。
 * 顺序、结构、配对全都不变，变的只是正文。
 *
 * <h2>代价：它也破坏前缀缓存</h2>
 * 被清的那些结果如果在缓存前缀里，这次调用就得重写。所以它**只在划算时做** ——
 * 见 {@code ContextCompactor} 里那两档阈值：清一批内容的代价，
 * 要小于换来的一次完整压缩（那次调用本身就不便宜）。
 *
 * @param callIds 被清掉正文的那些调用。**只记 id，不记原文** ——
 *                留着原文就等于没清；要回看原始内容请查事件流本身，它一直在
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
