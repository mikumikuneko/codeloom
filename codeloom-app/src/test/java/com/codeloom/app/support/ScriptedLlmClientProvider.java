package com.codeloom.app.support;

import com.codeloom.agent.llm.ChatRequest;
import com.codeloom.agent.llm.LlmClient;
import com.codeloom.agent.llm.LlmClientProvider;
import com.codeloom.agent.llm.LlmResult;
import com.codeloom.agent.support.ScriptedLlm;
import com.codeloom.domain.session.ModelConfig;
import com.codeloom.domain.user.UserId;

import java.util.List;
import java.util.Optional;

/**
 * 把 {@link ScriptedLlm} 包成一个 {@link LlmClientProvider} —— app 这边的接口测试用它替掉真实模型。
 *
 * <h2>为什么是包装，而不是另写一份</h2>
 * "按脚本出结果、把真实请求记下来、脚本用完了怎么办"这一整套行为只该定义一次，
 * 而那一次在 {@link ScriptedLlm} 里。这里只多做一件事：**同一个客户端给所有用户、
 * 所有模型用** —— 接口测试关心的是剧本，不是密钥（密钥有它自己的测试）。
 *
 * <p>它曾是独立的第二实现，与 agent 那份各自演化（连"脚本用完了"抛什么话都不同）——
 * 夹具漂移的代价是改一边不影响另一边，而两边都还在跑绿。
 */
public class ScriptedLlmClientProvider implements LlmClientProvider {

    private final ScriptedLlm client = new ScriptedLlm();

    /** 换一份新脚本（每个测试开始前调一次）。 */
    public void script(LlmResult... results) {
        client.script(results);
    }

    /** 真实发出去过的请求，按顺序 —— 断言"模型到底看到了什么"时用它。 */
    public List<ChatRequest> requests() {
        return client.requests();
    }

    /** 每次模型调用**之前**跑一下（传 null 取消）。见 {@link ScriptedLlm#onCall}。 */
    public void onCall(Runnable hook) {
        client.onCall(hook);
    }

    @Override
    public Optional<LlmClient> findClient(UserId ownerId, ModelConfig model) {
        return Optional.of(client);
    }
}
