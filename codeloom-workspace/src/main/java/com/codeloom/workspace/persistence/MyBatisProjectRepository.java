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
 * <p>{@link Project} 是带成员集合的聚合，数据库里是两张表；读方法都按"查项目行 →
 * 一次批量查成员 → 拼回聚合"进行，避免按项目逐个查成员的 N+1。
 */
@Repository
public class MyBatisProjectRepository implements ProjectRepository {

    private final ProjectMapper mapper;

    public MyBatisProjectRepository(ProjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 保存项目与成员，同一事务。
     *
     * <p>成员采用"先全删再全插"：表只有两列，成员上限是
     * {@code Project.MAX_MEMBERS} = 2，整轮只有几行；差集逻辑一旦写错会静默丢成员。
     *
     * @param project 要保存的项目
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
     * <p>先删成员行再删项目行。这里没有外键与级联（见 schema.sql 开头的约定），顺序
     * 写反会留下无法被任何查询看到的孤儿成员行。
     */
    @Override
    @Transactional
    public void delete(ProjectId id) {
        mapper.deleteMembers(id.value());
        mapper.delete(id.value());
    }

    @Override
    public List<Project> findByMember(UserId userId, int limit, int offset) {
        return assemble(mapper.findByMember(userId.value(), limit, offset));
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code MANDATORY} 强制调用方提供事务：没有事务时直接抛出，而不是自己开一个
     * 读锁旋即释放的事务，否则调用方会在无互斥的情况下修改主干。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(ProjectId id) {
        if (mapper.lockById(id.value()) == null) {
            throw new IllegalStateException("要加锁的项目不存在：" + id);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>项目行和成员行都用锁定读：先 {@code lock} 再 {@link #findById} 会读到事务
     * 开始时的快照，锁拿到了而成员还是旧的。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Project> lockAndFind(ProjectId id) {
        ProjectRow row = mapper.findByIdForUpdate(id.value());
        if (row == null) {
            return Optional.empty();
        }
        Set<UserId> members = new LinkedHashSet<>();
        for (ProjectMemberRow member : mapper.findMembersForUpdate(id.value())) {
            members.add(UserId.of(member.userId()));
        }
        return Optional.of(row.toDomain(members));
    }

    /**
     * 项目行与成员拼成聚合，两个读方法共用；{@code lockAndFind} 需要自己加锁查成员，
     * 不走这里。
     *
     * <p>成员缺失时传空集，让 {@link Project} 的构造器抛出"项目至少要有一名成员"；
     * 那说明写入路径漏了，不在这里静默兜底。
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

    /** 一次查出这些项目的全部成员并按项目分组；成员集合保持稳定顺序。 */
    private Map<String, Set<UserId>> membersOf(Collection<String> projectIds) {
        Map<String, Set<UserId>> grouped = new HashMap<>();
        for (ProjectMemberRow row : mapper.findMembers(projectIds)) {
            grouped.computeIfAbsent(row.projectId(), id -> new LinkedHashSet<>())
                    .add(UserId.of(row.userId()));
        }
        return grouped;
    }
}
