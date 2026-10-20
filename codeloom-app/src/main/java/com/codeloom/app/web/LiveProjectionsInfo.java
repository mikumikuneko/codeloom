package com.codeloom.app.web;

import com.codeloom.app.turn.SessionProjections;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把常驻会话投影的规模放进 {@code /actuator/info}。
 *
 * <p>投影表按进程跑过一轮的会话数增长，越界前不可见，压测也压不出来（假模型对话太短）；
 * {@code codeloom.live-projections} 及字节上限要靠这个数来调整。
 *
 * <p>只报条数和大致字节，不含会话 id 和内容：该端点放行，任何人都能问。
 */
@Component
public class LiveProjectionsInfo implements InfoContributor {

    private final SessionProjections projections;

    public LiveProjectionsInfo(SessionProjections projections) {
        this.projections = projections;
    }

    /**
     * @param builder actuator info 构建器
     */
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
