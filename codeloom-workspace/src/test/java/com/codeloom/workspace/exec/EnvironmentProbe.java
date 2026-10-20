package com.codeloom.workspace.exec;

/**
 * 只打印自身环境变量名的子进程，给 {@link ProcessRunnerTest} 用。
 *
 * <p>环境清洗发生在子进程一侧，只能在真进程里读它自己的环境。只打印名字、不打印值：
 * 这条测试要证明密钥没有被传下去，打印值会让它自己成为泄露源。
 */
public final class EnvironmentProbe {

    private EnvironmentProbe() {
    }

    public static void main(String[] args) {
        System.getenv().keySet().forEach(System.out::println);
    }
}
