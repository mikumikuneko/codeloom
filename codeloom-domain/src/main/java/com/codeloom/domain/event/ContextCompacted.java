package com.codeloom.domain.event;

/**
 * 一次上下文压缩：{@code seq <= droppedUpToSeq} 的那些事件不再进入上下文，
 * 由 {@code summary} 代表它们。
 *
 * <h2>为什么记成「一条事件」而不是「删掉一些事件」</h2>
 * 事件表是 append-only 的，删不了也不该删 —— 它们是**发生过的事实**，审计、
 * 回放、断线补齐全靠它。压缩改的只是**投影**：同一份事件流，从某个序号往前不再
 * 送给模型。所以这里记的是"从哪儿开始不看"，而不是"丢了哪些"。
 *
 * <h2>它是「只追加、不改前缀」那条约定的合法例外</h2>
 * 模型服务商的 prompt 缓存是前缀匹配的，所以投影要尽量只往尾部追加 —— 这条性质是
 * 缓存便宜的全部来源。压缩**必然**破坏它（前缀整个换了）。因此压缩必须罕见：
 * 它换来的好处（不被上下文上限卡死）要大于它每次让缓存失效的代价。
 *
 * <p>{@link SessionRewound} 是另一个同类例外。除了这两条，别再往里加第三条。
 *
 * <h2>摘要不属于"被压掉的那部分"</h2>
 * 它是**一条新的事实**，排在事件流的尾部（和别的追加没什么两样）。所以后一次压缩
 * 会把前一次的摘要也一起总结进去 —— 那正是我们要的（不然压过几次之后摘要就丢了）。
 *
 * @param droppedUpToSeq 被压缩覆盖到的最后一个事件序号（含）。投影时跳过
 *                       {@code seq <= droppedUpToSeq} 的全部事件
 * @param summary        代表那一段的摘要正文，会作为一条**普通 user 消息**注入
 *
 *                       <p>它是**模型写的**，不是服务商截出来的：由平台专门发起一次调用
 *                       拿回来（怎么做见压缩那一层的注释）。所以它带着写它的那个模型的口气 ——
 *                       它是**转述**，不是原文。
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
