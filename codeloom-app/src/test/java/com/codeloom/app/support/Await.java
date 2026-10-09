package com.codeloom.app.support;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 轮询到条件成立为止 —— 用来取代测试里的 {@code Thread.sleep}。
 *
 * <h2>固定睡一觉错在哪</h2>
 * 它把"等一个异步状态变化"写成了一个**猜测**：睡短了，慢机器上假失败；睡长了，
 * 快机器上白等。而这两种症状都只会在别人的机器或 CI 上出现 —— 本机永远是对的，
 * 所以是最难发现的那一类问题。
 *
 * <p>轮询两边都对：条件一成立就往下走，超时则如实报出**等的是哪个条件**
 * （而不是丢一句 "assertion failed"，让人回去猜）。
 */
public final class Await {

    /**
     * 默认超时。
     *
     * <p>给得比"正常情况下需要的时间"宽得多：这些等待通常几十毫秒就成立了，
     * 十秒是留给"机器正忙"的余量。超时**不代表慢**，代表条件真的没成立。
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final long POLL_MILLIS = 20;

    /** 等到条件成立。 */
    public static void until(String what, BooleanSupplier condition) {
        until(what, condition, DEFAULT_TIMEOUT);
    }

    public static void until(String what, BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("等了 " + timeout + "，条件仍未成立：" + what);
            }
            sleepBriefly(what);
        }
    }

    /**
     * 反复尝试，直到**这一次**有结果。
     *
     * <p>给"尝试本身有副作用"的场合用 —— 典型是抢锁：失败的尝试什么都没发生，
     * 成功的那次才发号，所以不能先问"能抢到吗"再抢第二次（第二次会被自己挡住）。
     * 调用方拿到的就是成功那一次的结果。
     */
    public static <T> T untilPresent(String what, Supplier<Optional<T>> attempt) {
        return untilPresent(what, attempt, DEFAULT_TIMEOUT);
    }

    public static <T> T untilPresent(String what, Supplier<Optional<T>> attempt, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<T> result = attempt.get();
            if (result.isPresent()) {
                return result.get();
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("等了 " + timeout + "，仍未取到：" + what);
            }
            sleepBriefly(what);
        }
    }

    private static void sleepBriefly(String what) {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待「" + what + "」时被中断", e);
        }
    }

    private Await() {
    }
}
