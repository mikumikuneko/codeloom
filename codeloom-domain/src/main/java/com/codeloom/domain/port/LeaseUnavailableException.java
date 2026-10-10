package com.codeloom.domain.port;

/**
 * 没拿到执行权，**不是因为别人拿着，而是因为租约服务本身够不到**。
 *
 * <p>它和"没抢到"（{@link ExecutionLease#tryAcquire} 返回空）是两件事，
 * **调用方绝不能混为一谈**：没抢到说明这棵树有人管，处置是等或者排队；
 * 这个是"这把锁现在问不到"，处置是稍后重试。
 *
 * <p>而两条路都**不是**"那就先跑着"。写入有 fencing token 挡着，但
 * **工具在磁盘上的改动没有任何东西挡** —— 没有锁还照跑，等于让两个人同时改一棵工作树。
 * 所以这个异常存在的意义恰恰是**把"拒绝"和"不知道"分开**，让外面能分别给出
 * 正确的响应（前者 409、后者 503），而不是让它们都塌成一句"没跑成"。
 */
public class LeaseUnavailableException extends RuntimeException {

    public LeaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
