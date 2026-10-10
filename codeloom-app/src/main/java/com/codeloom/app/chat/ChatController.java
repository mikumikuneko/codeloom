package com.codeloom.app.chat;

import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.app.web.PageLimits;
import com.codeloom.domain.port.ChatMessageRepository;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.User;
import com.codeloom.realtime.chat.ChatMessagePayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 聊天室的**历史**读取。
 *
 * <p>实时那条走 WebSocket（{@code /ws/project-chat}），而这里管的是"打开页面时该显示什么"。
 * 两者共用同一个消息形状（{@code ChatMessagePayload}）—— 让历史和实时长得不一样，
 * 前端就得写两套渲染，而它们迟早会不一致。
 *
 * <p>聊天室是**项目级**的，不是会话级：两个人聊的是这个项目，不是各自的会话。
 * 所以路径挂在项目下面，权限也按"是不是这个项目的成员"判 —— 队友能看，非成员看不到。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatController {

    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final ChatMessageRepository messages;

    public ChatController(CurrentUser currentUser, ProjectAccess access,
                          ChatMessageRepository messages) {
        this.currentUser = currentUser;
        this.access = access;
        this.messages = messages;
    }

    /**
     * 聊天记录。
     *
     * @param afterId 给了就取它之后的消息（**轮询/断线补齐**）；不给就取最近的若干条
     *                （**打开页面时的初始加载**）。两种用途共用一个端点，因为它们是
     *                同一个问题的两个方向：从哪儿开始看。
     */
    @GetMapping("/api/projects/{projectId}/chat/messages")
    public List<ChatMessagePayload> history(Principal principal,
                                            @PathVariable String projectId,
                                            @RequestParam(required = false) Long afterId,
                                            @RequestParam(defaultValue = PageLimits.DEFAULT_PARAM) int limit) {
        User me = currentUser.require(principal);
        ProjectId project = ProjectId.of(projectId);
        access.requireMember(me.id(), project);

        int bounded = Math.clamp(limit, 1, PageLimits.MAX);
        // 返回的是**时间正序**的：聊天记录从旧到新，而 "after" 与 "recent" 两条路
        // 在仓储里已经统一成正序了（findRecent 内部会把倒序翻回来）
        return (afterId == null
                ? messages.findRecent(project, bounded)
                : messages.findAfter(project, afterId, bounded))
                .stream()
                .map(ChatMessagePayload::of)
                .toList();
    }
}
