package com.codeloom.agent.loop;

/**
 * 服务商报告“这次装不下”之后，修正持久状态并允许重发。
 *
 * <p>阈值压缩是预防，这里是兜底：估算偏小时 provider 会直接拒绝。原样重发没有意义，
 * 只有模型可见状态真的改变后才值得再发。返回值表示“修动了吗”，不是“成功了吗”；
 * 实现会写事件流，因此需要存储，这也是它被注入的原因。
 */
@FunctionalInterface
public interface ContextRepair {

    /**
     * 上下文装不下时修正持久状态（例如压缩并落一条 {@code ContextCompacted}），然后由循环重发。
     *
     * @return 模型可见的状态是否真的改变；false 时循环抛出原始错误
     */
    boolean afterContextOverflow();
}
