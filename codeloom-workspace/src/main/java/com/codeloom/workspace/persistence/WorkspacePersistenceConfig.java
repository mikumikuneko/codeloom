package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 本模块的持久化装配：扫描本包中带 {@link Mapper} 的接口。
 *
 * <p>由模块自己声明，app 只需扫描 {@code com.codeloom}，模块的内部包结构不必让装配根
 * 记住。{@code annotationClass} 必须指定，否则包内所有接口都会被注册成 mapper。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(value = "com.codeloom.workspace.persistence", annotationClass = Mapper.class)
public class WorkspacePersistenceConfig {
}
