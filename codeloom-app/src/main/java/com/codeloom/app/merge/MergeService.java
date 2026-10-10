package com.codeloom.app.merge;

import com.codeloom.agent.loop.VerificationPlan;
import com.codeloom.agent.loop.VerificationRunner;
import com.codeloom.app.project.ProjectLayout;
import com.codeloom.app.session.SessionCheckpoints;
import com.codeloom.app.turn.SessionWriter;
import com.codeloom.domain.event.VerificationResult;
import com.codeloom.domain.port.CancellationToken;
import com.codeloom.domain.port.CommandExecutor;
import com.codeloom.domain.port.ExecutionLease;
import com.codeloom.domain.port.LeaseToken;
import com.codeloom.domain.port.MergeResult;
import com.codeloom.domain.port.WorkspaceRepository;
import com.codeloom.domain.project.Project;
import com.codeloom.domain.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 合并这条会话的产出，**并在合并落地之后验证主干**。
 *
 * <h2>为什么合并也要验证</h2>
 * 单个 agent 的每一轮都有强制验证（改了文件就跑构建、失败回灌模型自修）。但**合并是唯一
 * 一个"代码变了却没有验证兜底"的路径** —— 而它恰恰是最容易出问题的一处：
 *
 * <pre>
 *   A 把方法名改成 printHi
 *   B 把调用点也改成 printHi（但改在另一个位置）
 *   → 三方合并两边不重叠，干净通过
 *   → 编译不过
 * </pre>
 *
 * 两个改动各自都是对的，合起来是错的，而 git 检测不到。所以"主干现在是好的"
 * 这句话必须有证据。
 *
 * <h2>为什么构建在事务之外跑</h2>
 * {@link ProjectMerger} 里那几步要持着项目行的排它锁；而一次 {@code mvn test}
 * 可能几分钟。两者同一个事务的话，那把锁被按住整场构建。所以这里只把**带锁的那一步**
 * 包成短事务，构建在外面跑。
 *
 * <p>代价是：构建期间另一次合并可能改了主干，于是这次验证的结论属于**那个 merge commit**
 * 而不是"主干现在"。所以 {@code callId} 里带着被验证的 sha（{@code merge-verify:<sha>}），
 * 事后看得出来验的是哪一份。
 *
 * <h2>为什么验证没过不回退合并</h2>
 * 因为回退本身也是一个改动主干的动作，而它该由人决定 —— 和冲突一律升级给人是同一条原则。
 * 我们能做的是把结论**响亮地**说出来：响应里带 {@code verification.passed=false}，
 * 事件流里留下证据。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MergeService {

    private static final Logger log = LoggerFactory.getLogger(MergeService.class);

    private final ProjectMerger merger;
    private final ExecutionLease leases;
    private final CommandExecutor commands;
    private final SessionCheckpoints checkpoints;
    private final SessionWriter writer;
    private final SessionSyncService syncs;
    private final WorkspaceRepository workspaces;

    public MergeService(ProjectMerger merger,
                        ExecutionLease leases,
                        CommandExecutor commands,
                        SessionCheckpoints checkpoints,
                        SessionWriter writer,
                        SessionSyncService syncs,
                        WorkspaceRepository workspaces) {
        this.merger = merger;
        this.leases = leases;
        this.commands = commands;
        this.checkpoints = checkpoints;
        this.writer = writer;
        this.syncs = syncs;
        this.workspaces = workspaces;
    }

    /**
     * 把这条会话的产出合进主干，成功的话接着验证主干。
     *
     * @param toCommitSha 合到哪个点。**null/空 = 合到分支的最新**（这条会话的全部产出）；
     *                    给了就必须是这条会话打过的一个 checkpoint —— 见 {@link #sourceRefOf}
     */
    public MergeOutcome mergeIntoMain(Session session, Project project, String toCommitSha) {
        // 分支名来自**工作区那一行**（分支是树的属性，不是会话的）。
        // 读库里那一份而不是现读 git：合之前要拿它做两次字符串比较，
        // 而库里的值和事件是同一个事务的产物，不会在中间飘
        String branch = workspaces.require(session.workspaceId()).branch();
        String sourceRef = sourceRefOf(session, branch, toCommitSha);
        LeaseToken token = acquire(session);
        try {
            // ① **先同步**。见 SessionSyncService：把主干拉进这棵树的工作区，
            //    于是冲突（如果有）发生在**树的工作区**里 —— 主干保持干净。
            //
            //    只在"合到分支最新"时做。合到某个旧 checkpoint 那种用法里，
            //    同步帮不上忙：那个点的内容是固定的，主干前进与否都不改变它
            boolean mergingTheBranchTip = sourceRef.equals(branch);
            if (mergingTheBranchTip) {
                syncs.sync(session, token);
            }

            // ② 合回。锁里会再查一次"是不是又落后了" —— 那一段窗口只有毫秒级，
            //    真撞上就拒绝（StaleBranchException），而不是让冲突落在主干上
            MergeResult result = merger.mergeIntoMain(session, project, sourceRef, mergingTheBranchTip);
            if (result.status() == MergeResult.Status.CONFLICT) {
                // 冲突时**不验证**：主干现在停在合并中途、工作区里带着冲突标记，
                // 拿那个状态去构建，报出来的错是"冲突标记语法错误" —— 那是噪音，不是信号。
                // 于是这里走异常路径：客户端要据此进入裁决流程，而且它需要那份冲突清单
                // 路径翻成**项目的**再给客户端 —— 它看见的、要拿去裁决的都是
                // 项目里的名字，不是 git 仓库里的名字。见 ProjectLayout.toProjectPath
                throw new ConflictPendingException(result.conflictingPaths().stream()
                        .map(ProjectLayout::toProjectPath)
                        .filter(Objects::nonNull)
                        .toList());
            }
            return MergeOutcome.of(result.status().name(), result.headCommit(),
                    verify(session, project, token, result.headCommit()), List.of());
        } finally {
            leases.release(token);
        }
    }

    /**
     * 这次合并到底合什么。
     *
     * <p><strong>为什么 sha 要过一遍 {@code SessionCheckpoints}</strong>：不过的话，
     * 客户端可以拿任意一个 sha 过来 —— 而 {@code git merge <sha>} 是会把**那个提交及其全部
     * 祖先**搬进主干的。也就是说少了这道校验，等于让调用方决定往主干里塞哪段历史。
     * 限定成"这条会话自己打过的点"之后，能合的就只有它自己的产出。
     */
    private String sourceRefOf(Session session, String branch, String toCommitSha) {
        if (toCommitSha == null || toCommitSha.isBlank()) {
            return branch;
        }
        return checkpoints.require(session, toCommitSha).commitSha();
    }

    public ConflictsView conflicts(Session session, Project project) {
        // 只读，不需要会话租约；但它要读**主干**的状态，所以项目锁还是要的（在 merger 里面）
        return merger.conflicts(session, project);
    }

    /** 裁决一个冲突；最后一个裁完之后合并落地，于是也验证一次。 */
    public MergeOutcome resolve(Session session, Project project, String path,
                                ConflictResolution resolution) {
        LeaseToken token = acquire(session);
        try {
            ProjectMerger.Resolution outcome = merger.resolve(session, project, path, resolution);
            if (!outcome.merged()) {
                return MergeOutcome.of("CONFLICT_PENDING", null, null, outcome.remaining());
            }
            if (outcome.inSessionWorktree()) {
                // 收尾的是一次**同步**：它推进的是这棵树的 HEAD，所以 head_commit 必须跟着更新 ——
                // 事件和列在同一个事务里（SessionWriter 是那个"唯一实现点"）。
                // from 取列的旧值：同步开始时因为冲突没能落事件，所以列还停在原处，正好是我们要的
                writer.sync(session, workspaces.require(session.workspaceId()).headCommit(),
                        outcome.mergeCommitSha(), token);
                // 注意这里**不验证主干** —— 同步动的是这棵树的工作区，主干压根没被碰过。
                // 拿"这棵树的产出"去报"主干现在好不好"不成立
                return MergeOutcome.of("MERGED", outcome.mergeCommitSha(), null, List.of());
            }
            // 主干上的合并落地了 —— 该验证了
            return MergeOutcome.of("MERGED", outcome.mergeCommitSha(),
                    verify(session, project, token, outcome.mergeCommitSha()), List.of());
        } finally {
            leases.release(token);
        }
    }

    public void abort(Session session, Project project) {
        merger.abort(session, project);
    }

    // ------------------------------------------------------------------

    /**
     * 合并期间持有这棵树的租约。两个作用：
     * <ol>
     *   <li>别让**正在跑一轮**的会话被合进去 —— 那一轮结束前它的分支还会变。
     *       注意"正在跑"可能是同一个人的另一条会话：它们共用一棵树；
     *   <li>验证结论要落成事件，而写事件必须带 token ——
     *       事实来源上没有"这条不用记账"的例外。
     * </ol>
     */
    private LeaseToken acquire(Session session) {
        return leases.tryAcquire(session).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完再合并"));
    }

    /**
     * 在主干上跑一次验证，把结论落成事件。
     *
     * @param verifiedCommit 这次验证的是哪个提交。它进 {@code callId}（{@code merge-verify:<sha>}）——
     *                       构建期间主干可能被另一次合并改掉，那个 sha 是事后分辨"验的是哪一份"的唯一线索
     * @return 验证结论；**没有可认的构建文件时为 null** —— 那时候不做验证，
     *         而不是硬跑一条可能错的命令（那会让人去修一个根本不存在的问题）
     */
    private VerificationResult verify(Session session, Project project, LeaseToken token,
                                      String verifiedCommit) {
        Path main = Path.of(project.repoPath());
        // 构建文件在**项目根**里，不在仓库根 —— 拿仓库根去探会什么都找不到，
        // 于是"合并后不验证"变成一条静默的常态。见 ProjectLayout
        Path projectRoot = ProjectLayout.rootBelow(main);
        Optional<VerificationPlan> detected = VerificationPlan.detect(projectRoot);
        if (detected.isEmpty()) {
            return null;
        }
        VerificationPlan plan = detected.get();
        String callId = "merge-verify:" + shortSha(verifiedCommit);

        // 跑验证这件事收在 VerificationRunner 里（和一轮结束之后那条路径是同一个实现）——
        // 输出上限、截断方式、"命令没跑起来"的说法都只定义一次
        VerificationResult evidence = VerificationRunner.run(plan, callId, projectRoot, commands,
                CancellationToken.none()).evidence();
        writer.append(session, evidence, token);

        if (!evidence.passed()) {
            // 合并**已经成功了**，只是结果不可信。这条日志是给运维看的；
            // 调用方要看的是响应里那个 verification —— 我们**不自动回退合并**，
            // 因为回退本身也是一个改动主干的动作，该由人决定
            log.warn("合并到主干之后验证未通过：会话 {} 命令 {} 提交 {}",
                    session.id(), plan.commandLine(), verifiedCommit);
        }
        return evidence;
    }

    /** 短 sha，用于把验证结论和具体的提交绑在一起。 */
    private static String shortSha(String sha) {
        if (sha == null) {
            return "unknown";
        }
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }
}
