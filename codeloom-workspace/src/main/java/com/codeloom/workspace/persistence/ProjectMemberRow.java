package com.codeloom.workspace.persistence;

/**
 * {@code project_member} 表的一行。
 *
 * <p>只有两列，但值得单独一个类型：批量查成员时（比如「我的项目」列表）需要把结果按
 * {@code projectId} 分组，而只返回 {@code List<String>} 会把归属关系丢掉。
 */
public record ProjectMemberRow(String projectId, String userId) {
}
