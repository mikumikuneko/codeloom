package com.codeloom.agent.context;

import com.codeloom.domain.event.SessionRewound;
import com.codeloom.domain.event.StoredEvent;

/**
 * 投影里**一份跟着事件走的派生事实** —— 消息之外，投影还要记住的那几样东西。
 *
 * <h2>为什么各自一件事，而不是投影里的一堆字段</h2>
 * 散着写的时候有两个写入点：折每条事件时更新、推倒重来时清空。**两处必须一一对应，
 * 而漏掉不会报错** —— 只会留下一段被退掉的时间的痕迹。收成单元之后，
 * "它长什么样"和"它怎么跟着回滚"住在一起，两个写入点都只是遍历一遍。
 *
 * <h2>实现方的义务</h2>
 * <ul>
 *   <li>{@link #accept} —— **每条事件都要能吃**，不产出消息的那些（状态迁移、用量）也要。
 *       同一条只会喂一遍（投影按 seq 过滤），所以无条件追加是安全的。
 *   <li>**回滚自己消化**：{@code SessionRewound} 像别的事件一样喂进来，一份事实该不该
 *       跟着退、退到哪儿由实现自己定 —— 它比外面更清楚自己存的是什么。
 *   <li>{@link #clear()} —— **只为推倒重来存在**：那时候整条流会重新喂一遍，
 *       所以重建之前先把实现退回起点，省得它还要自己去想"再喂一遍会不会变成另一样"。
 *       这一条**从外面测不出来** —— 今天那几份事实对"同一批事件再喂一遍"的产出恰好相同
 *       （追加的那些挤在末尾、随后一起被砍掉；覆盖的那些还是覆盖）。所以它由
 *       {@link ContextAssembler.Projection} 里那一个遍历保证，而不是由某条测试保证。
 * </ul>
 */
interface Derived {

    /** 吃一条事件。 */
    void accept(StoredEvent stored);

    /** 回到"一条都还没处理"的样子 —— 推倒重来之前调。 */
    void clear();

    /**
     * 回滚的切点：**序号不超过它的事件还作数**。
     *
     * <p>切点为空（旧数据、没有那条字段）时取最小值 —— 也就是整段作废。这是保守的那一边：
     * 拿不准的时候宁可多丢，和投影那边"查不到边界就退到开头"是同一个判断。
     *
     * <p>它收在这里，是因为"回滚退到哪儿"是这一族事实**共用的**一条规则：
     * 几份事实各写一遍的话，某一份算错了不会报错，只会让它自己留在另一条时间线上。
     */
    static long cutOf(SessionRewound rewound) {
        return rewound.toCheckpointSeq() == null ? Long.MIN_VALUE : rewound.toCheckpointSeq();
    }
}
