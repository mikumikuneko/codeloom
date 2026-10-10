package com.codeloom.app.web;

import com.codeloom.domain.port.LeaseUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一处状态码：**租约服务够不到是 503，不是 500**。
 *
 * <p>钉它是因为这两个码会把人带去完全不同的方向：500 是"我们坏了，去查缺陷"，
 * 503 是"稍后再来"。落到 500 时，客户端连退避重试都不会做。
 */
class ApiExceptionHandlerTest {

    @Test
    @DisplayName("租约服务连不上 → 503")
    void leaseUnavailableIsServiceUnavailable() {
        ProblemDetail problem = new ApiExceptionHandler().onLeaseUnavailable(
                new LeaseUnavailableException("执行租约服务（Redis）连不上 —— 这一轮没法开始。稍后重试",
                        new RuntimeException("boom")));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getDetail()).contains("Redis");
    }
}
