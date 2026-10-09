import { useEffect, useMemo, useState } from 'react'

import { ApiError, sessions, type Session } from '@/lib/api'
import { turnChanges, type StreamItem, type TurnChanges } from '@/lib/sessionStream'

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
  /** 这个位置**后面**那句话（= 选中它会撤销的第一句）。`(current)` 那条没有 —— 它说的是"此刻在哪" */
  said: string | null
  change: TurnChanges | null
  isNow: boolean
}

/**
 * 回滚：挑一个点，把**代码和对话一起**退回去。
 *
 * <h2>它替掉的是输入框那一条，不是整栏</h2>
 * 会话流还在上面。回滚要挑"退到哪一轮之前"，而挑的时候多半想回头看一眼那几轮说了什么 ——
 * 整栏被它占掉的话那份对照就没了。
 *
 * <h2>每一行的主语是「你说的那句话」</h2>
 * 不是 commit、不是时间、也不是"第几轮"。因为**轮次的定义就是你说了一句话**：
 * 代码改动是它的产物，所以代码那行是副标题。这和 Claude Code 的形态一致，
 * 也和 {@code CheckpointCreated} 上那条"轮次是给人看的概念"对齐。
 *
 * <h2>数据就是屏幕上那条流 —— 它不自己再拉一份</h2>
 * 位置、那句话、这一轮改了什么，三样**都在 {@code items} 里**，而 {@code items}
 * 就是渲染上面那段对话用的同一份 fold。所以这个面板和它上面显示的东西**不可能对不上**。
 *
 * <p>它从前不是这样：自己发一个请求、自己 fold 一遍。那条路的坑是
 * 那个接口**分页**（默认两百条），于是面板只看得到一条会话**最早**的两百条事件 ——
 * 最近的位置列不出来，而被回滚退掉的老分支上的位置**反而还在**（那条
 * {@code SessionRewound} 也排在两百条之外，截断压根没发生）。Claude Code 那边不存在这个问题，
 * 因为它的位置列表来自它手里那份会话模型。这条改成同一个形状。
 *
 * <h2>一个位置一行，"现在"单独占一行</h2>
 * 见下面 {@code entries} 那两段注释 —— 它们都是为了在**同样的情况下显示同样的东西**
 * （Claude Code 里说了两句话就是"第一句 / 第二句 / (current)"三行）。
 *
 * <h2>纯键盘</h2>
 * 照 Claude Code：↑↓ 选、Enter 确认、Esc 取消。
 */
export function RewindPicker({
  sessionId,
  items,
  onClose,
  onRewound,
}: {
  sessionId: string
  /** 屏幕上那条流（{@code SessionStreamView} 折出来的那一份） */
  items: StreamItem[]
  onClose: () => void
  /**
   * 退好了。
   *
   * <p>第二个参数是**刚刚被退掉的那句话**，调用方拿它放回输入框 —— 退完让人重新打一遍，
   * 等于把这次回滚的用处去掉一半。取不到那句话时是 {@code null}（见 {@link unknownTurn}）。
   */
  onRewound: (session: Session, said: string | null) => void
}) {
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const entries = useMemo<Entry[]>(() => {
    // 没有序号的 checkpoint **当不了回滚目标**（回滚送的就是序号），所以它不进列表。
    // 它只在"流式增量"那类帧上出现 —— checkpoint 是落库的事件，正常都带序号；
    // 真碰上了也宁可少一行，而不是给出一行点了会退错地方的
    const marks: Mark[] = items.flatMap((item) =>
      item.kind === 'checkpoint' && item.seq !== undefined
        ? [{ seq: item.seq, turn: item.turn }]
        : [],
    )

    // **一个编号只留最早那条。**
    //
    // 号是"到这儿为止完成了几次交互"（见 CheckpointCreated）。同一个号会有两条：
    // 一次带审批的轮次会落两个点（挂起时一个、跑完一个），挂起那条写的是
    // **上一轮完成时的号** —— 它是"这一轮**内部**"的位置，不该单独占一行。
    //
    // 取**最早**那个，正好就是"这次交互结束、下一句还没说"那个位置。
    const byTurn = new Map<number, Mark>()
    for (const mark of marks) {
      if (!byTurn.has(mark.turn)) {
        byTurn.set(mark.turn, mark)
      }
    }
    const positions = [...byTurn.values()]

    // **一行 = 一个位置，标签是它后面那句话。**
    //
    // Claude Code 就是这么配的：列表里是**每一条用户消息**加末尾一个虚拟的"当前输入"，
    // 选中某一条的含义是"退到**我发出这句话之前**"（它的确认文案原话是
    // "restore … to the point before you sent this message"）。
    //
    // 所以编号 k 那个位置（第 k 次交互结束）配的是**第 k+1 句话**，而最后一个位置
    // 没有"后面那句话" —— 它就是"我现在在这儿"，单独成一行 (current)。
    const said = items.flatMap((item) => (item.kind === 'user' ? [item.text] : []))
    const changes = turnChanges(items)

    // 「现在」取的是**流里最后一个标记**，而不是"编号最大的那个位置"。两者一般是一回事，
    // 但一轮正挂在审批上时不是：那个位置在编号上属于**上一次交互结束**（这一轮还没跑完，
    // 号没推），可是代码此刻就停在那儿 —— (current) 得说真话。
    // 所以按**对象**（不是 sha）把那条从行里摘掉：一轮没改动时两条 checkpoint 的 sha
    // 一模一样，按 sha 摘会把别人也摘掉
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

  /** 光标：默认停在「现在」那一行 —— 你要往回退，起点就是现在 */
  const [at, setAt] = useState(-1)
  useEffect(() => {
    if (entries.length > 0 && at < 0) setAt(entries.length - 1)
  }, [entries.length, at])

  // 窗口跟着光标走：它走到边上，窗口就翻页。上下各留出"还有几条"
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
      setError(e instanceof ApiError ? e.message : '回滚失败')
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
   * 焦点收进来，否则 Esc 和方向键都没人接。
   *
   * <p>**它必须等面板真的渲染出来。** 一开始写在挂载时跑（依赖 `[]`），而那一刻组件还在
   * loading 分支上 —— 渲染的是"正在读取…"，压根没有那个元素，于是 `getElementById`
   * 拿到 null，焦点留在输入框里。表现就是"打开之后要先拿鼠标点一下，Esc 才管用"。
   *
   * <p>依赖里放 `entries.length`：数据到齐、面板出现的那一次渲染之后，才去收焦点。
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
        {/* 标题一行、说明一行（同 Claude Code）：标题回答"这是什么"，
            说明回答"它会动什么" —— 而后者是**两条都要动**，那正是这里最容易误解的地方。

            标题从前是英文的 "Rewind"（照搬参考实现），全站其余都是中文，改了。

            说明**必须在标题下第一行、用正文那一档的颜色**：它说的是这个动作**退到哪** ——
            "选中一句 = 退到那句**之前**"，而列表里每一行写的是那句话本身，
            读起来天然像"回到这一轮"。实测踩过：选"让它写斐波那契那栏"，结果是退到
            "hello?" 之后（= 斐波那契那句之前），看着像"回滚到第 0 轮"。
            它做的是对的，是这句话没被读到。 */}
        <p className="text-sm font-medium">回滚</p>
        <p className="mb-2 text-xs text-foreground">
          选中哪一句，就退到那句之前 —— 代码和对话一起退。
        </p>

        {error !== null && (
          <p role="alert" className="mb-1 text-xs text-destructive">
            {error}
          </p>
        )}

        {/* 只有 (current) 一行 = 一次交互都还没跑完，那就没有"之前"可退 ——
            Claude Code 这时候也是一句话，而不是列一行让人选（照搬它的判据） */}
        {entries.length <= 1 ? (
          <p className="text-xs text-loom-faint">还没有可以回退的地方。</p>
        ) : (
          <>
            {start > 0 && <More count={start} />}

            {visible.map((entry, offset) => {
              const index = start + offset
              const here = index === at
              // key 用**序号**而不是 sha：同一个 sha 在一条会话里会出现好几次
              //（一轮什么都没改时），拿它当 key 就是两个一模一样的 key ——
              // 而 React 那种情况下的表现是"少画/错画一行"，不报错
              return (
                <div key={entry.isNow ? 'now' : entry.mark.seq}>
                  {/* 光标用一条左侧的竖线标，不是背景块 —— 这一栏里已经有底色在表达别的意思 */}
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

/** 上下那两条折叠提示。**它是个数**，所以读的人知道还剩多少，而不是"下面还有"。 */
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
 * <p>**什么都没改时明写"没有改动"**，不留空 —— "这一轮只说了话没动代码"是个值得知道的事实，
 * 而留空读起来像"这一格没加载出来"。
 *
 * <p>改动太多时后端只记下了前一批，那个数字如实带个"+" —— 不拿一个偏小的数当完整的报。
 */
function describe(change: TurnChanges | null): string {
  if (change === null || change.files.length === 0) {
    return '没有代码改动'
  }
  const added = change.files.reduce((sum, file) => sum + file.added, 0)
  const deleted = change.files.reduce((sum, file) => sum + file.deleted, 0)
  return `${change.files.length}${change.truncated ? '+' : ''} 个文件 · +${added} −${deleted}`
}

/**
 * 位置 {@code at} 里**该撤销的改动**：第 {@code at + 1} 句话那一轮改了什么。
 *
 * <h2>两个号是同一条尺子</h2>
 * checkpoint 的 {@code turnIndex} 数的是"完成了几次交互"（结束第 k 次交互 = k），
 * 而每一轮改动的 {@code turn} 数是"第几条用户消息"（从 0 起）。两个都是**每次交互 +1**，
 * 所以位置 {@code at} 后面那句话就是第 {@code at} 条用户消息（0 起），两者**直接相等**。
 */
function changeOfTurn(changes: TurnChanges[], at: number): TurnChanges | null {
  return changes.find((entry) => entry.turn === at) ?? null
}

/**
 * 那句话没能显示出来时，写什么。
 *
 * <p>**不能编一句像是真的话。** 取不到的原因至少有三种，而它们要人做的事不一样：
 * 历史被压缩过、编号对不上、或者那一段流还没到。
 * 所以如实说"没能取到"，并把是第几句带上 ——
 * 那至少能让人对得上左边那些代码改动是哪一轮的。
 */
function unknownTurn(at: number): string {
  return `第 ${at + 1} 句话 · 没能取到`
}

/** 贴着输入框那一块的样子：同样的左右留白，好让它看起来是"那一行换了内容"而不是另开一块。 */
function Frame({ children }: { children: React.ReactNode }) {
  return <div className="shrink-0 px-4 py-3.5">{children}</div>
}
