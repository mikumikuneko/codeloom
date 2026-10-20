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
 * 聊天室的历史读取。
 *
 * <p>实时通道走 WebSocket（{@code /ws/project-chat}），这里管打开页面时显示什么。
 * 两者共用 {@code ChatMessagePayload}，避免前端写两套渲染。
 *
 * <p>聊天室是项目级的，不是会话级；路径挂在项目下，权限按项目成员判定，
 * 队友能看，非成员看不到。
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
     * 聊天记录，按时间正序返回。
     *
     * @param principal 当前身份
     * @param projectId 项目
     * @param afterId   给了就取它之后的消息（轮询、断线补齐）；不给就取最近若干条
     *                  （打开页面时的初始加载）
     * @param limit     最多返回多少条，会被夹到 1 到 {@code PageLimits.MAX} 之间
     * @return 消息列表，从旧到新
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
        // after 与 recent 两条路在仓储里都已统一成时间正序。
        return (afterId == null
                ? messages.findRecent(project, bounded)
                : messages.findAfter(project, afterId, bounded))
                .stream()
                .map(ChatMessagePayload::of)
                .toList();
    }
}
