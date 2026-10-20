package com.codeloom.domain.event;

/**
 * 一次上下文压缩：{@code seq <= droppedUpToSeq} 的事件不再进入上下文，由 {@code summary}
 * 代表它们。
 *
 * <p>记成事件而不是删事件：事件表 append-only，且投影必须是事件流的纯函数；压缩只改投影，记的是
 * "从哪儿开始不看"，不是"丢了哪些"。见
 * {@code docs/decisions/architecture/2026-10-03-compaction-clears-via-an-event}。
 *
 * <p>它和 {@link SessionRewound} 是"只追加、不改前缀"的两条合法例外。投影只往尾部追加是 prompt
 * 缓存便宜的全部来源，而压缩必然换掉前缀，因此必须罕见，不要再加第三条。见
 * {@code docs/decisions/bug-fix/2026-09-30-context-window-comes-from-measurement}。
 *
 * <p>摘要是一条新事实，排在事件流尾部；后一次压缩会把前一次的摘要一起总结进去 —— 这正是要的。
 *
 * @param droppedUpToSeq 被压缩覆盖到的最后一个事件序号（含）；投影跳过
 *                       {@code seq <= droppedUpToSeq} 的全部事件
 * @param summary        代表那一段的摘要，会作为普通 user 消息注入。它是平台专门调用模型生成
 *                       的转述，不是服务商截出来的原文
 * @throws IllegalArgumentException 水位线小于 1，或摘要为空
 */
public record ContextCompacted(long droppedUpToSeq, String summary) implements PersistentEvent {

    public ContextCompacted {
        if (droppedUpToSeq < 1) {
            throw new IllegalArgumentException(
                    "压缩水位线必须是 1 开始的事件序号（事件 seq 从 1 起），收到：" + droppedUpToSeq);
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException(
                    "压缩必须留下摘要 —— 没有摘要的话，被跳过的那段对话对模型就是彻底消失了");
        }
    }
}
