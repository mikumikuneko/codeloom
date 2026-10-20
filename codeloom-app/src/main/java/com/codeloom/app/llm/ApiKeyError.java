package com.codeloom.app.llm;

/**
 * “密钥在本轮跑到一半时没了”那句话。
 *
 * <p>模型客户端和联网客户端都在发请求时现取密钥，而用户可能在建好会话之后把它删掉
 * 或换掉。这句话只有一处来源，免得各处说法慢慢分叉。
 */
public final class ApiKeyError {

    private ApiKeyError() {
    }

    /**
     * @param providerName 给人看的服务商名字，不是 id
     * @return 可直接展示给用户的错误信息
     */
    public static String goneMidTurn(String providerName) {
        return providerName + " 的 API Key 现在取不到了 —— "
                + "它多半是在这一轮跑起来之后被删掉或换掉了。重新配置之后重新发一条消息即可。";
    }
}
