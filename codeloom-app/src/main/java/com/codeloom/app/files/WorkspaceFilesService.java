package com.codeloom.app.files;

import com.codeloom.app.project.ProjectLayout;
import com.codeloom.app.workspace.WorkspaceLookup;
import com.codeloom.domain.workspace.WorkspacePathGuard;
import com.codeloom.domain.port.Workspace;
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
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 浏览某个人在某个项目里那棵树的工作区文件，供前端左中两栏取数。
 *
 * <p>读的是树的工作区，也就是 agent 改的那一份、将来会被合并的那一份；主干另走
 * {@link #listTrunk} / {@link #readTrunk}。树挂在“用户 × 项目”上，同一个人的两条会话
 * 看到同一份文件。
 *
 * <p>路径一律复用 {@link WorkspacePathGuard}，不另写一套守卫。越界抛
 * {@link WorkspacePathGuard.PathEscapeException}，由控制器翻成 400。
 *
 * <p>只列一层，不递归：递归会把整个大仓库读进内存再序列化。
 */
@Component
public class WorkspaceFilesService {

    /**
     * 一次最多读多少字节。超了就截断，并如实置起 {@code truncated}，前端要能告诉人
     * “后面还有”。
     *
     * <p>包级可见是为了让测试造一个刚好超过上限的文件，而不必把 1 MiB 抄进测试。
     */
    static final int MAX_READ_BYTES = 1 << 20;   // 1 MiB

    /**
     * 二进制探测看多少字节。真正的文本文件不会有 NUL，有 NUL 的文件几乎不会只在后半段有；
     * 看全量只会让读几百兆的二进制多扫一遍，结论不变。
     */
    private static final int BINARY_PROBE_BYTES = 8 * 1024;

    /**
     * 不列出来的名字。
     *
     * <p>刻意不按项目类型猜该忽略什么（{@code target/}、{@code node_modules/} 等）：
     * 那些是工作区里真实存在的东西，猜错会让人以为文件没了；折叠它们属于前端。
     *
     * <ul>
     *   <li>{@code .git}：在 linked worktree 里它是个文件（内容是 {@code gitdir: …}），
     *       列出来没意义，还会泄露主仓库在磁盘上的位置</li>
     *   <li>{@code .codeloom}：我们自己的落盘目录（见 {@code Workspace.PLATFORM_DIR}），
     *       里面是给模型读的工具输出，不属于用户代码</li>
     * </ul>
     */
    private static final Set<String> HIDDEN = Set.of(".git", Workspace.PLATFORM_DIR);

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
     * @param owner        树的所有者
     * @param project      项目
     * @param relativePath 相对项目根的路径；空表示项目根目录
     * @return 该层条目，目录在前，其余按名字不区分大小写排序
     * @throws WorkspacePathGuard.PathEscapeException 路径越界
     * @throws IllegalArgumentException 那个路径不是目录
     * @throws UncheckedIOException     读取目录失败
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
     * 列主干那一层目录。
     *
     * <p>和 {@link #list} 分开而不是加一个布尔开关：“看谁的树”答的是人，
     * “看主干”答的是共享的那条线，两者不是同一个问题的两个答案。
     *
     * @param project      项目
     * @param relativePath 相对主干项目根的路径；空表示项目根目录
     * @return 该层条目
     * @throws WorkspacePathGuard.PathEscapeException 路径越界
     * @throws IllegalArgumentException 那个路径不是目录
     * @throws UncheckedIOException     读取目录失败
     */
    public List<FileEntryView> listTrunk(Project project, String relativePath) {
        return listUnder(trunkRoot(project), relativePath);
    }

    /**
     * 读主干上的一个文件。
     *
     * <p>只读。改主干只有合并一条路，它要项目级的独占权（见
     * {@code ProjectRepository.lock}）；从这里写会绕过独占，悄悄改掉
     * 所有人共享的基线。
     *
     * @param project      项目
     * @param relativePath 相对主干项目根的路径
     * @return 文件内容
     * @throws WorkspacePathGuard.PathEscapeException 路径越界
     * @throws IllegalArgumentException 那个路径不是普通文件
     * @throws UncheckedIOException     读取文件失败
     */
    public FileContentView readTrunk(Project project, String relativePath) {
        return readUnder(trunkRoot(project), relativePath);
    }

    /**
     * 主干那棵树的根：项目主仓库里检出的 {@code main}，路径来自 {@code Project.repoPath()}。
     */
    private static Path trunkRoot(Project project) {
        return ProjectLayout.rootBelow(Path.of(project.repoPath()));
    }

    /**
     * 读一个文件。
     *
     * @param owner        树的所有者
     * @param project      项目
     * @param relativePath 相对项目根的路径，必填
     * @return 文件内容；二进制文件带 {@code binary} 标志且内容为空
     * @throws WorkspacePathGuard.PathEscapeException 路径越界
     * @throws IllegalArgumentException 那个路径不是普通文件
     * @throws UncheckedIOException     读取文件失败
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
     * 用手改过的完整内容覆盖一个文本文件。
     *
     * <p>打开时读到的 {@code baseContent} 必须仍是磁盘上的内容；不一致就拒绝，而不是
     * 把 agent、格式化器或另一个人刚写的改动盖掉。这和 Claude Code 的写文件保护是同一
     * 条规则：先读、再改；读完之后文件变了，就重新读一遍再写。行尾先归一再比较，因为
     * 浏览器 textarea 会把 CRLF 统一成 LF，但磁盘上那份仍可能是 CRLF。
     *
     * <p>只允许改普通文本文件：二进制、被 1 MiB 上限截断的文件都不接受编辑，后者
     * 拿到的本来就不是全文，写回去会把后半段删掉。
     *
     * @param owner        树的所有者
     * @param project      项目
     * @param relativePath 相对项目根的路径
     * @param baseContent  打开时读到的完整内容
     * @param content      要写入的新内容
     * @throws WorkspacePathGuard.PathEscapeException 路径越界
     * @throws IllegalArgumentException 缺少打开时的内容
     * @throws ResponseStatusException  404：文件不存在；409：文件已被别处修改；
     *                                  413：文件或新内容过大；415：二进制文件
     * @throws UncheckedIOException     写入失败
     */
    public void save(UserId owner, ProjectId project, String relativePath,
                     String baseContent, String content) {
        Path target = insideOwnTree(owner, project, relativePath);
        if (!Files.isRegularFile(target)) {
            throw notFound("没有这个文件：" + display(relativePath));
        }
        if (baseContent == null) {
            throw new IllegalArgumentException("缺少打开时的内容，无法确认文件没有被改过");
        }

        long size = sizeOf(target);
        if (size > MAX_READ_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件太大，不能在这里编辑：" + display(relativePath));
        }
        byte[] head = readHead(target, size);
        if (looksBinary(head)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "二进制文件不能在这里编辑：" + display(relativePath));
        }

        String current;
        try {
            current = decodeText(Files.readAllBytes(target), false);
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败：" + display(relativePath), e);
        }
        if (!normalizeNewlines(current).equals(normalizeNewlines(baseContent))) {
            // 与 Claude Code 的提示同一句事实：不是“保存失败”，是“先重新读一遍”
            throw conflict("文件已被别处修改，先重新读取再保存：" + display(relativePath));
        }

        String normalized = normalizeNewlines(content == null ? "" : content);
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_READ_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "内容太大，不能在这里保存：" + display(relativePath));
        }
        // 保留原文件的行尾：浏览器把所有换行都变成 LF，直接写回去会让整份文件看起来
        // 全变了；Windows 项目尤其明显
        String toWrite = current.contains("\r\n")
                ? normalized.replace("\n", "\r\n")
                : normalized;
        try {
            Files.writeString(target, toWrite, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("保存失败：" + display(relativePath), e);
        }
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n");
    }

    /**
     * 新建一个空文件或空目录。
     *
     * <p>这棵树就是普通工作目录，agent 也以同样方式往里写。新建的文件在下一个
     * checkpoint 之前只是未跟踪文件，回滚会把它清掉 —— 它和手改一处代码是同一类东西，
     * 不该比 agent 的改动更持久。
     *
     * @param owner        树的所有者
     * @param project      项目
     * @param relativePath 相对项目根的路径
     * @param directory    true 建目录，false 建空文件
     * @throws WorkspacePathGuard.PathEscapeException 路径越界
     * @throws ResponseStatusException  409：上一级目录不存在，或目标已存在。
     *                                  存在即失败，绝不覆盖
     * @throws UncheckedIOException     创建失败
     */
    public void create(UserId owner, ProjectId project, String relativePath, boolean directory) {
        Path target = insideOwnTree(owner, project, relativePath);

        if (!Files.isDirectory(target.getParent())) {
            throw conflict("放不下：上一级目录不在（" + display(relativePath) + "）。刷新一下看看");
        }
        // 存在就失败，绝不覆盖：“新建”里没有“覆盖”的意思，手滑毁掉文件是最坏的后果。
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
            // 检查过了还会撞上，只可能是 agent 在同一秒写了同一个地方
            throw conflict("已经有了：" + display(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("新建失败：" + display(relativePath), e);
        }
    }

    /**
     * 改名或移动位置；两者都只是改路径。
     *
     * <p>用文件系统移动，不走 {@code git mv}：下一次 {@code add -A} 自己会看见。
     * 走 git 会多出“已暂存、未提交”的中间状态，而回滚还得额外照顾它。
     *
     * @param owner   树的所有者
     * @param project 项目
     * @param from    源路径
     * @param to      目标路径
     * @throws WorkspacePathGuard.PathEscapeException 路径越界
     * @throws ResponseStatusException  404：源不存在；409：目标已存在、上一级目录不存在，
     *                                  或把目录挪进它自己里面
     * @throws UncheckedIOException     移动失败
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
     * <p>不区分这两件事：删目录时人要的是“这个目录没了”，而不是“里面还有东西所以删不掉”。
     *
     * @param owner        树的所有者
     * @param project      项目
     * @param relativePath 相对项目根的路径
     * @throws WorkspacePathGuard.PathEscapeException 路径越界
     * @throws ResponseStatusException  404：目标已经不存在
     * @throws UncheckedIOException     删除失败
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
     * 把客户端给的路径解析到这个人自己那棵树里。
     *
     * <p>写方法一律不带 owner：带上等于“我可以删掉对方工作区里的文件”，而对方的树
     * 是唯一只有他能改的地方。空路径会被守卫拒掉，所以“删掉整个工作区”在协议层面
     * 不存在。见
     * {@code docs/decisions/architecture/2026-09-29-write-endpoints-carry-no-owner.md}。
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
     * 这个人在这个项目里的那棵树的项目根目录。
     *
     * <p>入参是“人 + 项目”而不是会话：树挂在“人 × 项目”上，浏览文件与有没有会话无关。
     * 目录不在磁盘上意味着服务端状态不对（500，不是 400）。这里问的是
     * {@code WorkspaceManager} 的磁盘真相，不是库里的那一行。
     */
    private Path projectRootOf(UserId owner, ProjectId project) {
        WorkspaceId workspaceId = WorkspaceId.of(owner, project);
        // 要项目根，不是工作区根：界面展示的是项目里有什么。见 ProjectLayout。
        return ProjectLayout.rootOf(WorkspaceLookup.requireOnDisk(workspaces, workspaceId));
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
     * <p>用 {@code NOFOLLOW_LINKS} 判断类型：符号链接一律当文件，不展开、不读大小。
     * 那两件事都会跟着链接走到工作区外面，而列目录不经过 {@code WorkspacePathGuard}。
     * 链接本身仍列得出来，点开读内容时守卫会拦。
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
     * 字节转文本。
     *
     * <p>固定按 UTF-8 解，和 agent 的 {@code read_file} 同一个假设：前端显示的与模型
     * 看到的必须是同一份。截断时把尾巴退到最后一个换行；没有换行可退时退到最后一个
     * 完整字符，避免多字节字符被从中间切开而在末尾留下 U+FFFD。
     */
    private static String decodeText(byte[] bytes, boolean truncated) {
        int end = bytes.length;
        if (truncated) {
            int lastNewline = lastIndexOf(bytes);
            end = lastNewline > 0 ? lastNewline + 1 : utf8Boundary(bytes);
            bytes = Arrays.copyOf(bytes, end);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 没有换行可退时，退到最后一个完整字符的边界。
     *
     * <p>截断上限是字节数，正好落在多字节字符中间时，硬解会得到 U+FFFD；退删掉这半个
     * 字符即可。
     */
    private static int utf8Boundary(byte[] bytes) {
        int end = bytes.length;
        // 从尾部往回跳过至多 3 个续字节（10xxxxxx）
        int continuation = 0;
        while (end > 0 && continuation < 3 && (bytes[end - 1] & 0xC0) == 0x80) {
            end--;
            continuation++;
        }
        if (end == 0) {
            return 0;
        }
        int lead = bytes[end - 1] & 0xFF;
        int needed = lead < 0x80 ? 0 : lead >= 0xF0 ? 3 : lead >= 0xE0 ? 2 : lead >= 0xC0 ? 1 : 0;
        return needed > continuation ? end - 1 : end;
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
                ? "（项目根目录）"
                : relativePath;
    }
}
