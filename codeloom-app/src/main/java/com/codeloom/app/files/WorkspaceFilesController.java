package com.codeloom.app.files;

import com.codeloom.agent.tool.WorkspacePathGuard;
import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.User;
import com.codeloom.domain.user.UserId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.function.Supplier;

/**
 * 浏览**某个人在某个项目里的那棵树**里的文件 —— 工作区左中两栏的数据来源。
 *
 * <h2>为什么是「项目 + 可选的人」，而不是「一条会话」</h2>
 * 因为这个界面**进去就是工作区，会话要等你发第一句话才有**。按会话取的话，
 * 一个刚打开项目的人会看到一片空白 —— 而他自己的代码明明就在那儿。
 *
 * <p>树本来就是挂在「人 × 项目」上的（见 {@code WorkspaceId}），
 * 所以"我的工作区"和"我有没有会话"从来就是两件事。
 *
 * <h2>为什么要能看**别人**的树</h2>
 * 因为观战要看得见对方的代码 —— 光看事件流（"它改了 Main.java"）而看不见那个文件
 * 现在长什么样，观战就只剩一半。{@code owner} 不传就是自己。
 *
 * <p>传了别人时照样要过成员检查：**不是这个项目的人一律 404**（装作不存在），
 * 和这个项目对越权的一贯处理一致。
 *
 * <h2>越界路径是 400，不是 500</h2>
 * {@link WorkspacePathGuard.PathEscapeException} 在工具链上表示"模型做了一次不该做的尝试"，
 * 要回灌给它。到了这里它表示"**客户端传了个越界路径**"—— 那是客户端的错。
 * 所以翻成 {@link IllegalArgumentException}，交给全局的 {@code ApiExceptionHandler}
 * 映射成 400。不翻的话它是个裸 RuntimeException，会变成 500 ——
 * 把"你传错了"报成"服务器坏了"，而这两种错的处理方式完全相反。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkspaceFilesController {

    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final WorkspaceFilesService files;

    public WorkspaceFilesController(CurrentUser currentUser, ProjectAccess access,
                                    WorkspaceFilesService files) {
        this.currentUser = currentUser;
        this.access = access;
        this.files = files;
    }

    /**
     * 列一层目录。
     *
     * @param path  相对工作区的路径；**省略就是工作区根目录**。只列这一层，不递归
     * @param owner 看谁的树；不传就是自己的。**必须是这个项目的成员**
     * @param trunk 看**主干**；和 {@code owner} 互斥（一个是"谁"，一个是"那条共享的线"）
     */
    @GetMapping("/api/projects/{projectId}/files")
    public List<FileEntryView> list(Principal principal,
                                    @PathVariable String projectId,
                                    @RequestParam(required = false) String path,
                                    @RequestParam(required = false) String owner,
                                    @RequestParam(required = false) Boolean trunk) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        if (Boolean.TRUE.equals(trunk)) {
            return guarded(() -> files.listTrunk(project, path));
        }
        UserId whose = whoseTree(me, project, owner);
        return guarded(() -> files.list(whose, project.id(), path));
    }

    /**
     * 读一个文件。
     *
     * @param path  相对工作区的路径，必填
     * @param owner 看谁的树；不传就是自己的
     * @param trunk 读**主干**上的它；和 {@code owner} 互斥
     */
    @GetMapping("/api/projects/{projectId}/files/content")
    public FileContentView content(Principal principal,
                                   @PathVariable String projectId,
                                   @RequestParam String path,
                                   @RequestParam(required = false) String owner,
                                   @RequestParam(required = false) Boolean trunk) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        if (Boolean.TRUE.equals(trunk)) {
            return guarded(() -> files.readTrunk(project, path));
        }
        UserId whose = whoseTree(me, project, owner);
        return guarded(() -> files.read(whose, project.id(), path));
    }

    /**
     * 新建一个文件或目录。
     *
     * <h2>写接口上**没有** {@code owner}，这是这个类里最要紧的一条</h2>
     * 上面两个读接口都带 `owner` —— 观战要看得见对方的代码。写的一律不带，
     * 而且**不是"没写"而是"不能写"**：带上就等于"我可以删掉对方工作区里的文件"，
     * 而他的那棵树是整个协作模型里唯一一块只有他能改的地方。
     */
    @PostMapping("/api/projects/{projectId}/files")
    @ResponseStatus(HttpStatus.CREATED)
    public void create(Principal principal,
                       @PathVariable String projectId,
                       @RequestBody CreateRequest request) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        guarded(() -> files.create(me.id(), project.id(), request.path(), request.directory()));
    }

    /**
     * 改名，或者挪个位置 —— 改的都是路径，所以是同一个接口。
     *
     * <p>路径写成 {@code /files/move} 而不是对 {@code /files} 发 PATCH：这个项目里
     * **"动作"一律是 {@code POST /{资源}/{动作}}**（{@code /merge}、{@code /sync}、
     * {@code /rewind} 都是）。跟着已有的写法走，读的人不用每次重新判断该用哪个动词。
     */
    @PostMapping("/api/projects/{projectId}/files/move")
    public void move(Principal principal,
                     @PathVariable String projectId,
                     @RequestBody MoveRequest request) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        guarded(() -> files.move(me.id(), project.id(), request.from(), request.to()));
    }

    /**
     * 删掉一个文件或一整棵目录。
     *
     * <p>路径走查询串而不是请求体：DELETE 带请求体在有些代理和客户端上会被丢掉，
     * 而"路径被丢掉"的表现是一次**删错东西**的删除。
     */
    @DeleteMapping("/api/projects/{projectId}/files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal principal,
                       @PathVariable String projectId,
                       @RequestParam String path) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        guarded(() -> files.delete(me.id(), project.id(), path));
    }

    /** 新建的请求体：路径 + 是不是目录。 */
    public record CreateRequest(String path, boolean directory) {
    }

    /** 改名/移动的请求体。 */
    public record MoveRequest(String from, String to) {
    }

    // ------------------------------------------------------------------

    /**
     * 这次要看谁的树。
     *
     * <p>不传就是自己 —— 那是绝大多数情况（左栏是**我的**工作区，我在那儿干活）。
     * 传了别人就是观战。
     *
     * <p>别人必须是这个项目的成员，否则 404：**"不存在的人"和"不是这个项目的人"
     * 在客户端看来必须一样**，否则拿一批 id 挨个试就能枚举出系统里有哪些用户。
     */
    private static UserId whoseTree(User me, Project project, String owner) {
        if (owner == null || owner.isBlank()) {
            return me.id();
        }
        UserId whose = UserId.of(owner);
        if (!project.hasMember(whose)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "项目不存在");
        }
        return whose;
    }

    /** 见类注释：把守卫的越界异常翻成 400，别让它变成 500。 */
    private static <T> T guarded(Supplier<T> call) {
        try {
            return call.get();
        } catch (WorkspacePathGuard.PathEscapeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** 同上，给那几个没有返回值的写接口用。 */
    private static void guarded(Runnable call) {
        try {
            call.run();
        } catch (WorkspacePathGuard.PathEscapeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
