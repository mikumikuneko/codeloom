package com.codeloom.app.web;

import com.codeloom.app.support.AbstractPersistenceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /actuator/info} 里那块"常驻投影"。
 *
 * <p>它自己没什么逻辑，值得钉的是**形状与那几个数**：键名、以及默认的两个上限有没有
 * 真的绑上去 —— 那几个数正是调 {@code codeloom.live-projections} 时唯一的凭据，
 * 报错了或者报了个空，调的时候就没有依据。
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
