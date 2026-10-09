package com.codeloom.app.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * app 模块自己的持久化装配。
 *
 * <p>它在装配根里，但仍然独立声明而不是并在启动类上：这样 {@code CodeloomApplication}
 * 保持「只说从哪里开始扫」这一件事，加一个 mapper 包不需要去改启动类。
 * 另外两个模块同理，见 {@code WorkspacePersistenceConfig} / {@code RealtimePersistenceConfig}。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(value = "com.codeloom.app.persistence", annotationClass = Mapper.class)
public class AppPersistenceConfig {
}
