package com.codeloom.workspace.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 本模块的持久化装配。
 *
 * <h2>为什么由模块自己声明，而不写在 app 里</h2>
 * 「哪个包里有 mapper」是模块的内部实现细节，不该让装配根替它记着 ——
 * 少了这一句 app 启动时会报「找不到 SessionMapper 这个 bean」，
 * 而那个报错离真正的原因（某个模块的 scan 漏了）隔着一层。
 *
 * <p>这样 app 那边只需要扫描 {@code com.codeloom}，各模块的持久化就自动就位。
 *
 * <p>{@code annotationClass = Mapper.class} 是必须的：不加的话 {@code @MapperScan}
 * 会把包内**所有接口**都当成 mapper 注册，包括 {@code SessionRepository} 这类领域端口。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(value = "com.codeloom.workspace.persistence", annotationClass = Mapper.class)
public class WorkspacePersistenceConfig {
}
