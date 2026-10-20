package com.codeloom.domain.port;

import com.codeloom.domain.workspace.WorkspaceId;

/**
 * 写入被拒绝：执行租约已经过期，这棵工作区已被别的执行者接管。
 *
 * <p>不要重试、不要吞掉：遇到它说明当前线程是僵尸写入者（GC 停顿或网络分区让它不知
 * 道自己已失去执行权），正确反应是立刻中止本轮，把控制权交还新持有者；这是 fencing
 * token 正常工作时的表现。报的是工作区而不是会话，因为失效的是树上的锁：报会话会让
 * 人以为「别的实例在跑我这一条会话」，而真相可能是同一人的另一条会话拿到了这棵树。
 * 见 {@code docs/decisions/architecture/2026-09-24-lease-and-fencing-token.md}。
 */
public class StaleLeaseException extends RuntimeException {

    /**
     * @param workspaceId    被接管的树
     * @param presentedToken 对方拿出来的、已经失效的号；它会拼进消息
     */
    public StaleLeaseException(WorkspaceId workspaceId, long presentedToken) {
        super("工作区 " + workspaceId + " 的写入被拒绝：持有的 fencing token " + presentedToken
                + " 已失效，这棵工作区已被别的执行者接管");
    }
}
