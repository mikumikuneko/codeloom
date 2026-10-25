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
 * 把一个项目的主干导出成 zip，即界面上的"下载"。
 *
 * <p>导出的是主干而不是某个人的工作区：后者是半成品，而且每人一份。先让 git 写临时文件
 * 再流式发出，因为 {@code git archive} 输出二进制，而子进程读取会按字符集解码。
 *
 * <p>下载语义见 {@code docs/decisions/feature/2026-10-08-download-the-project-as-zip.md}。
 */
@Service
public class ProjectArchive {

    /**
     * 一个已经导出好的包。用完必须 {@link #discard}：它位于临时目录，没有别人会清理。
     *
     * @param file         临时 zip 文件
     * @param downloadName 下载时显示的文件名
     */
    public record Archived(Path file, String downloadName) {
    }

    private final WorkspaceManager workspaces;

    public ProjectArchive(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * 把主干导出成临时 zip。
     *
     * @param project 要导出的项目
     * @return 临时文件及下载名；调用方负责 {@link #discard}
     * @throws UncheckedIOException 临时文件创建失败；git 导出失败时原样抛出
     *                              {@code GitCommandException}
     */
    public Archived of(Project project) {
        String stem = safeStem(project.name());
        Path staging = null;
        try {
            // 系统临时目录 + 随机名字：内容没有秘密，但也没有理由放在可预测的位置
            staging = Files.createTempDirectory("codeloom-archive-");
            Path zip = staging.resolve(stem + ".zip");
            // 只导项目根子树：仓库里还有 untitled 这一层，"下载这个项目"不该多出它
            workspaces.archive(Path.of(project.repoPath()),
                    WorkspaceManager.MAIN_BRANCH + ":" + ProjectLayout.DIRECTORY, zip);
            return new Archived(zip, stem + "-main.zip");
        } catch (IOException e) {
            discardQuietly(staging);
            throw new UncheckedIOException("导不出这个项目：" + project.id(), e);
        } catch (RuntimeException e) {
            // 流还没开始，控制器拿不到 Archived，不会有人替这里清理
            discardQuietly(staging);
            throw e;
        }
    }

    private static void discardQuietly(Path staging) {
        if (staging != null) {
            Directories.deleteRecursively(staging);
        }
    }

    /**
     * 删掉整个暂存目录，而不只是 zip：目录是本类建的，由本类清理。
     *
     * @param archived 要清理的导出物；可重复调用
     */
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
