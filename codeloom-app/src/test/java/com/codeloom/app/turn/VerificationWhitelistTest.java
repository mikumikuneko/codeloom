package com.codeloom.app.turn;

import com.codeloom.agent.loop.VerificationPlan;
import com.codeloom.workspace.exec.CommandPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块不变量：自动验证用到的可执行文件，必须全部被生产配置里的白名单放行。
 *
 * <p>两个知识分居两个模块：{@link VerificationPlan#detect}（agent）决定改完代码跑什么，
 * {@code codeloom.command-whitelist}（application.yml）决定哪些程序不必问人；前者必须是
 * 后者的子集。它守的不是"验证能否运行" —— 验证由平台直接调用执行器，不经过审批，
 * 名单里有没有 {@code mvn} 对它没有影响；它守的是"平台自己会跑的名字也应当免审批"，
 * 少放行一个，模型主动跑一次 {@code mvn} 就得等人点批准。
 *
 * <p>因此直接拿生产装配出的 {@code CommandPolicy} bean 断言。需要启动容器取得真实白名单，
 * 所以只在中间件可达时运行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIf("com.codeloom.app.support.MiddlewareAvailability#isEverythingReachable")
class VerificationWhitelistTest {

    @Autowired
    private CommandPolicy policy;

    @Test
    @DisplayName("探测会用到的可执行文件，一个不落全在 application.yml 的白名单里")
    void everyRequiredExecutableIsWhitelisted() {
        assertThat(VerificationPlan.REQUIRED_EXECUTABLES).isNotEmpty();

        assertThat(VerificationPlan.REQUIRED_EXECUTABLES)
                .as("探测会用的工具一个都不能少 —— 否则自动验证永远跑不起来")
                .allSatisfy(executable -> assertThat(policy.isAllowed(executable))
                        .as("%s 不在白名单里（见 codeloom.command-whitelist）", executable)
                        .isTrue());
    }
}
