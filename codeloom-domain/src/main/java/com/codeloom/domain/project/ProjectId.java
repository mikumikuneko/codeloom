package com.codeloom.domain.project;

import java.util.UUID;

/** 项目标识。项目 = 一个 git 仓库，是会话的容器。 */
public record ProjectId(String value) {

    public ProjectId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("project id 不能为空");
        }
    }

    /**
     * 从文本构造；空白值会被拒绝。
     *
     * @param value 项目标识文本
     * @return 项目标识
     */
    public static ProjectId of(String value) {
        return new ProjectId(value);
    }

    /**
     * 新项目标识。
     *
     * @return 随机 UUID 文本形式的项目标识
     */
    public static ProjectId generate() {
        return new ProjectId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
