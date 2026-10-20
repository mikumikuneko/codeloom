package com.codeloom.app.session;

import com.codeloom.domain.event.CheckpointCreated;

/**
 * 一个可回滚的点。
 *
 * <p>两个字段是同一位置的两面：{@code commitSha} 是代码位置，{@code turnIndex} 是对话
 * 位置。回滚时两者必须一起退，只退一个是这里最容易犯也最难发现的错误。
 *
 * @param commitSha 代码所在的提交
 * @param turnIndex 对话位置
 */
public record CheckpointView(String commitSha, int turnIndex) {

    /**
     * @param event checkpoint 事件
     * @return 可回滚点
     */
    public static CheckpointView of(CheckpointCreated event) {
        return new CheckpointView(event.commitSha(), event.turnIndex());
    }
}
