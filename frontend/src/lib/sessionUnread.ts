/**
 * 观战标签上的未读点：对方**新开了**会话，而我没在看观战栏。
 *
 * <p>状态由调用方持有（一个 {@link Set}），这里只算"下一步该是什么" —— 纯函数，
 * 能单独测。判据只有两条：
 *
 * <ul>
 *   <li>第一次拿到列表时，**已有的那些都算看过** —— 否则每次进项目都会凭空多一个点；</li>
 *   <li>正在看观战栏时，新出现的也算看过 —— 人就在那儿，不需要再提示。</li>
 * </ul>
 *
 * <p>被删掉的会话要从"看过"里剔掉：同一条 id 不会再回来，留着只会让集合越积越大。
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
