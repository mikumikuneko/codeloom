package com.codeloom.app.web;

import com.codeloom.app.support.AbstractPersistenceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /actuator/info} 里的常驻投影信息。
 *
 * <p>逻辑很少，需要钉住的是键名以及默认上限是否真的绑定：这些数字是调整
 * {@code codeloom.live-projections} 时唯一的依据，报错或为空就没有依据。
 */
class LiveProjectionsInfoTest extends AbstractPersistenceTest {

    @Autowired
    private LiveProjectionsInfo info;

    @Test
    @DisplayName("报出常驻条数、大致字节，以及两个上限（默认 128 条 / 32MB）")
    void reportsTheLiveTable() {
        Info.Builder builder = new Info.Builder();

        info.contribute(builder);

        Object detail = builder.build().getDetails().get("liveProjections");
        assertThat(detail).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) detail;
        assertThat(block)
                .containsKeys("count", "approximateBytes", "maxCount", "maxBytes")
                .containsEntry("maxCount", 128)
                .containsEntry("maxBytes", 32L * 1024 * 1024);
    }
}
