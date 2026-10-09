package com.codeloom.app.llm;

import com.codeloom.domain.llm.Providers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 我们认识的那几家 —— 界面上"预设供应商"那一排卡片用它。
 *
 * <h2>为什么和 {@code /api/auth/api-key} 分开</h2>
 * 它们回答的是两个问题：那个是"**我**配了什么"（每个用户一份，要登录态），
 * 这个是"**你**支持哪些家"（所有人看到的一样，是产品的一份说明）。
 *
 * <h2>为什么没有请求地址</h2>
 * 界面只需要名字和官网（{@code officialUrl} 是给人点过去的那个网址）。
 * 发请求由服务端做，请求地址留在服务端 —— 露出来只会让人以为那是可以改的。
 */
@RestController
@RequestMapping("/api/providers")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProvidersController {

    /** 预设的那几家，顺序就是界面上的顺序。 */
    @GetMapping
    public List<PresetView> list() {
        return Providers.all().stream().map(PresetView::of).toList();
    }

    /**
     * @param id          标识。配置密钥、建会话、换模型时回传的就是它
     * @param displayName 界面上显示的名字
     * @param officialUrl 官网，**只用来给人点过去**
     */
    public record PresetView(String id, String displayName, String officialUrl) {

        static PresetView of(Providers.Preset preset) {
            return new PresetView(preset.id().value(), preset.displayName(), preset.officialUrl());
        }
    }
}
