import { useEffect, useMemo, useState } from 'react'

import { errorText, sessions, type Session } from '@/lib/api'
import { changeTotals, changesByTurnIndex, type StreamItem, type TurnFiles } from '@/lib/sessionStream'

/** 一次能看见几条。上下再多了就折叠成「↑ N more」。 */
const WINDOW = 6

/** 一个可以退回去的位置。 */
interface Mark {
  /** 回滚要送出去的就是它：这条 checkpoint 在事件流里的位置，全局唯一 */
  seq: number
  /** 这条 checkpoint 记的轮次号，界面上按它去对那一轮的改动 */
  turn: number
}

/** 列表里的一项：一个可以退回去的点，或者"现在"。 */
interface Entry {
  mark: Mark
  /** 这个位置后面那句话（选中它会撤销的第一句）。"现在"那条没有：它说的是此刻在哪 */
  said: string | null
  change: TurnFiles | null
  isNow: boolean
}

/**
 * 回滚：挑一个点，把代码和对话一起退回去。
 *
 * 它替掉的是输入框那一条，不是整栏：会话流还在上面。挑"退到哪一轮之前"时多半
 * 想回头看一眼那几轮说了什么，整栏被占掉就没有对照了。
 *
 * 每一行的主语是「你说的那句话」，不是 commit、时间或"第几轮"：轮次的定义就是
 * 你说了一句话，代码改动是它的产物，所以代码那行是副标题。
 *
 * 数据就是屏幕上那条流，不另外拉：位置、那句话、这一轮改了什么都在 `items` 里，
 * 而 `items` 就是渲染上面那段对话用的同一份 fold，所以面板与上面的对话不可能对不上。
 * 理由见
 * `docs/decisions/simplification/2026-10-18-checkpoint-list-comes-from-the-stream.md`。
 *
 * 一个位置一行，"现在"单独占一行。纯键盘：↑↓ 选、Enter 确认、Esc 取消。
 */
export function RewindPicker({
  sessionId,
  items,
  onClose,
  onRewound,
}: {
  sessionId: string
  /** 屏幕上那条流（`SessionStreamView` 折出来的那一份） */
  items: StreamItem[]
  onClose: () => void
  /**
   * 退好了。
   *
   * 第二个参数是刚被退掉的那句话，调用方拿它放回输入框：退完让人重新打一遍，等于
   * 把回滚的用处去掉一半。取不到时是 null（见 {@link unknownTurn}）。
   */
  onRewound: (session: Session, said: string | null) => void
}) {
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const entries = useMemo<Entry[]>(() => {
    // 没有序号的 checkpoint 当不了回滚目标（回滚送的就是序号），所以不进列表。
    // 它只在流式增量那类帧上出现；真碰上宁可少一行，也不给一行点了会退错地方的。
    const marks: Mark[] = items.flatMap((item) =>
      item.kind === 'checkpoint' && item.seq !== undefined
        ? [{ seq: item.seq, turn: item.turn }]
        : [],
    )

    // 一个编号只留最早那条。
    //
    // 号是"到这儿为止完成了几次交互"（见 CheckpointCreated）。同一个号会有两条：
    // 一次带审批的轮次会落两个点（挂起一个、跑完一个），挂起那条写的是上一轮完成
    // 时的号，它是这一轮内部的位置，不该单独占一行。取最早那个，正好是"这次交互
    // 结束、下一句还没说"的位置。
    const byTurn = new Map<number, Mark>()
    for (const mark of marks) {
      if (!byTurn.has(mark.turn)) {
        byTurn.set(mark.turn, mark)
      }
    }
    const positions = [...byTurn.values()]

    // 一行 = 一个位置，标签是它后面那句话：编号 k 的位置（第 k 次交互结束）配第
    // k+1 句用户消息，最后一个位置没有"后面那句话"，就是"我现在在这儿"，单独成行。
    //
    // 一次交互就是一句用户消息：挂起等人批不算跑完、号不推；批准后续跑仍是同一句
    // 的那一轮，跑完才推。所以"完成 k 次"和"说过 k 句"永远对得上，按消息顺序取
    // 就是按号取。这里跟的是号本身的来源，不是块号。
    const said = items.flatMap((item) => (item.kind === 'user' ? [item.text] : []))

    // 改动按后端那个号取，不按位置推：这一列的行本来就是后端号（checkpoint），
    // "这一轮改了哪些文件"的事件也带同一个号（WorkspaceChanges.turnIndex），
    // 谁也不数第二遍。
    const changes = changesByTurnIndex(items)

    // 「现在」取流里最后一个标记，而不是"编号最大的位置"。两者一般一回事，但一轮
    // 正挂在审批上时不是：那个位置在编号上属于上一次交互结束（这一轮还没跑完、号
    // 没推），可是代码此刻就停在那儿。按对象（不是 sha）把它从行里摘掉：一轮没
    // 改动时两条 checkpoint 的 sha 相同，按 sha 摘会误伤。
    const now = marks[marks.length - 1]
    if (now === undefined) {
      return []
    }
    return [
      ...positions
        .filter((mark) => mark !== now)
        .map((mark) => ({
          mark,
          said: said[mark.turn] ?? null,
          change: changeOfTurn(changes, mark.turn),
          isNow: false,
        })),
      { mark: now, said: null, change: null, isNow: true },
    ]
  }, [items])

  /** 光标：默认停在「现在」那一行；要往回退，起点就是现在 */
  const [at, setAt] = useState(-1)
  useEffect(() => {
    if (entries.length > 0 && at < 0) setAt(entries.length - 1)
  }, [entries.length, at])

  // 窗口跟着光标走：走到边上就翻页，上下各留出"还有几条"。
  const start = Math.min(
    Math.max(0, at - Math.floor(WINDOW / 2)),
    Math.max(0, entries.length - WINDOW),
  )
  const visible = entries.slice(start, start + WINDOW)

  async function rewind() {
    const target = entries[at]
    if (target === undefined || busy) return
    setBusy(true)
    setError(null)
    try {
      onRewound(await sessions.rewind(sessionId, target.mark.seq), target.said)
    } catch (e) {
      setError(errorText(e, '回滚失败'))
    } finally {
      setBusy(false)
    }
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      void rewind()
      return
    }
    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      event.preventDefault()
      const step = event.key === 'ArrowUp' ? -1 : 1
      setAt((current) => Math.min(Math.max(0, current + step), entries.length - 1))
    }
  }

  /**
   * 焦点收进来，否则 Esc 和方向键没人接。
   *
   * 必须等面板真的渲染出来：挂在 `[]` 上时组件还在 loading 分支，界面上没有那个
   * 元素，`getElementById` 拿到 null，焦点留在输入框里，表现是"打开之后要先点一下，
   * Esc 才管用"。依赖 `entries.length`：数据到齐、面板出现的那次渲染之后才收焦点。
   */
  useEffect(() => {
    document.getElementById('rewind-picker')?.focus()
  }, [entries.length])

  return (
    <Frame>
      <div
        id="rewind-picker"
        role="listbox"
        tabIndex={-1}
        aria-label="回滚到哪句话之前"
        onKeyDown={onKeyDown}
        className="outline-none"
      >
        {/* 标题一行、说明一行：标题回答"这是什么"，说明回答"它会动什么"，而后者
            是两条都要动，那正是这里最容易误解的地方。

            说明必须在标题下第一行、用正文那一档颜色：它说的是动作退到哪——"选中
            一句 = 退到那句之前"，而列表每行写的是那句话本身，读起来天然像"回到这
            一轮"。实测踩过：选"让它写斐波那契那栏"，结果是退到 "hello?" 之后（=
            斐波那契那句之前），看着像"回滚到第 0 轮"；它做的是对的，是这句话没被读到 */}
        <p className="text-sm font-medium">回滚</p>
        <p className="mb-2 text-xs text-foreground">
          选中哪一句，就退到那句之前 —— 代码和对话一起退。
        </p>

        {error !== null && (
          <p role="alert" className="mb-1 text-xs text-destructive">
            {error}
          </p>
        )}

        {/* 只有 (current) 一行 = 一次交互都还没跑完，没有"之前"可退；这时显示一句话，
            而不是列一行让人选 */}
        {entries.length <= 1 ? (
          <p className="text-xs text-loom-faint">还没有可以回退的地方。</p>
        ) : (
          <>
            {start > 0 && <More count={start} />}

            {visible.map((entry, offset) => {
              const index = start + offset
              const here = index === at
              // key 用序号而不是 sha：同一个 sha 在一条会话里会多次出现（一轮没
              // 改动时），拿它当 key 就是两个相同的 key，React 的表现是少画/错画
              // 一行，不报错。
              return (
                <div key={entry.isNow ? 'now' : entry.mark.seq}>
                  {/* 光标用左侧竖线标，不是背景块：这一栏里已经有底色在表达别的意思 */}
                  <div className={`border-l-2 pl-2 ${here ? 'border-ring' : 'border-transparent'}`}>
                    {entry.isNow ? (
                      <p className="text-sm italic text-loom-faint">(current)</p>
                    ) : (
                      <>
                        <p className={`truncate text-sm ${here ? 'text-foreground' : 'text-muted-foreground'}`}>
                          {entry.said ?? unknownTurn(entry.mark.turn)}
                        </p>
                        <p className="pl-0.5 text-xs text-loom-faint">{describe(entry.change)}</p>
                      </>
                    )}
                  </div>
                </div>
              )
            })}

            {entries.length > start + WINDOW && <More count={entries.length - start - WINDOW} down />}
          </>
        )}
      </div>

      <p className="mt-2 text-xs text-loom-faint">
        {entries.length <= 1 ? 'Esc 取消' : '↑↓ 选 · Enter 确认 · Esc 取消'}
      </p>
    </Frame>
  )
}

/** 上下两条折叠提示。它是个数，读的人知道还剩多少，而不是"下面还有"。 */
function More({ count, down = false }: { count: number; down?: boolean }) {
  return (
    <p className="pl-4 text-xs text-loom-faint">
      {down ? '↓' : '↑'} {count} more
    </p>
  )
}

/**
 * 那一轮的代码改动，写成一行。
 *
 * 什么都没改时明写"没有改动"，不留空："这一轮只说了话没动代码"是个值得知道的事实，
 * 留空读起来像这一格没加载出来。
 *
 * 改动太多时后端只记了前一批，数字如实带"+"，不拿偏小的数当完整报告。
 */
function describe(change: TurnFiles | null): string {
  if (change === null || change.files.length === 0) {
    return '没有代码改动'
  }
  const { added, deleted } = changeTotals(change.files)
  return `${change.files.length}${change.truncated ? '+' : ''} 个文件 · +${added} −${deleted}`
}

/**
 * 位置 `at` 里该撤销的改动：第 `at + 1` 句话那一轮改了什么。
 *
 * 两个号是同一条尺子：checkpoint 的 `turnIndex` 与每一轮改动的 `turnIndex` 都数
 * "完成了几次交互"（结束第 k 次 = k，第一轮为 1），所以直接相等。它不是从 0 数的
 * 用户消息下标。
 */
function changeOfTurn(changes: Map<number, TurnFiles>, at: number): TurnFiles | null {
  return changes.get(at) ?? null
}

/**
 * 那句话没能显示出来时写什么。
 *
 * 不能编一句像真的话。取不到至少有三种原因（历史被压缩、编号对不上、那段流还没
 * 到），要人做的事不一样。如实说"没能取到"并带上第几句，至少能对上左边代码改动
 * 是哪一轮。
 */
function unknownTurn(at: number): string {
  return `第 ${at + 1} 句话 · 没能取到`
}

/** 贴着输入框那一块的样子：同样的左右留白，让它看起来是"那一行换了内容"而不是另开一块。 */
function Frame({ children }: { children: React.ReactNode }) {
  return <div className="shrink-0 px-4 py-3.5">{children}</div>
}
