package com.codeloom.app.session;

import com.codeloom.domain.event.CheckpointCreated;

/**
 * 一个可回滚的点。
 *
 * <p>两个字段缺一不可，而且它们对应的是**同一个位置的两面**：
 * {@code commitSha} 是代码在哪，{@code turnIndex} 是对话在哪。
 * 回滚的时候两者要一起退 —— 只退一个是这个设计里最容易犯、也最难发现的错。
 */
public record CheckpointView(String commitSha, int turnIndex) {

    public static CheckpointView of(CheckpointCreated event) {
        return new CheckpointView(event.commitSha(), event.turnIndex());
    }
}
