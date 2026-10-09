package com.codeloom.app.project;

import com.codeloom.domain.project.Project;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.workspace.Directories;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把一个项目导出成 zip —— 界面上那个"下载"。
 *
 * <h2>导的是主干</h2>
 * "下载这个项目"要的是**它现在是什么样**，那是主干。各人树上的东西是半成品，
 * 而且每个人一份 —— 按钮上写清"主干"，语义就只有一个。
 *
 * <h2>为什么先落临时文件再发</h2>
 * {@code git archive} 吐的是二进制，而子进程读取那条路会按字符集解码（见 {@code ProcessRunner}）——
 * 二进制过一趟字符集就坏了。所以让 git 自己写文件，再把文件流出去，发完删掉。
 */
@Service
public class ProjectArchive {

    /** 一个已经导出好的包。**用完必须 {@link #discard}** —— 它是个临时目录，没有别人会替我们清。 */
    public record Archived(Path file, String downloadName) {
    }

    private final WorkspaceManager workspaces;

    public ProjectArchive(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    public Archived of(Project project) {
        String stem = safeStem(project.name());
        try {
            // 系统临时目录 + 随机名字：导出物里没有秘密，但也没有理由落在可预测的位置上
            Path staging = Files.createTempDirectory("codeloom-archive-");
            Path zip = staging.resolve(stem + ".zip");
            // 只导项目根那棵子树 —— 仓库里还有 untitled 这一层，而"下载这个项目"不该多出它
            workspaces.archive(Path.of(project.repoPath()),
                    WorkspaceManager.MAIN_BRANCH + ":" + ProjectLayout.DIRECTORY, zip);
            return new Archived(zip, stem + "-main.zip");
        } catch (IOException e) {
            throw new UncheckedIOException("导不出这个项目：" + project.id(), e);
        }
    }

    /** 删掉整个暂存目录（不是只删那个 zip —— 目录是我们建的，就该我们收）。 */
    public void discard(Archived archived) {
        Path staging = archived.file().getParent();
        if (staging != null) {
            Directories.deleteRecursively(staging);
        }
    }

    /**
     * 项目名会进文件名，所以要洗一遍：路径分隔符和 Windows 不允许的那几个字符都换成连字符。
     * 空名兜一个 —— 文件名叫 ".zip" 是那种看着像 bug 的东西。
     */
    private static String safeStem(String name) {
        String cleaned = name.strip().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return cleaned.isEmpty() ? "project" : cleaned;
    }
}
