package com.codeloom.app.files;

import com.codeloom.domain.workspace.WorkspacePathGuard;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.function.Supplier;

/**
 * 浏览某个人在某个项目里那棵树中的文件，供工作区左中两栏取数。
 *
 * <p>按“项目 + 可选的人”取，不按会话：进入界面就是工作区，会话要等用户发第一句话才有；
 * 树本来就挂在“人 × 项目”上。{@code owner} 不传是自己，传别人用于观战，但同样要过
 * 项目成员检查，非成员一律 404。
 *
 * <p>写接口不接受 {@code owner}：读可以看别人的树，写只能写自己的。见
 * {@code docs/decisions/architecture/2026-09-29-write-endpoints-carry-no-owner.md}。
 *
 * <p>{@link WorkspacePathGuard.PathEscapeException} 在这里表示客户端传了越界路径，
 * 翻成 {@link IllegalArgumentException}，由全局异常处理映射成 400；不翻会变成 500，
 * 把“你传错了”报成“服务器坏了”。
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
     * 列一层目录，不递归。
     *
     * @param principal 当前身份
     * @param projectId 项目
     * @param path      相对项目根的路径；省略就是项目根目录
     * @param owner     看谁的树；不传就是自己的，必须是这个项目的成员
     * @param trunk     为 true 时看主干；此时 {@code owner} 被忽略
     * @return 该层条目，目录在前，其余按名字不区分大小写排序
     * @throws IllegalArgumentException 路径越界，或那个路径不是目录
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
     * @param principal 当前身份
     * @param projectId 项目
     * @param path      相对项目根的路径，必填
     * @param owner     看谁的树；不传就是自己的
     * @param trunk     为 true 时读主干上的文件；此时 {@code owner} 被忽略
     * @return 文件内容
     * @throws IllegalArgumentException 路径越界，或那个路径不是普通文件
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
     * 保存一个文本文件的新内容。
     *
     * <p>写接口没有 {@code owner}，只能写自己那棵树。请求里带打开时读到的
     * {@code baseContent}：磁盘上那份已经变了就回 409，让前端先重新读取，绝不静默
     * 覆盖 agent 或另一个人刚写的改动。规则与 Claude Code 的“写前先读”一致。
     *
     * @param principal 当前身份
     * @param projectId 项目
     * @param request   路径、打开时的内容和新内容
     * @throws IllegalArgumentException 路径越界，或缺少打开时的内容
     * @throws org.springframework.web.server.ResponseStatusException 404：文件不存在；
     *         409：文件已被别处修改；413：文件或新内容过大；415：二进制文件
     */
    @PutMapping("/api/projects/{projectId}/files/content")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(Principal principal,
                     @PathVariable String projectId,
                     @RequestBody SaveRequest request) {
        User me = currentUser.require(principal);
        Project project = access.requireMember(me.id(), ProjectId.of(projectId));
        guarded(() -> files.save(me.id(), project.id(), request.path(),
                request.baseContent(), request.content()));
    }

    /**
     * 新建一个文件或目录。
     *
     * <p>写接口没有 {@code owner}：带上它等于“我可以删掉对方工作区里的文件”，
     * 而对方的树是协作模型里唯一只有他能改的地方。
     *
     * @param principal 当前身份
     * @param projectId 项目
     * @param request   路径与是否为目录
     * @throws IllegalArgumentException 路径越界
     * @throws org.springframework.web.server.ResponseStatusException 409：上一级目录不存在，
     *                                                                 或目标已存在
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
     * 改名或移动位置；两者都只是改路径，所以共用一个接口。
     *
     * <p>本项目里“动作”一律是 {@code POST /{资源}/{动作}}，与 {@code /merge}、
     * {@code /sync}、{@code /rewind} 一致。
     *
     * @param principal 当前身份
     * @param projectId 项目
     * @param request   源路径与目标路径
     * @throws IllegalArgumentException 任一路径越界
     * @throws org.springframework.web.server.ResponseStatusException 404：源不存在；
     *         409：目标已存在、上一级目录不存在，或把目录挪进它自己里面
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
     * 而路径被丢掉的表现是一次删错东西的删除。
     *
     * @param principal 当前身份
     * @param projectId 项目
     * @param path      相对项目根的路径
     * @throws IllegalArgumentException 路径越界
     * @throws org.springframework.web.server.ResponseStatusException 404：目标已经不存在
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

    /**
     * 保存请求。
     *
     * @param path        相对项目根的路径
     * @param baseContent 打开时读到的完整内容，用来发现“这期间被别处改过”
     * @param content     要写入的新内容
     */
    public record SaveRequest(String path, String baseContent, String content) {
    }

    /**
     * 新建请求。
     *
     * @param path      相对项目根的路径
     * @param directory 为 true 时建目录，否则建空文件
     */
    public record CreateRequest(String path, boolean directory) {
    }

    /**
     * 改名或移动请求。
     *
     * @param from 源路径
     * @param to   目标路径
     */
    public record MoveRequest(String from, String to) {
    }

    // ------------------------------------------------------------------

    /**
     * 这次要看谁的树：不传是自己，传了别人就是观战。
     *
     * <p>别人必须是项目成员，否则 404：“不存在的人”和“不是这个项目的人”在客户端看来
     * 必须一样，否则拿一批 id 就能枚举出用户。
     */
    private UserId whoseTree(User me, Project project, String owner) {
        if (owner == null || owner.isBlank()) {
            return me.id();
        }
        UserId whose = UserId.of(owner);
        access.requireMembership(project, whose);
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
