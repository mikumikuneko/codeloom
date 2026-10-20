package com.codeloom.app.llm;

import com.codeloom.domain.llm.Providers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 我们认识的服务商，界面上“预设服务商”那一排卡片用它。
 *
 * <p>与 {@code /api/auth/api-key} 分开：那个回答“我配了什么”（按用户、要登录），
 * 这个回答“支持哪些家”（所有人一样，是产品说明）。
 *
 * <p>不返回请求地址：界面只需要名字和官网（{@code officialUrl} 是给人点过去的）。
 * 发请求由服务端做，露出地址只会让人以为它可以改。
 */
@RestController
@RequestMapping("/api/providers")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProvidersController {

    /**
     * 预设的服务商，顺序就是界面上的顺序。
     *
     * @return 预设列表
     */
    @GetMapping
    public List<PresetView> list() {
        return Providers.all().stream().map(PresetView::of).toList();
    }

    /**
     * @param id          标识；配置密钥、建会话、换模型时回传的就是它
     * @param displayName 界面上显示的名字
     * @param officialUrl 官网，只用来给人点过去
     */
    public record PresetView(String id, String displayName, String officialUrl) {

        static PresetView of(Providers.Preset preset) {
            return new PresetView(preset.id().value(), preset.displayName(), preset.officialUrl());
        }
    }
}
