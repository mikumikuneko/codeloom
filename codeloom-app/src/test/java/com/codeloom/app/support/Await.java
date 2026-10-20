package com.codeloom.app.support;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 轮询到条件成立为止，用来取代测试里的 {@code Thread.sleep}。
 *
 * <p>固定睡眠把等待异步变化写成猜测：睡短则慢机器上假失败，睡长则快机器上白等，
 * 而且只在别人的机器或 CI 上出现。轮询两端都正确：条件成立立即继续，
 * 超时则报出等待的是哪个条件，而不是只给一句 "assertion failed"。
 */
public final class Await {

    /**
     * 默认超时，远宽于正常所需：这类等待通常几十毫秒成立，十秒是留给机器繁忙的余量。
     * 超时不代表慢，而代表条件确实没有成立。
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
     * 反复尝试，直到某一次有结果。
     *
     * <p>用于尝试本身有副作用的场合，典型是抢锁：失败尝试什么也没发生，成功那次才发号，
     * 因此不能先问"能抢到吗"再抢第二次（会被自己挡住）。调用方拿到成功那次的结果。
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
