package com.codeloom.app.files;

import com.codeloom.app.project.ProjectLayout;
import com.codeloom.agent.tool.WorkspacePathGuard;
import com.codeloom.app.support.TestSessions;
import com.codeloom.domain.port.Workspace;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.session.Session;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.UserId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 浏览工作区的规则。
 *
 * <p><strong>不连库、不连 Redis</strong> —— 这个服务只依赖 {@code WorkspaceManager} 一个端口，
 * 工作区就是一个真目录（{@code @TempDir}）。所以这些断言在任何机器上都跑，
 * 而且验的是"文件系统上真实的规矩"，不是桩出来的。
 *
 * <p>入参是**所有者 + 项目**，不是会话：树挂在「人 + 项目」上，"看哪个目录"和有没有会话
 * 无关（见 {@code WorkspaceId}）。测试里那个 {@code Session} 只用来取这两半。
 */
class WorkspaceFilesServiceTest {

    @TempDir
    Path tempDir;

    /** 一条只活在测试里的会话 —— 这个服务压根不碰仓储，所以不需要谁把它落库。 */
    private final Session session = Session.create(SessionId.generate(), ProjectId.generate(),
            UserId.generate(), TestSessions.DEFAULT_MODEL);

    private final WorkspaceManager workspaces = mock(WorkspaceManager.class);

    private Path worktree;
    /** 项目根：文件的相对路径都以它为准。见 ProjectLayout */
    private Path projectRoot;
    private WorkspaceFilesService service;

    @BeforeEach
    void setUp() throws IOException {
        worktree = tempDir.resolve("worktree");
        Files.createDirectories(worktree);
        projectRoot = ProjectLayout.rootBelow(worktree);
        Files.createDirectories(projectRoot);
        when(workspaces.find(session.workspaceId()))
                .thenReturn(Optional.of(new Workspace(session.workspaceId(), worktree, "session/x", null)));
        service = new WorkspaceFilesService(workspaces);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("列根目录：目录在前、其余按名字不区分大小写 —— 顺序必须稳定，否则树会跳")
    void listsRootWithDirectoriesFirst() throws IOException {
        Files.createDirectory(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("README.md"), "hi");
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");

        assertThat(service.list(session.ownerId(), session.projectId(), "")).extracting(FileEntryView::name)
                .containsExactly("src", "pom.xml", "README.md");
    }

    @Test
    @DisplayName("空路径就是根目录 —— 不用过守卫，因为它本来就是我们自己的路径")
    void blankPathMeansRoot() throws IOException {
        Files.writeString(projectRoot.resolve("a.txt"), "x");

        assertThat(service.list(session.ownerId(), session.projectId(), null)).extracting(FileEntryView::name).containsExactly("a.txt");
        assertThat(service.list(session.ownerId(), session.projectId(), "   ")).extracting(FileEntryView::name).containsExactly("a.txt");
    }

    @Test
    @DisplayName("【隐藏】.git 和 .codeloom 不列出来 —— 前者是结构、后者是我们自己塞的")
    void gitAndCodeloomAreHidden() throws IOException {
        Files.writeString(projectRoot.resolve(".git"), "gitdir: /somewhere/else");   // worktree 里 .git 是个文件
        Files.createDirectory(projectRoot.resolve(".codeloom"));
        Files.writeString(projectRoot.resolve("real.txt"), "x");

        assertThat(service.list(session.ownerId(), session.projectId(), "")).extracting(FileEntryView::name)
                .containsExactly("real.txt");
    }

    @Test
    @DisplayName("只列一层，不递归 —— 递归会把大仓库整个读进内存")
    void listsOnlyOneLevel() throws IOException {
        Files.createDirectories(projectRoot.resolve("src/main/java/deep"));

        assertThat(service.list(session.ownerId(), session.projectId(), "src")).extracting(FileEntryView::name)
                .containsExactly("main");
    }

    @Test
    @DisplayName("空目录是空列表，不是错误")
    void emptyDirectoryIsAnEmptyList() throws IOException {
        Files.createDirectory(projectRoot.resolve("empty"));

        assertThat(service.list(session.ownerId(), session.projectId(), "empty")).isEmpty();
    }

    @Test
    @DisplayName("文件条目带大小；目录的是 null（目录的\"大小\"没有意义的定义）")
    void sizesAreReportedForFilesOnly() throws IOException {
        Files.createDirectory(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("a.txt"), "12345");

        assertThat(service.list(session.ownerId(), session.projectId(), ""))
                .satisfiesExactly(
                        dir -> {
                            assertThat(dir.name()).isEqualTo("src");
                            assertThat(dir.sizeBytes()).isNull();
                        },
                        file -> {
                            assertThat(file.name()).isEqualTo("a.txt");
                            assertThat(file.sizeBytes()).isEqualTo(5L);
                        });
    }

    // ------------------------------------------------------------------
    // 路径守卫
    // ------------------------------------------------------------------

    @Test
    @DisplayName("【安全】越界路径被拒 —— 这是整个接口最要紧的一条")
    void pathsOutsideTheWorktreeAreRejected() {
        assertThatThrownBy(() -> service.list(session.ownerId(), session.projectId(), "../outside"))
                .isInstanceOf(WorkspacePathGuard.PathEscapeException.class);
        assertThatThrownBy(() -> service.read(session.ownerId(), session.projectId(), "src/../../outside.txt"))
                .isInstanceOf(WorkspacePathGuard.PathEscapeException.class);
    }

    @Test
    @DisplayName("【安全】绝对路径被拒 —— 它没有「相对工作区」的语义")
    void absolutePathsAreRejected() {
        String absolute = projectRoot.resolve("a.txt").toString();

        assertThatThrownBy(() -> service.read(session.ownerId(), session.projectId(), absolute))
                .isInstanceOf(WorkspacePathGuard.PathEscapeException.class);
    }

    @Test
    @DisplayName("【安全】工作区里的符号链接指向外面时，读它被拒")
    void symlinkPointingOutsideIsRejectedOnRead() throws IOException {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "不该被读到");
        // assumeTrue 而不是 return —— 后者会让"没断言"也算通过，
        // 于是这条安全规则到底验没验过，从报告上看不出来
        Assumptions.assumeTrue(createSymlink(projectRoot.resolve("link.txt"), outside),
                "这个平台不让建符号链接（Windows 上要开发者模式）");

        assertThatThrownBy(() -> service.read(session.ownerId(), session.projectId(), "link.txt"))
                .isInstanceOf(WorkspacePathGuard.PathEscapeException.class);
    }

    @Test
    @DisplayName("符号链接在列表里当【文件】看，不展开 —— 展开它会走到工作区外面")
    void symlinksAreListedAsFilesNotExpanded() throws IOException {
        Path outsideDir = tempDir.resolve("outside-dir");
        Files.createDirectory(outsideDir);
        Assumptions.assumeTrue(createSymlink(projectRoot.resolve("link"), outsideDir),
                "这个平台不让建符号链接（Windows 上要开发者模式）");

        assertThat(service.list(session.ownerId(), session.projectId(), "")).singleElement().satisfies(entry -> {
            assertThat(entry.directory()).as("目录链接不能被当成目录").isFalse();
            assertThat(entry.sizeBytes()).as("不去读链接目标的大小").isNull();
        });
    }

    // ------------------------------------------------------------------
    // 读文件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("读文件：内容、大小、没有截断")
    void readsAFile() throws IOException {
        Files.writeString(projectRoot.resolve("a.txt"), "hello 中文");

        FileContentView content = service.read(session.ownerId(), session.projectId(), "a.txt");

        assertThat(content.content()).isEqualTo("hello 中文");
        assertThat(content.binary()).isFalse();
        assertThat(content.truncated()).isFalse();
        assertThat(content.sizeBytes()).isEqualTo(Files.size(projectRoot.resolve("a.txt")));
    }

    @Test
    @DisplayName("【截断】超过上限时截断，而且切在【换行】处 —— 不是把多字节字符切成两半")
    void oversizedFileIsTruncatedAtALineBoundary() throws IOException {
        long lines = WorkspaceFilesService.MAX_READ_BYTES / 2 + 50;   // 每行 "x\n" 两字节
        Files.writeString(projectRoot.resolve("big.txt"), "x\n".repeat((int) lines));

        FileContentView content = service.read(session.ownerId(), session.projectId(), "big.txt");

        assertThat(content.truncated()).isTrue();
        assertThat(content.content()).endsWith("\n");
        assertThat(content.content()).doesNotContain("�");
        // 真实大小照报 —— 前端要显示的是"这个文件多大"，不是"我拿回来多少"
        assertThat(content.sizeBytes()).isGreaterThan(content.content().length());
    }

    @Test
    @DisplayName("【二进制】含 NUL 的文件不当文本读 —— 但那是【标志位】，不是错误")
    void binaryFilesAreFlaggedNotRejected() throws IOException {
        Files.write(projectRoot.resolve("a.jar"), new byte[]{1, 2, 0, 3});

        FileContentView content = service.read(session.ownerId(), session.projectId(), "a.jar");

        assertThat(content.binary()).isTrue();
        assertThat(content.content()).isEmpty();
        assertThat(content.sizeBytes()).isEqualTo(4);
    }

    @Test
    @DisplayName("读目录不是错误吗？是 —— 那是客户端用错了接口")
    void readingADirectoryIsRejected() throws IOException {
        Files.createDirectory(projectRoot.resolve("src"));

        assertThatThrownBy(() -> service.read(session.ownerId(), session.projectId(), "src"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是一个普通文件");
    }

    @Test
    @DisplayName("把文件当目录列，同样拒掉")
    void listingAFileIsRejected() throws IOException {
        Files.writeString(projectRoot.resolve("a.txt"), "x");

        assertThatThrownBy(() -> service.list(session.ownerId(), session.projectId(), "a.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是一个目录");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("【工作区被删了】报服务端状态不对（500），不是「你传错了」（400）")
    void missingWorktreeIsAServerSideProblem() {
        when(workspaces.find(session.workspaceId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(session.ownerId(), session.projectId(), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("目录不在磁盘上");
    }

    /** @return 建成功返回 true；这个平台不允许（Windows 上要开发者模式）返回 false */
    private static boolean createSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
