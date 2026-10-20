import { describe, expect, it } from 'vitest'

import { trackTheirSessions } from './sessionUnread'

describe('trackTheirSessions', () => {
  it('第一次拿到列表：已有的都算看过，不点未读', () => {
    const result = trackTheirSessions(null, ['a', 'b'], false)
    expect(result.unread).toBe(false)
    expect([...result.seen].sort()).toEqual(['a', 'b'])
  })

  it('没在看观战时出现的会话算未读', () => {
    const first = trackTheirSessions(null, ['a'], false)
    const next = trackTheirSessions(first.seen, ['a', 'b'], false)
    expect(next.unread).toBe(true)
    // 未读的 id 不直接并进看过，否则下一次刷新就把它忘了。
    expect([...next.seen]).toEqual(['a'])
  })

  it('正在看观战时出现的会话直接算看过', () => {
    const first = trackTheirSessions(null, ['a'], false)
    const next = trackTheirSessions(first.seen, ['a', 'b'], true)
    expect(next.unread).toBe(false)
    expect([...next.seen].sort()).toEqual(['a', 'b'])
  })

  it('删掉的会话从看过里剔掉，不会越积越多', () => {
    const first = trackTheirSessions(null, ['a', 'b'], false)
    const next = trackTheirSessions(first.seen, ['a'], false)
    expect(next.unread).toBe(false)
    expect([...next.seen]).toEqual(['a'])
  })

  it('从没看过到看观战：把当前所有会话一次性收下', () => {
    const first = trackTheirSessions(null, ['a'], false)
    const unseen = trackTheirSessions(first.seen, ['a', 'b'], false)
    expect(unseen.unread).toBe(true)
    const watching = trackTheirSessions(unseen.seen, ['a', 'b'], true)
    expect(watching.unread).toBe(false)
    expect([...watching.seen].sort()).toEqual(['a', 'b'])
  })
})
