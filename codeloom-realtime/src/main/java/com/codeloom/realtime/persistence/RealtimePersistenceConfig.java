package com.codeloom.realtime.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 本模块的持久化装配。和 {@code WorkspacePersistenceConfig} 同理：
 * 「哪个包里有 mapper」是模块的内部实现细节，由模块自己声明，
 * 装配根只需要扫描 {@code com.codeloom}。
 *
 * <p>{@code annotationClass = Mapper.class} 不能省 —— 不加的话本包的
 * {@code ChatMessageInsert} 之类倒无所谓，但会把包内所有接口都当 mapper 注册。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(value = "com.codeloom.realtime.persistence", annotationClass = Mapper.class)
public class RealtimePersistenceConfig {
}
