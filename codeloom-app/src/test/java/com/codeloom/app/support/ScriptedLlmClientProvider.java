package com.codeloom.app.support;

import com.codeloom.agent.llm.LlmRequest;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.support.ScriptedLlm;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.user.UserId;

import java.util.List;
import java.util.Optional;

/**
 * 把 {@link ScriptedLlm} 包装成 {@link LlmClientProvider}，供 app 侧接口测试替代真实模型。
 *
 * <p>脚本出结果、记录真实请求、脚本耗尽后的行为只在 {@link ScriptedLlm} 定义一次；这里只
 * 额外做一件事：同一个客户端供所有用户和模型使用，因为接口测试关心剧本而不是密钥。
 * 不再维护独立实现，否则两边会各自演化（连脚本耗尽的报错都不同）而测试仍各自为绿。
 */
public class ScriptedLlmClientProvider implements LlmClientProvider {

    private final ScriptedLlm client = new ScriptedLlm();

    /** 换一份新脚本（每个测试开始前调一次）。 */
    public void script(LlmResult... results) {
        client.script(results);
    }

    /** 真实发出去过的请求，按顺序 —— 断言"模型到底看到了什么"时用它。 */
    public List<LlmRequest> requests() {
        return client.requests();
    }

    /** 每次模型调用之前执行一下（传 null 取消）。见 {@link ScriptedLlm#onCall}。 */
    public void onCall(Runnable hook) {
        client.onCall(hook);
    }

    @Override
    public Optional<LlmClient> findClient(UserId ownerId, ModelConfig model) {
        return Optional.of(client);
    }
}
