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
 * 【跨模块不变量】自动验证会用到的可执行文件，必须全部被**生产配置里的**白名单放行。
 *
 * <h2>这条守的是什么</h2>
 * 两个知识住在两个模块里，各自演化：
 * <ul>
 *   <li>{@link VerificationPlan#detect}（agent）决定"改完代码该跑什么"</li>
 *   <li>{@code codeloom.command-whitelist}（application.yml）决定"哪些程序不必问人"</li>
 * </ul>
 *
 * <p>前者必须是后者的子集。注意它**不是**在守"验证跑不跑得起来" ——
 * 验证由平台直接调执行器，根本不经过审批那一道，名单里有没有 {@code mvn}
 * 对它没有影响。它守的是另一半：**平台自己会跑的那些名字，也应当是不必问人的那一类**
 * ——少放行一个，模型自己想跑一次 {@code mvn} 就得先等人点一下。
 *
 * <p>所以这里把它变成一条断言：直接拿**生产那个 {@code CommandPolicy} bean**
 * （由 yml 装配出来的）去问。
 *
 * <p>它要起容器才能拿到真正的白名单，所以连着中间件时才跑。
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
