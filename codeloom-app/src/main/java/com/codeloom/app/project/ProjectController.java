package com.codeloom.app.project;

import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.app.web.PageLimits;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ContentDisposition;
import com.codeloom.domain.workspace.FileDiff;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Principal;
import java.util.List;

/**
 * 项目接口。
 *
 * <p>只在 servlet Web 应用里存在（{@code @ConditionalOnWebApplication}），因此不启容器的
 * 测试拿到的上下文没有 Web 层；理由见 {@code SecurityConfig} 的类注释。
 *
 * <p>请求体是控制器内部的嵌套 record（只在这里使用），响应体是顶层 record（由服务层组装）。
 */
@RestController
@RequestMapping("/api/projects")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProjectController {

    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final ProjectService projects;
    private final ProjectArchive archives;
    private final ProjectDiff diffs;

    public ProjectController(CurrentUser currentUser, ProjectAccess access, ProjectService projects,
                             ProjectArchive archives, ProjectDiff diffs) {
        this.currentUser = currentUser;
        this.access = access;
        this.projects = projects;
        this.archives = archives;
        this.diffs = diffs;
    }

    /**
     * 建一个项目；创建者成为第一个成员和房主。
     *
     * @param request 项目名；为空时由领域校验拒绝，以 400 返回
     * @return 新项目视图
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(Principal principal, @RequestBody CreateProjectRequest request) {
        User me = currentUser.require(principal);
        // 名字校验在领域里（Project 的紧凑构造器），这里不重复，否则两份规则迟早不一致
        return projects.view(projects.create(me, request.name()));
    }

    /**
     * 下载这个项目的主干版本（zip）。
     *
     * <p>响应用 {@code Content-Disposition: attachment}，由浏览器走自己的下载通路，
     * 前端只需要一个链接。导出物是临时文件，由这里在流式响应体的 finally 里发完就删，
     * 成功和失败两条路都会走到。
     *
     * @return zip 流；文件在响应结束后删除
     */
    @GetMapping("/{projectId}/archive")
    public ResponseEntity<StreamingResponseBody> archive(Principal principal,
                                                         @PathVariable String projectId) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        ProjectArchive.Archived archived = archives.of(project);

        StreamingResponseBody body = out -> {
            try (InputStream in = Files.newInputStream(archived.file())) {
                in.transferTo(out);
            } finally {
                archives.discard(archived);
            }
        };
        // 文件名可能是中文：ContentDisposition 写成 RFC 5987 的编码形式，浏览器仍拿到原名
        String disposition = ContentDisposition.attachment()
                .filename(archived.downloadName(), StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    /**
     * 某一轮里某个文件改了什么，即一段 diff 正文。仅限成员。
     *
     * <p>{@code commitSha} 来自 {@code WorkspaceChanges} 事件，{@code path} 是项目相对路径
     * （事件里记的就是这个形状）。提交取不到时返回 404：提交可能已被回收，而"问不出来"
     * 和"没改过"是两件事。
     *
     * @param commitSha 这一轮的提交
     * @param path      项目相对路径
     * @return diff 正文与是否被截断
     */
    @GetMapping("/{projectId}/diff")
    public FileDiffView diff(Principal principal,
                             @PathVariable String projectId,
                             @RequestParam String commitSha,
                             @RequestParam String path) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        FileDiff diff = diffs.of(project, commitSha, path);
        return new FileDiffView(diff.text(), diff.truncated());
    }

    /**
     * 「我的项目」，只列这个人参与的。分页上限由服务端夹住，客户端不能要求一次拉回无限多。
     *
     * @param offset 跳过多少条；负数按 0 处理（MySQL 的 {@code OFFSET} 不接受负数）
     * @param limit  这一页最多多少条；夹在 1 到 {@link PageLimits#MAX} 之间
     * @return 这一页项目
     */
    @GetMapping
    public List<ProjectView> mine(Principal principal,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = PageLimits.DEFAULT_PARAM) int limit) {
        User me = currentUser.require(principal);
        return projects.listFor(me.id(), PageLimits.limit(limit), PageLimits.offset(offset));
    }

    /**
     * 单个项目；仅限成员。
     *
     * @return 项目视图
     */
    @GetMapping("/{projectId}")
    public ProjectView one(Principal principal, @PathVariable String projectId) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return projects.view(project);
    }

    /**
     * 退出项目：每个成员都能做，房主也一样，没有"删除项目"这个动作。
     *
     * <p>带走他名下的会话（连同事件流和幂等键）和他那棵树（库里的行与磁盘目录）；
     * 聊天记录和邀请挂在项目上，不动。房主退出时项目转给剩下的人；最后一个人退出时
     * 项目连同仓库目录一起消失，界面靠 {@code ProjectView.ownerId} 判断该说哪一句。
     *
     * <p>路径里的 {@code me} 表示这个动作只能对自己做；支持"踢人"时是另一条路径，
     * 权限判断也不同。返回 204，因为响应没有内容。
     */
    @DeleteMapping("/{projectId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(Principal principal, @PathVariable String projectId) {
        User me = currentUser.require(principal);
        ProjectId id = ProjectId.of(projectId);
        projects.leave(access.requireMember(me.id(), id), me.id());
    }

    // 「把一个人拉进项目」不在这里，而是邀请链接（见 InvitationController）：
    // 两条路做同一件事迟早会在上限检查、留痕或权限上不一致。

    /**
     * @param name 项目名；为空时由领域校验拒绝
     */
    public record CreateProjectRequest(String name) {
    }

    /**
     * @param text      统一的 diff 正文
     * @param truncated 正文被截断；界面必须显示这一点，否则截断的 diff 与"只改了这么多"无法区分
     */
    public record FileDiffView(String text, boolean truncated) {
    }

}
