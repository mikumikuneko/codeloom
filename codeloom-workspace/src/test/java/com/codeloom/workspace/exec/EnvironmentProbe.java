package com.codeloom.workspace.exec;

/**
 * 一个只打印**自己环境变量名**的子进程。
 *
 * <p>给 {@link ProcessRunnerTest} 用。清洗发生在子进程那一侧，所以只能起一个真进程去读
 * 它自己的环境 —— 在父进程里断言等于什么都没验。
 *
 * <p>**只打印名字，不打印值。** 这条测试要证明的正是"某把密钥没被传下去"，
 * 要是它顺手把值打到测试输出里，那它自己就成了泄露源。
 */
public final class EnvironmentProbe {

    private EnvironmentProbe() {
    }

    public static void main(String[] args) {
        System.getenv().keySet().forEach(System.out::println);
    }
}
