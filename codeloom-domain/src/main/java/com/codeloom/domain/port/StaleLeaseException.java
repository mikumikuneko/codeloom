package com.codeloom.domain.port;

import com.codeloom.domain.workspace.WorkspaceId;

/**
 * 写入被拒绝：执行租约已经过期，这棵工作区已被别的执行者接管。
 *
 * <p>遇到它**不要重试、不要吞掉** —— 它意味着当前线程是一个"僵尸写入者"
 * （GC 停顿或网络分区导致它不知道自己已经失去执行权）。正确反应是立刻中止本轮执行，
 * 把控制权交还给新持有者。
 *
 * <p>这是 fencing token 机制正常工作时的表现，不是异常情况。
 *
 * <h2>为什么报的是工作区而不是会话</h2>
 * 因为失效的是**树上的那把锁**：报会话会让人以为"别的实例正在跑我这一条会话"，
 * 而真相可能是"同一个人的另一条会话拿到了这棵树" —— 处置方式完全不同
 * （前者等，后者得先看清那棵树现在被谁改到哪儿了）。
 */
public class StaleLeaseException extends RuntimeException {

    private final transient WorkspaceId workspaceId;

    /** @param presentedToken 对方拿出来的那个号（它已经失效了）。**拼进消息里** —— 只开这一个口子 */
    public StaleLeaseException(WorkspaceId workspaceId, long presentedToken) {
        super("工作区 " + workspaceId + " 的写入被拒绝：持有的 fencing token " + presentedToken
                + " 已失效，这棵工作区已被别的执行者接管");
        this.workspaceId = workspaceId;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }
}
