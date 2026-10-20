package com.codeloom.workspace.persistence;

/**
 * {@code project_member} 表的一行。
 *
 * <p>批量查成员时需要按 {@code projectId} 分组；只返回用户 id 会丢掉归属关系。
 */
public record ProjectMemberRow(String projectId, String userId) {
}
