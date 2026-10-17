package com.codeloom.app.llm;

/**
 * 「密钥在本轮跑到一半时没了」那句话。
 *
 * <p>模型客户端和联网客户端各自会在发请求时现取密钥（见两处的 {@code Supplier}），
 * 而会话建好之后用户可能把它删掉或换掉。三处各写一遍那句话的结果是说法慢慢分叉
 * （"这条会话的…" / "这一家（deepseek）的…"），而它们说的是同一个事实。
 */
public final class ApiKeyError {

    private ApiKeyError() {
    }

    /**
     * @param providerName **给人看的名字**（{@code Providers.displayNameOf}），不是 id
     */
    public static String goneMidTurn(String providerName) {
        return providerName + " 的 API Key 现在取不到了 —— "
                + "它多半是在这一轮跑起来之后被删掉或换掉了。重新配置之后重新发一条消息即可。";
    }
}
