package com.codeloom.domain.port;

/**
 * 没拿到执行权，不是因为别人拿着，而是因为租约服务本身不可达。
 *
 * <p>它和「没抢到」（{@link ExecutionLease#tryAcquire} 返回空）是两件事，绝不能混：
 * 没抢到说明这棵树有人管，处置是等或排队；这个说明锁的状态查询不到，处置是稍后
 * 重试。两条路都不是「那就先跑着」：写入有 fencing token 校验，但工具在磁盘上的改动
 * 没有任何保护。这个异常存在的意义就是把「拒绝」（409）和「不知道」（503）分开。
 * 见 {@code docs/decisions/architecture/2026-10-10-lease-failures-are-not-turns-that-never-ran.md}。
 */
public class LeaseUnavailableException extends RuntimeException {

    /**
     * @param message 失败说明
     * @param cause   底层失败
     */
    public LeaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
