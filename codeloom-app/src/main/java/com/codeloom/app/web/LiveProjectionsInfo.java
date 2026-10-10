package com.codeloom.app.web;

import com.codeloom.app.turn.SessionProjections;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把"常驻了多少条会话投影"放进 {@code /actuator/info}。
 *
 * <h2>为什么这件事要有个读点</h2>
 * 那张表按"这个进程跑过一轮的会话数"增长，而它在**越界之前是看不见的** ——
 * 压测也压不出来（假模型的对话太短）。{@code codeloom.live-projections} 和它那条字节上限
 * 要凭一个数来调，这个数就是它。
 *
 * <p>报的只有条数与大致字节，**没有会话 id、没有内容** —— 那是个放行的端点，谁都能问。
 */
@Component
public class LiveProjectionsInfo implements InfoContributor {

    private final SessionProjections projections;

    public LiveProjectionsInfo(SessionProjections projections) {
        this.projections = projections;
    }

    @Override
    public void contribute(Info.Builder builder) {
        SessionProjections.LiveReport report = projections.liveReport();
        builder.withDetail("liveProjections", Map.of(
                "count", report.count(),
                "approximateBytes", report.approximateBytes(),
                "maxCount", report.maxCount(),
                "maxBytes", report.maxBytes()));
    }
}
