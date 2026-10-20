package com.codeloom.app.project;

import com.codeloom.domain.project.Project;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.workspace.FileDiff;
import com.codeloom.workspace.git.GitCommandException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * 取某一轮里某个文件改了什么，即一段 diff 正文。
 *
 * <p>正文按需生成而不写进事件：事件表在断线补齐、回放、崩溃恢复时都要被读取，
 * 把正文塞进去会让每条读取路径都为它买单，而它只在人点开某个文件时才需要。
 * 事件里已经有指针（{@code WorkspaceChanges.commitSha}）。
 *
 * <p>理由见 {@code docs/decisions/feature/2026-10-10-file-diff-is-fetched-on-demand.md}。
 */
@Service
public class ProjectDiff {

    /**
     * 正文的字符上限。一次格式化能改几千行，人看的那一份不需要全部；截断会如实标记
     * （见 {@link FileDiff#truncated()}），界面据此说明没显示全。
     */
    private static final int MAX_CHARS = 200_000;

    private final WorkspaceManager workspaces;

    public ProjectDiff(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * @param project     项目
     * @param commitSha   这一轮的提交
     * @param projectPath 项目相对路径（界面上说的那个）；转成 git 路径在这里做，
     *                    见 {@link ProjectLayout#toGitPath}
     * @return diff 正文；超出上限时标记为截断
     * @throws ResponseStatusException 400 路径不是项目内的相对路径；404 取不出该提交
     */
    public FileDiff of(Project project, String commitSha, String projectPath) {
        // 只收项目内的相对路径：绝对路径与任何一段是 .. 的都拒。
        // 按段判断而不是找子串：a..b.txt 是合法文件名，子串判断会把它误伤成 400
        if (projectPath == null || projectPath.isBlank() || projectPath.startsWith("/")
                || Arrays.asList(projectPath.split("/", -1)).contains("..")) {
            // git 收的是 pathspec，路径不对时它不报错、只匹配不到任何东西，
            // 表现成"这个文件没改过"；真正的原因是路径写错了
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "路径要写成项目内的相对路径，收到：" + projectPath);
        }
        try {
            return workspaces.diffOfFile(Path.of(project.repoPath()), commitSha,
                    ProjectLayout.toGitPath(projectPath), MAX_CHARS);
        } catch (GitCommandException e) {
            // 提交被回收，或 sha 不在这棵树里：这是"问不出来"，不是服务端故障。
            // 404 只覆盖提交取不出来；路径对不上时 git 退出码为 0、输出为空，
            // 存储层分不出"没改过"和"路径写错了"，所以调用方必须传事件里记过的路径
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "从这棵树里取不出这个提交的改动 —— 这个提交是那棵树的起点，或者已经不在了");
        }
    }
}
