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
 * 取"某一轮改了某个文件的什么" —— 一段 diff 正文。
 *
 * <h2>为什么是按需取，而不是写事件的时候就记下来</h2>
 * 事件表是**每一处读取都要过一遍**的东西（断线补齐、回放、崩溃恢复），
 * 而"这一轮改了什么"那条事件每次收尾都会追加。把正文塞进去，等于让每一个读事件的地方
 * 都为它买单 —— 而正文只有人**点开某一个文件**时才需要。
 *
 * <p>指针早就在事件里了（{@code WorkspaceChanges.commitSha}），所以这里要做的
 * 只是"拿着它去问一次 git"。
 */
@Service
public class ProjectDiff {

    /**
     * 正文的字符上限。
     *
     * <p>一次格式化能改几千行，而**人看的那一份**不需要全部。截断了会如实标记
     * （见 {@link FileDiff#truncated}），界面据此说"没显示全" ——
     * 那比"打不开"有用，也比假装这就是全部诚实。
     */
    private static final int MAX_CHARS = 200_000;

    private final WorkspaceManager workspaces;

    public ProjectDiff(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * @param projectPath **项目相对**路径（界面上说的那个），不是 git 的路径 ——
     *                    翻那一道在这里做，见 {@link ProjectLayout#toGitPath}
     */
    public FileDiff of(Project project, String commitSha, String projectPath) {
        // 只收项目内的相对路径：绝对路径、以及**任何一段是 `..`** 的都拒。
        //
        // **按段判，不按"字符串里有没有 `..`"**：`a..b.txt` 是个合法的文件名，
        // 拿子串去判会把它误伤成一个 400 —— 而同一个路径走文件那条路是好的
        if (projectPath == null || projectPath.isBlank() || projectPath.startsWith("/")
                || Arrays.asList(projectPath.split("/", -1)).contains("..")) {
            // git 那边收的是 pathspec，路径不对它不报错、只会一条都匹配不上 ——
            // 那表现成"这个文件没改过"，而真正的原因是路径写错了
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "路径要写成项目内的相对路径，收到：" + projectPath);
        }
        try {
            return workspaces.diffOfFile(Path.of(project.repoPath()), commitSha,
                    ProjectLayout.toGitPath(projectPath), MAX_CHARS);
        } catch (GitCommandException e) {
            // 提交被回收了、或者谁给了个不在这棵树里的 sha —— 那是"问不出来"，
            // 不是"我们坏了"。报 500 的话，看的人不知道该去查什么。
            //
            // 注意 404 只覆盖**提交**取不出来：路径对不上时 git 退出码是 0、输出为空，
            // 那在存储层分不出"没改过"和"路径写错了"。所以这里的路径必须是
            // 事件里记过的那一个 —— 调用方拿它来，这条区分才成立
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "从这棵树里取不出这个提交的改动 —— 这个提交是那棵树的起点，或者已经不在了");
        }
    }
}
