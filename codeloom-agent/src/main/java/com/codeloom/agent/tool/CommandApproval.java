package com.codeloom.agent.tool;

import java.util.Optional;

/**
 * 判断一行命令是否需要人工批准，并返回需要批准的理由。
 *
 * <p>空表示不需要问；有值表示需要问，且该值会进入审批事件、显示在确认界面上。
 * 返回布尔会让用户在不知道原因的情况下点同意。没有理由就不该问：那说明判据没想清楚。
 */
@FunctionalInterface
public interface CommandApproval {

    /**
     * @param commandLine 模型写的命令行；null 表示这次调用没有命令参数，不是“一条需要审批的命令”
     * @return 空 = 不用问；有值 = 需要问，且该值就是理由
     */
    Optional<String> reasonToAsk(String commandLine);
}
