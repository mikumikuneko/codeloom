package com.codeloom.realtime.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 本模块的持久化装配。“哪个包里有 mapper”是模块内部细节，由模块自己声明，
 * 装配根只扫描 {@code com.codeloom}。
 *
 * <p>{@code annotationClass = Mapper.class} 不能省：不加会把包内所有接口都注册成 mapper。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(value = "com.codeloom.realtime.persistence", annotationClass = Mapper.class)
public class RealtimePersistenceConfig {
}
