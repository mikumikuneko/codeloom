package com.codeloom.realtime.event;

import com.codeloom.domain.event.EventType;

/**
 * 事件类型名的解析 —— {@code event.type} 列与事件信封的判别字段共用这一个实现（{@code where} 用来区分是哪一处读到的）。
 *
 * <p>类型名是**持久化格式的一部分**：解析不出来只有两种可能 —— 有人手工改过那一列，
 * 或者枚举被改了名。后者对 append-only 的日志是**破坏性操作**：改一下 {@link EventType}
 * 里某个常量的名字，历史行就会整体读不出来（而它们本来是要永久可读的）。所以
 * {@link EventType} 的注释里写着"名字一旦发布就不能改"。
 */
public final class EventTypes {

    /**
     * @param where 出错时告诉读的人**是在哪儿读到的**（"event 表里" / "事件信封里"）——
     *              这两个地方同时坏和只有一处坏，排查方向完全不同
     */
    public static EventType parse(String raw, String where) {
        try {
            return EventType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new EventCodecException(where + "出现了未知的事件类型：" + raw, e);
        }
    }

    private EventTypes() {
    }
}
