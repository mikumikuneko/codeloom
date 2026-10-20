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
 * 把一条会话的产出合进主干，合并落地后在主干上跑一次验证。
 *
 * <p>合并是唯一一条"代码变了却没有验证兜底"的路径：两个各自正确的改动合起来可能编译
 * 不过（一个改方法名，另一个在别处改调用点），而 git 检测不到。验证结论写进事件流，
 * 也随响应返回；验证失败不回退合并 —— 回退同样是改动主干，必须由人决定。
 *
 * <p>带项目行锁的那一步在 {@link ProjectMerger} 里，是短事务；构建在事务外跑，否则锁会被
 * 按住整场构建。构建期间主干可能被另一次合并推进，所以 {@code callId} 带被验证的提交
 * （{@code merge-verify:SHA}）。
 *
 * <p>理由见 {@code docs/decisions/architecture/2026-09-25-merge-model-without-pull-requests.md}。
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
     * <p>有冲突时抛 {@link ConflictPendingException}，其中的路径已经翻成项目路径；
     * 此时主干停在未完成的合并里，冲突裁决端点在收尾前不会被再次合并。
     *
     * @param toCommitSha 合到哪个点。null 或空白表示合到分支的最新（这条会话的全部产出）；
     *                    给了就必须是这条会话打过的一个 checkpoint（见 {@link #sourceRefOf}）
     * @return 合并状态、合并提交、主干验证结论；验证结论在无构建文件时为 null
     * @throws ConflictPendingException 合并有冲突，需要人工裁决
     */
    public MergeOutcome mergeIntoMain(Session session, Project project, String toCommitSha) {
        // 分支是树的属性，不是会话的；读库里的值而非现读 git，因为下面要拿它做比较，
        // 而库里的值与事件在同一事务里产生，不会在中途变化
        String branch = workspaces.require(session.workspaceId()).branch();
        String sourceRef = sourceRefOf(session, branch, toCommitSha);
        LeaseToken token = acquire(session);
        try {
            // 先同步，让冲突落在树的工作区里、主干保持干净。只在合到分支最新时做：
            // 合到旧 checkpoint 时那个点的内容是固定的，同步不改变它
            boolean mergingTheBranchTip = sourceRef.equals(branch);
            if (mergingTheBranchTip) {
                syncs.sync(session, token);
            }

            // 锁内会再查一次是否落后；那个窗口只有毫秒级，撞上就抛 StaleBranchException，
            // 不让冲突落在主干上
            MergeResult result = merger.mergeIntoMain(session, project, sourceRef, mergingTheBranchTip);
            if (result.status() == MergeResult.Status.CONFLICT) {
                // 冲突时不验证：主干停在合并中途，构建只会报冲突标记的语法错误。
                // 冲突路径翻成项目路径再交给客户端，裁决用的都是项目里的名字
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
     * 本次合并的源 ref。
     *
     * <p>sha 必须经过 {@link SessionCheckpoints}：{@code git merge SHA} 会把该提交及其全部
     * 祖先搬进主干，不校验等于让调用方决定往主干里写哪段历史。限定为这条会话自己打过的
     * checkpoint 后，能合的只有它自己的产出。
     */
    private String sourceRefOf(Session session, String branch, String toCommitSha) {
        if (toCommitSha == null || toCommitSha.isBlank()) {
            return branch;
        }
        return checkpoints.require(session, toCommitSha).commitSha();
    }

    /**
     * 当前待裁决的冲突与两侧内容；没有待裁决的合并时返回空信封。
     *
     * @param session 发起合并的会话
     * @param project 会话所属项目
     * @return 冲突信封；没有待裁决的合并时方向为 null、列表为空
     */
    public ConflictsView conflicts(Session session, Project project) {
        // 只读，不需要执行租约；但要读主干的状态，项目锁仍然要（在 merger 里）
        return merger.conflicts(session, project);
    }

    /**
     * 裁决一个冲突；最后一个裁完后合并落地，于是也验证一次。
     *
     * @param resolution 取一侧，或人给出的全文
     * @return 尚未裁完时状态为 {@code CONFLICT_PENDING} 并带剩余文件；
     *         收尾的是同步时 {@code verification} 为 null
     */
    public MergeOutcome resolve(Session session, Project project, String path,
                                ConflictResolution resolution) {
        LeaseToken token = acquire(session);
        try {
            ProjectMerger.Resolution outcome = merger.resolve(session, project, path, resolution);
            if (!outcome.merged()) {
                return MergeOutcome.of("CONFLICT_PENDING", null, null, outcome.remaining());
            }
            if (outcome.inSessionWorktree()) {
                // 收尾的是一次同步，推进的是这棵树的 HEAD，所以 head_commit 必须跟着更新；
                // 事件和列在同一个事务里写。from 取列的旧值：同步开始时因冲突没落事件，列仍在原处
                writer.sync(session, workspaces.require(session.workspaceId()).headCommit(),
                        outcome.mergeCommitSha(), token);
                // 不验证主干：同步只动这棵树的工作区，主干没被碰过
                return MergeOutcome.of("MERGED", outcome.mergeCommitSha(), null, List.of());
            }
            return MergeOutcome.of("MERGED", outcome.mergeCommitSha(),
                    verify(session, project, token, outcome.mergeCommitSha()), List.of());
        } finally {
            leases.release(token);
        }
    }

    /** 放弃当前未完成的合并，工作区恢复到合并前。 */
    public void abort(Session session, Project project) {
        merger.abort(session, project);
    }

    // ------------------------------------------------------------------

    /**
     * 合并期间持有这棵树的租约：既防止正在跑一轮的会话被合进去（那一轮结束前它的分支还会变），
     * 也让验证结论能带 token 写进事件流。同一人的其他会话共用一棵树，同样会被挡住。
     *
     * @throws ResponseStatusException 409，这条会话正在执行中
     */
    private LeaseToken acquire(Session session) {
        return leases.tryAcquire(session).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "这条会话正在执行中，等它跑完再合并"));
    }

    /**
     * 在主干上跑一次验证，把结论落成事件。
     *
     * @param verifiedCommit 这次验证的提交；它进 {@code callId}（{@code merge-verify:SHA}），
     *                       用于在主干被另一次合并推进后分辨验的是哪一份
     * @return 验证结论；没有可识别的构建文件时为 null，此时不跑任何命令
     */
    private VerificationResult verify(Session session, Project project, LeaseToken token,
                                      String verifiedCommit) {
        Path main = Path.of(project.repoPath());
        // 构建文件在项目根里，不在仓库根；用仓库根去探会什么都找不到，合并后验证就静默消失。
        // 见 ProjectLayout
        Path projectRoot = ProjectLayout.rootBelow(main);
        Optional<VerificationPlan> detected = VerificationPlan.detect(projectRoot);
        if (detected.isEmpty()) {
            return null;
        }
        VerificationPlan plan = detected.get();
        String callId = "merge-verify:" + shortSha(verifiedCommit);

        // 与一轮结束后的验证共用 VerificationRunner：输出上限、截断方式、
        // "命令没跑起来"的表述只定义一次
        VerificationResult evidence = VerificationRunner.run(plan, callId, projectRoot, commands,
                CancellationToken.none()).evidence();
        writer.append(session, evidence, token);

        if (!evidence.passed()) {
            // 合并已经成功，只是结果不可信；不回退合并，因为回退也是改动主干、该由人决定
            log.warn("合并到主干之后验证未通过：会话 {} 命令 {} 提交 {}",
                    session.id(), plan.commandLine(), verifiedCommit);
        }
        return evidence;
    }

    private static String shortSha(String sha) {
        if (sha == null) {
            return "unknown";
        }
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }
}
