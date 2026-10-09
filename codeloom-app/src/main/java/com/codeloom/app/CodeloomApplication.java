package com.codeloom.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 装配根。整个系统唯一带 {@code main} 的类。
 *
 * <p>{@code scanBasePackages} 必须显式写 {@code "com.codeloom"}：本类位于
 * {@code com.codeloom.app}，默认只会扫描它下面的包，那样 {@code codeloom-agent}、
 * {@code codeloom-workspace}、{@code codeloom-realtime} 里的组件全都不会被注册。
 */
@SpringBootApplication(scanBasePackages = "com.codeloom")
public class CodeloomApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeloomApplication.class, args);
    }
}
