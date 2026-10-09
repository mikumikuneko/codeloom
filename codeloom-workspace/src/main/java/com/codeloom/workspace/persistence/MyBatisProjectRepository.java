package com.codeloom.workspace.persistence;

import com.codeloom.domain.project.Project;
import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.port.ProjectRepository;
import com.codeloom.domain.user.UserId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ProjectRepository} 的持久化实现。
 *
 * <p>麻烦的地方全在成员上：{@code Project} 是一个带 {@code Set<UserId>} 的聚合，
 * 而数据库里它是两张表。所以每个读方法都是「查项目行 → 一次批量查成员 → 拼回聚合」，
 * 批量查那步是为了不出现「按项目逐个查成员」的 N+1。
 */
@Repository
public class MyBatisProjectRepository implements ProjectRepository {

    private final ProjectMapper mapper;

    public MyBatisProjectRepository(ProjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 保存项目本身与它的成员，同一个事务。
     *
     * <p>成员同步用「先全删再全插」，而不是算差集。理由很实在：
     * {@code project_member} 只有 {@code (project_id, user_id)} 两列，删掉不会丢任何
     * 别的东西；而成员上限是 {@code Project.MAX_MEMBERS} = 2，整轮操作就是几行。
     * 算差集能省下的那点写，不值得多一段「谁被删了、谁被加了」的逻辑 ——
     * 那是一段只要写错就会静默丢成员、而丢成员又不会被立刻发现的东西。
     */
    @Override
    @Transactional
    public void save(Project project) {
        mapper.save(ProjectRow.of(project));
        mapper.deleteMembers(project.id().value());
        for (UserId member : project.members()) {
            mapper.insertMember(project.id().value(), member.value());
        }
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
        return Optional.ofNullable(mapper.findById(id.value()))
                .map(row -> assemble(List.of(row)).getFirst());
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>先成员后项目。</strong>这个项目里没有外键也没有级联（见 schema.sql 开头
     * 那条约定），顺序写反的话会留下一堆"没有项目的成员行" —— 而那种行在任何一条查询里
     * 都不会出现（每条查成员的路都先要知道项目 id），只会慢慢堆积。
     */
    @Override
    @Transactional
    public void delete(ProjectId id) {
        mapper.deleteMembers(id.value());
        mapper.delete(id.value());
    }

    @Override
    public List<Project> findAll() {
        return assemble(mapper.findAll());
    }

    @Override
    public List<Project> findByMember(UserId userId, int limit, int offset) {
        return assemble(mapper.findByMember(userId.value(), limit, offset));
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code MANDATORY} 是在**强制**那条"调用方必须有事务"的要求：
     * 没有事务时它直接抛 {@code IllegalTransactionStateException}，
     * 而不是自己开一个 —— 后者会让这次加锁读完就释放，然后调用方在毫无互斥的情况下
     * 去改主干，而且一切看起来正常。这和 {@code MyBatisWorkspaceFence} 是同一个套路。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(ProjectId id) {
        if (mapper.lockById(id.value()) == null) {
            throw new IllegalStateException("要加锁的项目不存在：" + id);
        }
    }

    /**
     * 项目行 + 成员 → 聚合。三个读方法共用。
     *
     * <p>成员缺失时传空集，让 {@code Project} 的紧凑构造器抛「项目至少要有一名成员」。
     * 不在这里兜底成「一个没有成员的项目」：那说明写入路径漏了，静默容忍只会让它
     * 一直错下去。
     */
    private List<Project> assemble(List<ProjectRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Set<UserId>> members = membersOf(rows.stream().map(ProjectRow::id).toList());
        return rows.stream()
                .map(row -> row.toDomain(members.getOrDefault(row.id(), Set.of())))
                .toList();
    }

    /** 一次查出这些项目的全部成员，按项目分组。用 {@code LinkedHashSet} 保证顺序稳定。 */
    private Map<String, Set<UserId>> membersOf(Collection<String> projectIds) {
        Map<String, Set<UserId>> grouped = new HashMap<>();
        for (ProjectMemberRow row : mapper.findMembers(projectIds)) {
            grouped.computeIfAbsent(row.projectId(), id -> new LinkedHashSet<>())
                    .add(UserId.of(row.userId()));
        }
        return grouped;
    }
}
