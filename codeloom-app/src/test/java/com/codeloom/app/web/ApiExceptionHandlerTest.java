package com.codeloom.app.web;

import com.codeloom.domain.port.LeaseUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租约服务不可达必须是 503 而不是 500：500 表示"我们坏了、去查缺陷"，
 * 503 表示"稍后再来"；落到 500 时客户端连退避重试都不会做。
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
