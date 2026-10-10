import { describe, expect, it } from 'vitest'

import {
  EMPTY_STREAMING,
  changeTotals,
  changesByTurnIndex,
  turnChanges,
  fold,
  foldStreaming,
  groupIntoTurns,
  toolIsRunning,
  turnIsActive,
  turnStateOf,
  workspaceWrites,
  type Frame,
  type StreamItem,
  type ToolOutcome,
} from './sessionStream'

/**
 * 这一套钉的是 `sessionStream` 注释里**已经写明**的那些契约。
 *
 * <p>为什么是它：出过真 bug 的**只有这批纯投影**（被拒绝的调用一直闪、回滚退错地方、
 * 批准挂起冒用量行、这一轮在不在跑判错），而它们的失效方式都是"折到某个分支上不对"——
 * 截图够不到那些分支（要构造"拒绝 / 中断 / 回滚 / 批准续跑"的状态）。
 */

/** 帧的序号由后端发，这里只要递增；**易失的增量没有 seq**。 */
let at = 0
const event = (type: string, payload: Record<string, unknown> = {}): Frame => ({
  seq: ++at,
  type,
  payload,
})
const delta = (type: string, payload: Record<string, unknown>): Frame => ({ type, payload })

const state = (to: string, reason = '') => event('SESSION_STATE_CHANGED', { to, reason })
const ask = (callId = 'c1') =>
  event('TOOL_CALL_REQUESTED', { callId, toolName: 'run_command', argumentsJson: '{}' })
const done = (callId = 'c1', mutated = false) =>
  event('TOOL_RESULT', {
    callId,
    success: true,
    output: 'ok',
    truncated: false,
    exitCode: 0,
    mutated,
  })

const stream = (frames: Frame[]): StreamItem[] => frames.reduce(fold, [] as StreamItem[])
const toolsOf = (items: StreamItem[]) => items.flatMap((i) => (i.kind === 'tool' ? [i] : []))
const noticesOf = (items: StreamItem[]) => items.flatMap((i) => (i.kind === 'notice' ? [i.text] : []))

describe('toolIsRunning', () => {
  const outcome: ToolOutcome = {
    success: true,
    output: '',
    truncated: false,
    exitCode: 0,
    mutated: false,
  }

  it('既没有结果、也没有「没跑完」才算还在跑', () => {
    expect(toolIsRunning(null, null)).toBe(true)
  })

  it('有结果就不算', () => {
    expect(toolIsRunning(outcome, null)).toBe(false)
  })

  it('**后半个不能省**：被取消/被中断的那次调用也没有结果', () => {
    // 少了它，一轮早停了、最后一个工具还挂着，界面就一直以为它在跑（一直闪）
    expect(toolIsRunning(null, 'cancelled')).toBe(false)
    expect(toolIsRunning(null, 'interrupted')).toBe(false)
    expect(toolIsRunning(null, 'rejected')).toBe(false)
  })
})

describe('这一轮在不在跑：看状态，不看条目的形状', () => {
  it('在想 / 在跑工具 → 在跑', () => {
    expect(turnIsActive(stream([event('USER_MESSAGE', { text: '跑' }), state('THINKING')]))).toBe(true)
    expect(turnIsActive(stream([ask(), state('EXECUTING_TOOL')]))).toBe(true)
  })

  it('工具跑完、接着想 → 还在跑（旧判据在这一格说「没在跑」）', () => {
    expect(turnIsActive(stream([ask(), state('EXECUTING_TOOL'), done(), state('THINKING')]))).toBe(true)
  })

  it('等人批 / 收尾 / 失败 / 一轮都没跑 → 不在跑', () => {
    expect(turnIsActive(stream([ask(), state('AWAITING_APPROVAL')]))).toBe(false)
    expect(turnIsActive(stream([state('WAITING_USER')]))).toBe(false)
    expect(turnIsActive(stream([state('FAILED')]))).toBe(false)
    // 失败重试那两步之间那一瞬（FAILED → IDLE → THINKING）也不算
    expect(turnIsActive(stream([state('IDLE')]))).toBe(false)
    expect(turnIsActive(stream([event('CHECKPOINT_CREATED', { commitSha: 'a', turnIndex: 0 })]))).toBe(
      false,
    )
  })

  it('认不出的状态当「没在跑」—— 后端先上了新状态时，这一档只让按钮不亮一下', () => {
    expect(turnIsActive(stream([state('SOME_NEW_STATE')]))).toBe(false)
  })

  it('turnStateOf 取的是**最后**那条状态', () => {
    expect(turnStateOf(stream([state('THINKING'), state('EXECUTING_TOOL')]))).toBe('EXECUTING_TOOL')
  })
})

describe('流式缓冲什么时候清', () => {
  const grown = foldStreaming(EMPTY_STREAMING, delta('ASSISTANT_DELTA', { text: '半句' }))

  it('增量攒着，正文和思考各一路', () => {
    expect(grown.text).toBe('半句')
    expect(foldStreaming(grown, delta('REASONING_DELTA', { text: '想' })).reasoning).toBe('想')
  })

  it('完整回复到了就清空', () => {
    expect(foldStreaming(grown, event('ASSISTANT_MESSAGE', { text: '半句' }))).toEqual(EMPTY_STREAMING)
  })

  it('**一轮收了尾也清** —— 被打断的那一轮永远不会有完整回复', () => {
    for (const to of ['WAITING_USER', 'AWAITING_APPROVAL', 'FAILED']) {
      expect(foldStreaming(grown, state(to))).toEqual(EMPTY_STREAMING)
    }
  })

  it('还在跑的那些状态**不清** —— 清了的话正吐着的那半句会消失', () => {
    expect(foldStreaming(grown, state('EXECUTING_TOOL')).text).toBe('半句')
    expect(foldStreaming(grown, state('THINKING')).text).toBe('半句')
    // 失败重试那两步之间的 IDLE 也不清：那一轮还要接着跑
    expect(foldStreaming(grown, state('IDLE')).text).toBe('半句')
  })
})

describe('状态变化进流', () => {
  it('每一支都落一条 state 条目 —— 界面靠它判状态，不靠数别的迹象', () => {
    for (const [to, reason] of [
      ['THINKING', ''],
      ['EXECUTING_TOOL', ''],
      ['AWAITING_APPROVAL', ''],
      ['WAITING_USER', 'CANCELLED'],
      ['WAITING_USER', 'REJECTED'],
      ['FAILED', 'boom'],
    ] as const) {
      const items = stream([ask(), state(to, reason)])
      expect(items.some((item) => item.kind === 'state' && item.to === to)).toBe(true)
    }
  })

  it('等人批**不写小字**：那张卡片自己会说，再来一条就出现两遍', () => {
    expect(noticesOf(stream([ask(), state('AWAITING_APPROVAL')]))).toEqual([])
  })

  it('按 Esc 停和拒绝之后停，各说一句（都不是错误）', () => {
    expect(noticesOf(stream([state('WAITING_USER', 'CANCELLED')]))).toEqual(['已打断 · 接下来要它做什么？'])
    expect(noticesOf(stream([state('WAITING_USER', 'REJECTED')]))).toEqual(['已停下 · 接下来要它做什么？'])
  })

  it('失败那一句带原因', () => {
    expect(noticesOf(stream([state('FAILED', 'boom')]))).toEqual(['这一轮失败了：boom'])
  })
})

describe('收尾时给「没有结局」的调用一个交代', () => {
  it('请求了却没有结果 → 标成没跑完，而不是让它一直闪', () => {
    const items = stream([ask('c1'), state('WAITING_USER')])

    expect(toolsOf(items)[0]?.outcome).toBeNull()
    expect(toolsOf(items)[0]?.unfinished).toBe('no-result')
  })

  it('被拒绝的那次调用有自己的结局，不会被再标一遍', () => {
    const items = stream([ask('c1'), event('TOOL_REJECTED', { callId: 'c1' }), state('WAITING_USER')])

    expect(toolsOf(items)[0]?.unfinished).toBe('rejected')
  })

  it('**等人批时一条都不扫**：那条调用还在等一个人，说它「没有结果」是错的', () => {
    const items = stream([ask('c1'), state('AWAITING_APPROVAL')])

    expect(toolsOf(items)[0]?.unfinished).toBeNull()
  })

  it('**批过的那条不扫**：它没跑完是因为马上要跑', () => {
    const items = stream([
      ask('c1'),
      event('TOOL_APPROVAL_REQUESTED', { callId: 'c1', reason: '要问你' }),
      event('TOOL_APPROVAL_RESOLVED', { callId: 'c1', approved: true, resolvedByUserId: 'u-li' }),
      state('WAITING_USER'),
    ])

    expect(toolsOf(items)[0]?.outcome).toBeNull()
    expect(toolsOf(items)[0]?.unfinished).toBeNull()
  })
})

describe('回滚', () => {
  const beforeRewind = () => [
    event('USER_MESSAGE', { text: '第一句' }),
    event('CHECKPOINT_CREATED', { commitSha: 'a', turnIndex: 1 }),
    event('USER_MESSAGE', { text: '第二句' }),
  ]

  it('按**序号**截断，并把退掉的那句话念出来', () => {
    const frames = beforeRewind()
    const cutoff = frames[1]!.seq as number
    const framesAfter = [...frames, event('SESSION_REWOUND', { toCheckpointSeq: cutoff, byUserId: 'u-li' })]

    const items = stream(framesAfter)

    expect(items.some((item) => item.kind === 'user' && item.text === '第二句')).toBe(false)
    expect(noticesOf(items)).toContain('回滚到「第二句」之前')
  })

  it('事件里**没记下退到哪儿**时不截断 —— 清空整段是最坏的选择', () => {
    const frames = [...beforeRewind(), event('SESSION_REWOUND', { byUserId: 'u-li' })]

    const items = stream(frames)

    expect(items.some((item) => item.kind === 'user' && item.text === '第二句')).toBe(true)
    expect(noticesOf(items)).toContain('回滚了这条会话')
  })
})

describe('这一轮改过工作区几次', () => {
  it('数的是后端报的那个事实（mutated），不是工具名', () => {
    // run_command 跑一条什么都没动的命令 —— 不该让左边的树重拉一次
    expect(workspaceWrites(stream([ask('c1'), done('c1', false)]))).toBe(0)
    expect(workspaceWrites(stream([ask('c1'), done('c1', true)]))).toBe(1)
  })

  it('退出码非零也算 —— 格式化器修完报错退出时，磁盘已经动过了', () => {
    const items = stream([
      ask('c1'),
      event('TOOL_RESULT', {
        callId: 'c1',
        success: false,
        output: 'boom',
        truncated: false,
        exitCode: 1,
        mutated: true,
      }),
    ])

    expect(workspaceWrites(items)).toBe(1)
  })
})

describe('分块与这一轮改了什么', () => {
  it('回滚标记不进分块（留着只会让开头多一个空块）', () => {
    const items = stream([
      event('CHECKPOINT_CREATED', { commitSha: 'a', turnIndex: 0 }),
      event('USER_MESSAGE', { text: '第一句' }),
    ])

    expect(groupIntoTurns(items)).toHaveLength(1)
  })

  it('一轮开始前落下的状态条目**不该另起一块** —— 它渲染出来是空的', () => {
    // 真实顺序就是这样：一轮先把状态推到 THINKING，**然后**才落用户那条消息
    //（见后端 TurnExecutor.runOnce）。所以每条会话的开头都有一条状态条目
    const items = stream([
      event('CHECKPOINT_CREATED', { commitSha: 'a', turnIndex: 0 }),
      state('THINKING'),
      event('USER_MESSAGE', { text: '第一句' }),
      event('ASSISTANT_MESSAGE', { text: '答', model: null, reasoning: null }),
    ])

    expect(groupIntoTurns(items)).toHaveLength(1)
  })

  it('一句用户消息都还没有时，开头那些自成一块 —— 不然它们没地方显示', () => {
    expect(groupIntoTurns(stream([event('SESSION_SYNCED', {})]))).toHaveLength(1)
    expect(groupIntoTurns([])).toHaveLength(0)
  })

  it('增删合计只数新增和删除的行', () => {
    expect(
      changeTotals([
        { path: 'a.ts', added: 3, deleted: 1, binary: false, created: false, commitSha: 'a' },
        { path: 'b.ts', added: 0, deleted: 2, binary: false, created: false, commitSha: 'a' },
      ]),
    ).toEqual({ added: 3, deleted: 3 })
  })
})

/**
 * 「这一轮改了什么」这条事件 —— 折叠那一行、点开看的 diff、回滚面板那两列都吃它。
 *
 * <p>那次这条链路上没写测试（前端当时还没有运行器），所以这里补上**能测的那一半**：
 * 从帧到这几个纯函数的推导。点一下屏幕才看得见的那些（折叠展开、点文件、三种关法）
 * 由真跑盯着。
 */
describe('这一轮改了什么', () => {
  const file = (path: string, added: number, deleted: number) => ({
    path,
    added,
    deleted,
    binary: false,
    created: false,
  })
  const changesFrame = (files: unknown, extra: Record<string, unknown> = {}) =>
    event('WORKSPACE_CHANGES', {
      commitSha: 'sha-1',
      files,
      truncated: false,
      turnIndex: 0,
      ...extra,
    })

  it('每个文件都带上那一帧的 commitSha —— 点开看 diff 就是拿它去取正文', () => {
    const items = stream([changesFrame([file('a.ts', 1, 0)])])

    const files = groupIntoTurns(items).flatMap((block) => turnChanges([block]))

    expect(files[0]?.files[0]?.commitSha).toBe('sha-1')
  })

  it('字段缺了按「没有」算，binary / created 只认 true', () => {
    const items = stream([changesFrame([{ path: 'a.ts', added: 2, binary: 'yes' }])])

    expect(items[0]).toMatchObject({ kind: 'changes', truncated: false })
    const changed = groupIntoTurns(items)[0]?.find((item) => item.kind === 'changes')
    expect(changed).toMatchObject({
      files: [{ path: 'a.ts', added: 2, deleted: 0, binary: false, created: false }],
    })
  })

  it('files 不是数组时不炸 —— 按"没有"算', () => {
    const items = stream([changesFrame(undefined)])

    expect(items[0]).toMatchObject({ kind: 'changes', files: [] })
  })

  it('**老事件没有轮次号 → null，不能默认成 0** —— 0 是一个合法的号', () => {
    const missing = stream([changesFrame([file('a.ts', 1, 0)], { turnIndex: undefined })])
    const zero = stream([changesFrame([file('a.ts', 1, 0)], { turnIndex: 0 })])

    expect(missing[0]).toMatchObject({ turnIndex: null })
    expect(zero[0]).toMatchObject({ turnIndex: 0 })
    // 0 号那条要进表；没有号的那条不进（进的话它会占住 0 号）
    expect(changesByTurnIndex(missing).size).toBe(0)
    expect(changesByTurnIndex(zero).has(0)).toBe(true)
  })

  it('同一个文件在一轮里出现两次 → **行数相加**，不是只留最后一条', () => {
    // 一轮里可以落好几条改动记录（批准之后续跑，收尾时再落一条）。
    // 只留最后一条会把前一次的改动从账上抹掉
    const items = stream([
      changesFrame([file('a.ts', 3, 1)], { turnIndex: 1 }),
      changesFrame([file('a.ts', 2, 4)], { turnIndex: 1 }),
    ])

    const entry = changesByTurnIndex(items).get(1)

    expect(entry?.files).toEqual([
      { path: 'a.ts', added: 5, deleted: 5, binary: false, created: false, commitSha: 'sha-1' },
    ])
  })

  it('一条说截断了，这一轮就是截断了', () => {
    const items = stream([
      changesFrame([file('a.ts', 1, 0)]),
      changesFrame([file('b.ts', 1, 0)], { truncated: true }),
    ])

    expect(changesByTurnIndex(items).get(0)?.truncated).toBe(true)
  })

  it('一轮没改任何文件 → 不进"按轮次号"那张表，也不进会话流的分块结果', () => {
    // 界面上那一行本来就该不出现（它是"这一轮改了什么"，没改就没什么可说）
    const items = stream([event('USER_MESSAGE', { text: '问一句' }), state('WAITING_USER')])

    expect(changesByTurnIndex(items).size).toBe(0)
    expect(turnChanges(groupIntoTurns(items))).toEqual([])
  })

  it('会话流那份按**块号**挂，回滚面板那份按**后端的号**挂', () => {
    const items = stream([event('USER_MESSAGE', { text: '第一句' }), changesFrame([file('a.ts', 1, 0)], { turnIndex: 7 })])

    // 块号是 0（第一块），后端号是 7 —— 两份各按各的，谁也不去猜另一套
    expect(turnChanges(groupIntoTurns(items))[0]?.turn).toBe(0)
    expect([...changesByTurnIndex(items).keys()]).toEqual([7])
  })
})
