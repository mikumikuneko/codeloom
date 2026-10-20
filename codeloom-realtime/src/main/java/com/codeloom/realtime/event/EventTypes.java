package com.codeloom.realtime.event;

import com.codeloom.domain.event.EventType;

/**
 * 事件类型名解析，{@code event.type} 列与事件信封的判别字段共用这一实现。
 *
 * <p>类型名是持久化格式的一部分：解析不出来只有两种可能，有人手工改过那一列，或者
 * {@link EventType} 的常量被改了名。后者对 append-only 日志是破坏性操作，历史行会整体
 * 读不出来，所以类型名一旦发布就不能改。
 */
public final class EventTypes {

    /**
     * @param raw   类型名
     * @param where 出错时标明读取位置（例如“event 表里”“事件信封里”）；两处同时坏和
     *              只有一处坏，排查方向不同
     * @return 对应的事件类型
     * @throws EventCodecException 类型名未知
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
