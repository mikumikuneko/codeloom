package com.codeloom.app.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * app 模块的持久化装配。
 *
 * <p>独立声明而不是并在启动类上，让 {@code CodeloomApplication} 只说从哪里开始扫；
 * 新增 mapper 包不需要改启动类。另外两个模块同理，见 {@code WorkspacePersistenceConfig}
 * 与 {@code RealtimePersistenceConfig}。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(value = "com.codeloom.app.persistence", annotationClass = Mapper.class)
public class AppPersistenceConfig {
}
