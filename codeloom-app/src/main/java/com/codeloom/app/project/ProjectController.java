package com.codeloom.app.project;

import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ContentDisposition;
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
 * <p>{@code @ConditionalOnWebApplication} 是这条规则的一部分：只在 servlet Web 应用里存在，
 * 于是那些不启容器的测试拿到的上下文里没有 Web 层。理由见 {@code SecurityConfig} 的类注释。
 *
 * <p>请求体是**控制器内部**的嵌套 record（只有这个控制器用它），
 * 而响应体是顶层的 record（服务层要负责组装它）。这条分工让"传输形状"和
 * "业务结果"各自待在该待的地方。
 */
@RestController
@RequestMapping("/api/projects")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProjectController {

    /** 一页最多多少条。给得比"正常一屏"宽得多，它拦的是**没上限**，不是"翻页体验"。 */
    private static final int MAX_PAGE = 200;

    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final ProjectService projects;
    private final ProjectArchive archives;

    public ProjectController(CurrentUser currentUser, ProjectAccess access, ProjectService projects,
                             ProjectArchive archives) {
        this.currentUser = currentUser;
        this.access = access;
        this.projects = projects;
        this.archives = archives;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(Principal principal, @RequestBody CreateProjectRequest request) {
        User me = currentUser.require(principal);
        // 名字的校验在领域里（Project 的紧凑构造器），这里不重复一遍 ——
        // 重复的那一份迟早会和另一份不一致。空名字会以 400 出去，见 ApiExceptionHandler
        return projects.view(projects.create(me, request.name()));
    }

    /**
     * 下载这个项目（**主干**那个版本，zip）。
     *
     * <p>响应用 {@code Content-Disposition: attachment}，交给浏览器自己那条下载通路 ——
     * 前端只要一个链接就够了，不必先取回 blob 再存盘。
     *
     * <p>导出物是临时文件，所以由这里负责**发完就删**：流式响应体里 finally 那段，
     * 成功和失败两条路都会走到。
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
        // 文件名可能是中文：ContentDisposition 会写成 RFC 5987 那种带编码的形式，
        // 浏览器拿到的仍是原来的名字
        String disposition = ContentDisposition.attachment()
                .filename(archived.downloadName(), StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    /**
     * 「我的项目」——只列这个人参与的，不是全库的。
     *
     * <p>**分页**：这个列表没有上限，而不分页的话，某个用户攒够几千个项目之后
     * 那一次请求就会把整张表扫出来。上限由服务端夹住，理由和事件流那边一样 ——
     * 一个客户端不该能要求一次拉回无限多。
     *
     * @param offset 跳过多少条；负数按 0 处理（MySQL 的 {@code OFFSET} 不接受负数）
     * @param limit  这一页最多多少条
     */
    @GetMapping
    public List<ProjectView> mine(Principal principal,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "50") int limit) {
        User me = currentUser.require(principal);
        return projects.listFor(me.id(), Math.clamp(limit, 1, MAX_PAGE), Math.max(0, offset));
    }

    @GetMapping("/{projectId}")
    public ProjectView one(Principal principal, @PathVariable String projectId) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        return projects.view(project);
    }

    /**
     * 退出项目。**每个成员都能做，房主也一样** —— 没有"删除项目"这个动作。
     *
     * <p>退出带走的是**他自己的**：名下会话（连同事件流和幂等键）、他那棵树
     * （库里的行 + 磁盘上的目录）。聊天记录和邀请不动，它们挂在项目上。
     *
     * <p>他是房主时项目转给剩下那个人；他是最后一个人时，项目本身跟着消失
     * （连仓库目录一起）。所以同一句"退出"，对不同的人意味着不同的事 ——
     * 而界面要能把这件事说出来（它靠 {@code ProjectView.ownerId} 判断该说哪一句）。
     *
     * <p>路径里的 {@code me} 不是啰嗦：这个动作只能对自己做。将来真要支持
     * "把队友踢出去"，那条路径会长得不一样，而且它要过的是另一套权限判断。
     *
     * <p>204 而不是 200：没有内容要说。
     */
    @DeleteMapping("/{projectId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(Principal principal, @PathVariable String projectId) {
        User me = currentUser.require(principal);
        ProjectId id = ProjectId.of(projectId);
        projects.leave(access.requireMember(me.id(), id), me.id());
    }

    // 「把一个人拉进项目」不在这里 —— 它是**邀请链接**，见 InvitationController。
    // **两条路做同一件事就会在某处不一致**（上限检查、留痕、谁能做），
    // 所以只保留邀请链接这一条。

    public record CreateProjectRequest(String name) {
    }

}
