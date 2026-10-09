package com.codeloom.app.files;

import com.codeloom.agent.tool.WorkspacePathGuard;
import com.codeloom.app.project.ProjectLayout;
import com.codeloom.domain.port.WorkspaceManager;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import com.codeloom.domain.workspace.WorkspaceId;
import com.codeloom.workspace.Directories;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 浏览**某个人在某个项目里那棵树的工作区**里的文件 —— 前端的左中两栏靠它取数。
 *
 * <h2>读的是树的工作区，不是主干</h2>
 * agent 改的就是这棵树的 worktree。所以这里看到的就是「**这个人现在改到哪儿了**」——
 * 也正是将来会被拿去合并的那一份。想看主干请走合并那条路径，这里不掺和。
 *
 * <p>树挂在「用户 × 项目」上（见 {@code WorkspaceId}），所以同一个人的两条会话看到的
 * **是同一份文件**。这不是 bug ——
 * 换一段对话不该让上一轮的改动消失。
 *
 * <h2>路径一律过守卫</h2>
 * {@link WorkspacePathGuard} 是 agent 工具链上最要紧的那个类（防任意文件读写）。
 * 这里**复用它而不是重写一遍** —— 两套守卫迟早会走岔，而走岔的那一套就是漏洞。
 * 唯一的区别是"谁来承担后果"：工具路径上它是"模型做了一次不该做的尝试"，
 * 到了 HTTP 这一层它就是 **400**（见 {@code WorkspaceFilesController}）。
 *
 * <h2>只列一层，不递归</h2>
 * 递归会把一个大仓库整个读进内存再序列化。前端点开一层拉一层，
 * 既省服务端也省浏览器 —— 而且**不点开的东西永远是零成本**。
 */
@Component
public class WorkspaceFilesService {

    /**
     * 一次最多读多少字节。
     *
     * <p>够看一个源码文件，又不至于让一个巨型文件（日志、生成的代码）把响应撑爆。
     * 超了就截断，并且**如实置起 {@code truncated}** —— 前端要能告诉人"后面还有"。
     *
     * <p>包级可见而不是 private：测试要造一个"刚好超过上限"的文件，
     * 而把 1 MiB 抄进测试里的话，改了上限测试还在验旧的那个数。
     */
    static final int MAX_READ_BYTES = 1 << 20;   // 1 MiB

    /**
     * 二进制探测看多少字节。
     *
     * <p>只看开头这一段就够：真正的文本文件不会有 NUL，而有 NUL 的文件几乎不可能只在后半段有。
     * 看全量的话，读一个几百兆的二进制就得整个扫一遍，而结论不会变。
     */
    private static final int BINARY_PROBE_BYTES = 8 * 1024;

    /**
     * 不列出来的名字。只有两个，而且都有明确理由。
     *
     * <p><strong>刻意不做"按项目类型猜该忽略什么"</strong>（忽略 {@code target/}、
     * {@code node_modules/} 之类）：那些是工作区里**真实存在**的东西，
     * 而猜错的后果是"我以为文件没了"。要折叠它们，是前端的事，不是后端替它决定。
     *
     * <ul>
     *   <li>{@code .git} —— 在 linked worktree 里它是个**文件**（内容是 {@code gitdir: …}）。
     *       列出来既没意义，又把主仓库在磁盘上的位置泄给前端</li>
     *   <li>{@code .codeloom} —— 我们自己塞进去的落盘目录（见 {@code Workspace.TOOL_OUTPUT_DIR}），
     *       里面是给模型读的工具输出，不属于用户的代码</li>
     * </ul>
     */
    private static final Set<String> HIDDEN = Set.of(".git", ".codeloom");

    /** 目录在前，然后按名字（不区分大小写）。顺序必须稳定，否则树会在刷新时跳。 */
    private static final Comparator<FileEntryView> ENTRY_ORDER =
            Comparator.comparing(FileEntryView::directory).reversed()
                    .thenComparing(FileEntryView::name, String.CASE_INSENSITIVE_ORDER);

    private final WorkspaceManager workspaces;

    public WorkspaceFilesService(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * 列一层目录。
     *
     * @param relativePath 相对工作区的路径；空表示工作区根目录
     * @throws IllegalArgumentException 路径越界、或者那个路径不是目录
     */
    public List<FileEntryView> list(UserId owner, ProjectId project, String relativePath) {
        return listUnder(projectRootOf(owner, project), relativePath);
    }

    /** {@link #list} 和 {@link #listTrunk} 共用的那一份 —— 区别只是根从哪儿来。 */
    private List<FileEntryView> listUnder(Path root, String relativePath) {
        Path dir = resolveInside(root, relativePath);

        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("不是一个目录：" + display(relativePath));
        }

        try (Stream<Path> children = Files.list(dir)) {
            return children
                    .filter(child -> !HIDDEN.contains(child.getFileName().toString()))
                    .map(child -> toEntry(root, child))
                    .sorted(ENTRY_ORDER)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取目录失败：" + display(relativePath), e);
        }
    }

    /**
     * 列**主干**那一层目录。
     *
     * <h2>为什么它和 {@link #list} 是两个方法，而不是一个带开关的</h2>
     * "看谁的树"和"看主干"不是同一个问题的两个答案：前者答的是**人**，
     * 后者答的是**共享的那条线**（上面还有别人合进去的东西）。
     * 一个方法加个布尔开关的话，调用方每次都得去读一眼签名才知道那个 true 指什么。
     */
    public List<FileEntryView> listTrunk(Project project, String relativePath) {
        return listUnder(trunkRoot(project), relativePath);
    }

    /**
     * 读**主干**上的一个文件。
     *
     * <p>只读。改主干只有一条路：**合并**（它要项目级的独占权，见
     * {@code ProjectRepository.acquireExclusive}）。从这里写等于绕过那道独占 ——
     * 而主干是所有会话共享的一处，绕过它的代价不是"某个人看到旧的"，
     * 是**所有人的基线被悄悄改了**。
     */
    public FileContentView readTrunk(Project project, String relativePath) {
        return readUnder(trunkRoot(project), relativePath);
    }

    /**
     * 主干那棵树的根。
     *
     * <p>主干就是**项目主仓库里被检出的 {@code main}**（见 {@code WorkspaceManager}
     * 里"合回主干"那一段），所以路径直接来自 {@code Project.repoPath()} ——
     * 它是建项目时就落下来的，不需要在这儿重新推一遍。
     */
    private static Path trunkRoot(Project project) {
        return ProjectLayout.rootBelow(Path.of(project.repoPath()));
    }

    /**
     * 读一个文件。
     *
     * @param relativePath 相对工作区的路径，必填
     * @throws IllegalArgumentException 路径越界、或者那个路径不是普通文件
     */
    public FileContentView read(UserId owner, ProjectId project, String relativePath) {
        return readUnder(projectRootOf(owner, project), relativePath);
    }

    /** {@link #read} 和 {@link #readTrunk} 共用的那一份 —— 区别只是根从哪儿来。 */
    private FileContentView readUnder(Path root, String relativePath) {
        Path file = resolveInside(root, relativePath);

        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("不是一个普通文件：" + display(relativePath));
        }

        long size = sizeOf(file);
        byte[] head = readHead(file, size);
        boolean truncated = size > head.length;

        if (looksBinary(head)) {
            // 二进制不是错误 —— 用户点开了树里的一个 .jar，该告诉他"看不了"，
            // 而不是弹一个失败。所以用标志位，不用异常
            return new FileContentView(display(relativePath), "", false, true, size);
        }
        return new FileContentView(display(relativePath),
                decodeText(head, truncated), truncated, false, size);
    }

    // ------------------------------------------------------------------
    // 改文件
    // ------------------------------------------------------------------

    /**
     * 新建一个空文件，或者一个空目录。
     *
     * <h2>为什么可以直接往工作区里写</h2>
     * 因为这棵树本来就是一棵**普通的工作目录**：agent 往里写文件的方式和这里一模一样，
     * 两边看到的是同一份文件。手工建的文件和 agent 建的没有区别 ——
     * 下一个 checkpoint 会把它们一起收进去。
     *
     * <p>反过来说，它**不是立刻持久的**：在下一个 checkpoint 之前它只是一个未跟踪文件，
     * 而回滚（{@code reset --hard} + {@code clean -fd}）会把它清掉。
     * 这是对的 —— 它和人手动改一处代码是同一类东西，不该比 agent 的改动更"结实"。
     */
    public void create(UserId owner, ProjectId project, String relativePath, boolean directory) {
        Path target = insideOwnTree(owner, project, relativePath);

        if (!Files.isDirectory(target.getParent())) {
            throw conflict("放不下：上一级目录不在（" + display(relativePath) + "）。刷新一下看看");
        }
        // CREATE_NEW 那一类语义：**存在就失败，绝不覆盖**。"新建"两个字里没有"覆盖"的意思，
        // 而一次手滑毁掉一个文件，是这个界面能造成的最坏的后果
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw conflict("已经有了：" + display(relativePath));
        }

        try {
            if (directory) {
                Files.createDirectory(target);
            } else {
                Files.createFile(target);
            }
        } catch (FileAlreadyExistsException e) {
            // 检查过了还会撞上，只可能是 agent 正在同一秒写同一个地方
            throw conflict("已经有了：" + display(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("新建失败：" + display(relativePath), e);
        }
    }

    /**
     * 改名或者挪个位置（同一个操作：改的是路径）。
     *
     * <p>用文件系统上的移动，**不走 {@code git mv}**：这是一棵普通的工作目录，
     * 下一次 {@code add -A} 自己会看见。走 git 的话会把这次改动**暂存**起来，
     * 于是多出一个"暂存了但没提交"的中间状态 —— 而回滚（reset + clean）又得额外照顾它。
     */
    public void move(UserId owner, ProjectId project, String from, String to) {
        Path source = insideOwnTree(owner, project, from);
        Path target = insideOwnTree(owner, project, to);

        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound("没有这个文件：" + display(from));
        }
        // 把一个目录挪进它自己里面，多数文件系统会以一个看不懂的 IOException 结束。
        // 这一句把它变成一句人话
        if (target.startsWith(source)) {
            throw conflict("不能把一个目录挪到它自己里面去");
        }
        if (!Files.isDirectory(target.getParent())) {
            throw conflict("放不下：上一级目录不在（" + display(to) + "）");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw conflict("那里已经有一个同名的了：" + display(to));
        }

        try {
            Files.move(source, target);
        } catch (IOException e) {
            throw new UncheckedIOException("改名失败：" + display(from), e);
        }
    }

    /**
     * 删掉一个文件或一整棵目录。
     *
     * <p>它**不区分**这两件事：右键删一个目录时人想要的显然是"这个目录没了"，
     * 而不是"删不掉，因为里面还有东西"。
     */
    public void delete(UserId owner, ProjectId project, String relativePath) {
        Path target = insideOwnTree(owner, project, relativePath);

        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound("已经没有这个文件了：" + display(relativePath));
        }
        Directories.deleteRecursively(target);
    }

    // ------------------------------------------------------------------

    /**
     * 把客户端给的路径解析到**这个人自己那棵树**里。
     *
     * <h2>为什么不带 owner 参数</h2>
     * 读的那几个方法都带（观战要看得见对方的代码），**写的一律不带**。
     * 这不是"忘了"：带上就等于"我可以删掉对方工作区里的文件"，
     * 而那把整个协作模型打穿了 —— 他的树是**唯一**一块只有他能改的地方。
     *
     * <p>根目录也走不到这里：空路径会被守卫拒掉，于是"把整个工作区删了"
     * 这个动作在协议层面就不存在。
     */
    private Path insideOwnTree(UserId owner, ProjectId project, String relativePath) {
        return WorkspacePathGuard.resolve(projectRootOf(owner, project), relativePath);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    // ------------------------------------------------------------------

    /**
     * 这个人在这个项目里的那棵树的工作区目录。
     *
     * <h2>为什么入参是「人 + 项目」而不是一条会话</h2>
     * 因为**浏览文件这件事和会话无关**。树挂在「人 × 项目」上（见 {@code WorkspaceId}），
     * 所以"我的工作区"在**还没有任何会话**的时候就已经存在了 —— 而"进项目就是工作区、
     * 会话要等你发第一句话才有"这个模型下，那正是常态。会话是**对话**，
     * 不该是"看到自己代码"的前提。
     *
     * <p>找不到目录意味着它被人从磁盘上删掉了 —— 服务端状态不对，所以是 500 不是 400。
     */
    private Path projectRootOf(UserId owner, ProjectId project) {
        WorkspaceId workspaceId = WorkspaceId.of(owner, project);
        return workspaces.find(workspaceId)
                // **项目根，不是工作区根** —— 界面上看到的就是项目里有什么，
                // 而工作区根下那个目录是"项目"这一层之外的东西。见 ProjectLayout
                .map(ProjectLayout::rootOf)
                .orElseThrow(() -> new IllegalStateException(
                        "工作区 " + workspaceId + " 的目录不在磁盘上。"
                                + "它本该在你第一次打开这个项目时就建好 —— 现在找不到，"
                                + "只可能是被手工删掉了。"));
    }

    /** 越界一律抛 {@link WorkspacePathGuard.PathEscapeException}，由控制器翻成 400。 */
    private static Path resolveInside(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return root;          // 根目录是我们自己的路径，不用过守卫
        }
        return WorkspacePathGuard.resolve(root, relativePath);
    }

    /**
     * 一个子条目。
     *
     * <p>用 {@code NOFOLLOW_LINKS} 判断类型：**符号链接一律当"文件"**，既不展开它、
     * 也不去读它的大小。因为那两件事都会**跟着链接走到工作区外面去** ——
     * 而列目录这条路不过 {@code WorkspacePathGuard}（它管的是单条路径的解析）。
     * 链接本身的类型仍然列得出来，点开读内容时守卫会拦。
     */
    private static FileEntryView toEntry(Path root, Path child) {
        String name = child.getFileName().toString();
        String path = WorkspacePathGuard.relativize(root, child);

        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            return FileEntryView.ofDirectory(name, path);
        }
        Long size = Files.isSymbolicLink(child) ? null : sizeOrNull(child);
        return new FileEntryView(name, path, false, size);
    }

    /** 读不到就返回 null，而不是编一个 0 —— 前端显示"未知"比显示"0 字节"诚实。 */
    private static Long sizeOrNull(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读文件大小失败：" + file, e);
        }
    }

    /** 只读开头至多 {@link #MAX_READ_BYTES} 字节 —— 不整个读进来。 */
    private static byte[] readHead(Path file, long size) {
        int want = (int) Math.min(size, MAX_READ_BYTES);
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(want);
        } catch (IOException e) {
            throw new UncheckedIOException("读文件失败：" + file, e);
        }
    }

    private static boolean looksBinary(byte[] bytes) {
        int probe = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 字节 → 文本。
     *
     * <p><strong>固定按 UTF-8 解，和 agent 的 {@code read_file} 用同一个假设。</strong>
     * 前端显示的和模型看到的必须是同一份 —— 不一样的话，"我以为它看到的是这个"
     * 就永远无从判断，而排查 agent 的行为全靠那个前提。
     *
     * <p>截断时把尾巴退到**最后一个换行**：多字节字符被从中间切开会在末尾多出一个
     * U+FFFD，而"最后一行不完整"比"末尾一个乱码字符"好懂得多。
     */
    private static String decodeText(byte[] bytes, boolean truncated) {
        if (truncated) {
            int lastNewline = lastIndexOf(bytes);
            if (lastNewline > 0) {
                bytes = Arrays.copyOf(bytes, lastNewline + 1);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int lastIndexOf(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == (byte) 10) {
                return i;
            }
        }
        return -1;
    }

    /** 空路径在报错信息里要读得通顺，不能出现「：」后面什么都没有。 */
    private static String display(String relativePath) {
        return relativePath == null || relativePath.isBlank()
                ? "（工作区根目录）"
                : relativePath;
    }
}
