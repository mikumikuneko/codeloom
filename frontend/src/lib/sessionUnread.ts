/**
 * 观战标签上的未读点：对方新开了会话，而我没在看观战栏。
 *
 * 状态由调用方持有（一个 `Set`），这里只算下一步该是什么，是纯函数。判据两条：
 * 第一次拿到列表时已有的都算看过，否则每次进项目都会凭空多一个点；正在看观战栏
 * 时新出现的也算看过，人就在那儿。
 *
 * 被删掉的会话要从"看过"里剔掉：同一条 id 不会再回来，留着只会让集合越积越大。
 */
export function trackTheirSessions(
  seen: ReadonlySet<string> | null,
  ids: readonly string[],
  watching: boolean,
): { seen: Set<string>; unread: boolean } {
  if (seen === null) {
    return { seen: new Set(ids), unread: false }
  }

  const next = new Set<string>()
  for (const id of ids) {
    if (seen.has(id)) next.add(id)
  }

  if (watching) {
    for (const id of ids) next.add(id)
    return { seen: next, unread: false }
  }

  return { seen: next, unread: ids.some((id) => !seen.has(id)) }
}
