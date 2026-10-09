package com.codeloom.domain.project;

import java.util.UUID;

/** 项目标识。项目 = 一个 git 仓库，是会话的容器。 */
public record ProjectId(String value) {

    public ProjectId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("project id 不能为空");
        }
    }

    public static ProjectId of(String value) {
        return new ProjectId(value);
    }

    public static ProjectId generate() {
        return new ProjectId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
