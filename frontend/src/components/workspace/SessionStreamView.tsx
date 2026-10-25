import { ChevronRight, CornerDownRight } from 'lucide-react'
import { createContext, memo, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ThemedToken } from 'shiki'

import { Button } from '@/components/ui/button'
import { LivePane } from '@/components/workspace/LivePane'
import { Markdown, StreamingMarkdown } from '@/components/workspace/Markdown'
import { errorText, approvals, sessions as sessionsApi, type ToolView } from '@/lib/api'
import { languageOfPath, tokenStyle, useHighlighted, useViewportHighlighting } from '@/lib/highlight'
import { lineNumberWidth } from '@/lib/lineNumbers'
import { observeStream, usePendingEcho } from '@/lib/pendingEcho'
import {
  formatTokens,
  groupIntoTurns,
  changeTotals,
  toolIsRunning,
  turnChanges,
  turnIsActive,
  usageLineVisible,
  workspaceWrites,
  type ContextReading,
  type NoticeTone,
  type StreamItem,
  type Streaming,
  type ToolOutcome,
  type TurnChanges,
  type Unfinished,
} from '@/lib/sessionStream'
import { diffLines, wordDiffs, type DiffRow, type DiffSegment } from '@/lib/textDiff'
import { dayOf, externalUrl, hostOf, type WebSource } from '@/lib/toolPresentation'
import { useSessionStream } from '@/lib/useSessionStream'
import { ToolCatalogContext, useTool, useToolCatalog } from '@/lib/useToolCatalog'

/**
 * 一条会话的事件流。
 *
 * agent 说的话是满宽的一块文本，不是气泡：气泡适合"你一句我一句"的短对话（聊天室
 * 就是那样），而这里是一整段工作过程——回答、工具调用、验证结论。给它们套气泡
 * 等于说"这些是同一类东西"。
 *
 * 不画"这是谁的 agent"的彩色竖线：这一栏是标签页，永远只有一条流在屏幕上，那个
 * 比较从未发生；"这是谁的"已经由名字和名字前的点说了两遍。
 *
 * 与聊天室共用 {@link LivePane}：滚动、贴底、断线提示在外壳里，这里只负责"一条
 * 消息长什么样"。
 */
export function SessionStreamView({
  sessionId,
  speaker,
  nameOf,
  canApprove = false,
  footer,
  emptyHint,
  onOpenFile,
  onOpenDiff,

  onWorkspaceChanged,
}: {
  sessionId: string | null
  /**
   * 按 id 查一个人叫什么。
   *
   * 事件里带的都是用户 id（用户名会变，不能当身份），名字在这一层现查：改过用户名
   * 之后历史那几行也跟着变。查不到时返回 null，各处给一句诚实的说法（"有人"/
   * "协作者"），不编名字。
   */
  nameOf?: (id: string) => string | null
  /**
   * 能不能批挂起的调用。只有会话所有者能（后端是 requireDriver）。
   *
   * 观战时为 false：B 用自己的 key 驱动自己的 agent，A 既不知道它在做什么，也不知道
   * 为什么，让 A 同意等于让 A 替 B 负责。
   */
  canApprove?: boolean
  /**
   * 这条会话的主人怎么称呼，用来标"他说的那几句"。
   *
   * 看自己的会话时是「你」，观战时是他的名字。不能写死成「你」：观战时那几行是
   * 他说的话，挂在你名下等于你看见几句自己没说过的话。
   */
  speaker: string
  /**
   * 贴在底部的东西（输入框 / 回滚面板）。观战时没有：别人的会话你插不上话。
   *
   * 做成函数并接收 fold 出来的条目，是因为回滚面板要的就是这条流（"能退回哪儿"、
   * "这一轮改了什么"都在里面）；让它自己再拉一次会出现两份数据，屏幕与面板可能
   * 对不上。做成函数而不是另开 context：条目就在这个组件里折出来，交给渲染底部的
   * 人是最短的路。
   */
  footer?: (items: StreamItem[]) => React.ReactNode
  /**
   * 观战栏空着时的说法。
   *
   * 三种空法不是同一件事：还没有队友、队友还没有会话、正在打开。写死成一句会对
   * 已经进来的人说错话；列表还没刷新时还会把人往"他是不是没进来"的方向带。
   */
  emptyHint?: string
  /**
   * 点流里提到的文件。
   *
   * 看自己的会话时是"在中栏打开它"；观战时文件在对方的树里，调用方给的是另一种
   * 动作（说明它还没合并，而不是去读一棵里面没有它的树）。
   */
  onOpenFile?: (path: string) => void
  onOpenDiff?: (commitSha: string, path: string) => void

  /**
   * agent 动了工作区里的文件。
   *
   * 这是 agent 写盘后触发树重拉的唯一来源：树看不见磁盘；它自己的建、删、改名
   * 另算（见 `FileTree`）。后端往磁盘写文件和界面之间本来没有线，这是接上的那根。
   *
   * 观战那处不传：对方的 agent 改的是他的树，我这边的树没动。接上去只会让人看着
   * 一棵一直重拉、内容永远不变的树。
   */
  onWorkspaceChanged?: () => void
}) {
  const { items, streaming, disconnected, empty } = useSessionStream(sessionId)

  /**
   * 名字查不到时说「有人」，不编名字（见下面的 who）。
   * 没接这一层时（这条流不给人看的那种用法）同样走它。
   */
  const lookupName = nameOf ?? (() => null)

  /**
   * 工具声明的样子，供这一整棵树渲染。
   *
   * 取一次就够，声明是静态的（见 `useToolCatalog`）。挂在这里而不是每个小组件各自
   * 取，是因为"取"有加载态，工具行不该各自闪一下。
   */
  const catalog = useToolCatalog()

  /**
   * 这一轮是否还在跑。
   *
   * 判据只有一个：这一轮最后那条状态（见 {@link turnIsActive}）。它喂给"按 Esc
   * 打断"和下面那行状态；判错的方向里说"没在跑"更贵——人正要打断一次跑了几十秒
   * 的构建，而按钮不响应。
   */
  const running = turnIsActive(items)

  /** 停止信号发出去了、但还没真的停。只为了给一句话的反馈，见下面的 effect */
  const [stopping, setStopping] = useState(false)

  const writes = useMemo(() => workspaceWrites(items), [items])

  // 停了（或者本来就没在跑）就把那句话收掉
  useEffect(() => {
    if (!running) setStopping(false)
  }, [running])

  /**
   * 按 Esc 打断正在跑的那一轮。
   *
   * 挂在 window 上而不是这一块区域上：人要打断时手多半不在输入框上。不抢别人已经
   * 处理过的事件（右键菜单按 Esc 会 preventDefault）。
   *
   * 按下之后在输入框上方挂一句「正在停…」：它是信号（后端回 202），取消只在工具
   * 边界检查，发出去了不等于停了；没有反馈的话按的人只会以为键没生效。
   */
  useEffect(() => {
    if (sessionId === null) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || !running) return
      setStopping(true)
      void sessionsApi.interrupt(sessionId).catch(() => setStopping(false))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [sessionId, running])

  /**
   * 我刚发出、还没在流里出现的话。
   *
   * 判据是"用户消息条数"：发请求那条路不等回执（见 `Composer.send`），"发出去了"
   * 与"流里看得见"之间有一段空白，排队那句要等这一轮收尾才落库。这中间得让说话
   * 的人看见自己那句话，否则就是"点了没反应"。
   *
   * 只数条数、不比内容：同一句话发两遍是允许的。
   */
  const userCount = items.reduce((count, item) => (item.kind === 'user' ? count + 1 : count), 0)
  const pending = usePendingEcho(sessionId)
  useEffect(() => {
    if (sessionId !== null) {
      observeStream(sessionId, userCount)
    }
  }, [sessionId, userCount])

  /**
   * 上一次通知过的时候这个数是多少。
   *
   * 必须记着：`items` 每批事件都换新数组，没有这一步的话只要数大于零就会每批通知
   * 一次，左边的树会被打成每帧重拉。见下面 `changesByTurn` 那条同类说明。
   */
  const notified = useRef(0)
  useEffect(() => {
    // 归零只有一种原因：换了会话（items 被清空）。记号跟着归零，否则新会话头几次
    // 改动会被上一条会话的大数压住，一直不通知。
    if (writes === 0) {
      notified.current = 0
      return
    }
    if (writes > notified.current) {
      // 一次只通知一次，不管中间多了几条：调用方要的是"现在去拉一遍"，
      // 拉一次拿到的就是最新全部内容。
      notified.current = writes
      onWorkspaceChanged?.()
    }
  }, [writes, onWorkspaceChanged])

  // 回滚用的位置标记不进分块。
  //
  // 看不见的条目留着只有坏处：开头那条会独占一个空块，空块没有高度，space-y-8
  // 却会给下一个兄弟留白；更要紧的是轮次号按块下标查（见下面 changesByTurn 和
  // TurnRail），checkpoint 多占一格会让后面每一轮整体错一格。
  const turns = groupIntoTurns(items)

  /**
   * 每一轮改了哪些文件。
   *
   * 不另发请求：若在读的时候拿 git 现算，每收尾一轮就要重算整条历史，整条会话是
   * O(n²)，提交被 gc 后那一轮永远算不出来。后端改为在收尾时算一次、落进事件流
   * （见 WorkspaceChanges），跟着 `items` 一起来，和屏幕上的消息同出一份 fold。
   */
  const changesByTurn = new Map(turnChanges(turns).map((entry) => [entry.turn, entry]))

  /**
   * 每一轮的根元素。轮次导航点一下就把那一轮滚进视野，见 {@link TurnRail}。
   *
   * 换会话时不清空：那些元素已不在 DOM 里，`scrollIntoView` 对着脱树节点什么也
   * 不做；加一套清理反而多一处会写错的地方。
   */
  const turnRefs = useRef<(HTMLDivElement | null)[]>([])

  function jumpTo(index: number) {
    turnRefs.current[index]?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }

  // 只有一个 return。若在"还没有会话"时提前 return 掉整个面板，底部输入框就没了，
  // 而它是打第一句话的唯一入口——会话正是在用户说出第一句话时产生的（见
  // Composer），界面上写着"在下面说一句话"，下面却什么都没有。收进 children 之后
  // footer 在这个组件里只出现一次，"某条路径忘了带上它"在结构上不可能发生。
  return (
    <ToolCatalogContext.Provider value={catalog}>
      <LivePane
      // 没有会话时不会有连接，也就不会有"断线"这回事
      disconnected={sessionId !== null && disconnected}
      progress="连接断了，正在重连…"
      footer={
        <>
          {/* 贴在输入框上方而不是流末尾：它说的是"这一轮正在收尾"，是个状态，
              翻上去看历史时也该看得见 */}
          {/* 这一行常驻（不按 Esc 时占一个不换行空格）：它是插进布局的元素，
              按条件挂出来会把滚动区挤矮一行 */}
          <p role="status" className="shrink-0 px-4 pt-2 text-xs text-loom-faint">
            {stopping ? '正在停…' : ' '}
          </p>
          {footer?.(items)}
        </>
      }
      // 只有一轮的话那条刻度毫无意义：它标的就是"这里可以跳"。
      rail={turns.length > 1 ? <TurnRail turns={turns} onJump={jumpTo} /> : undefined}
    >
      {sessionId === null ? (
        footer ? (
          // 贴顶写，不居中：这一栏上方是空的，那句话要指的是下面那个输入框。
          // 居中会让人先看到一大片空白，再在中间找到一行字。
          <p className="px-4 pt-6 text-sm leading-6 text-muted-foreground">
            还没有会话。在下面说一句话，就有了。
          </p>
        ) : (
          // 观战没有输入框，上面那句话没有东西可指，于是回到全站统一空状态：
          // 落在正中间（"这里本来就没有东西"），不贴顶（"内容没加载出来"的样子）。
          <div className="pane-absent h-full">
            <p className="text-muted-foreground">
              {emptyHint ?? '对方还没有会话。把邀请链接发给对方，他就能进来。'}
            </p>
          </div>
        )
      ) : (
        <div className="px-4 py-4">
          {/* space-y-6 是块与块的间距，块内部的 space-y-4 是另一半：两档间距是
              这段流唯一的骨架，见 groupIntoTurns */}
          <div className="space-y-6">
            {empty && !streaming.text && !streaming.reasoning && (
              <p className="text-sm leading-6 text-muted-foreground">
                这里会显示这条会话的每一步。
              </p>
            )}

            {turns.map((turn, index) => (
              // 只有最后那一轮算"正在跑"。
              <Turn
                key={index}
                turn={turn}
                change={changesByTurn.get(index) ?? null}
                anchor={(element) => {
                  turnRefs.current[index] = element
                }}
                live={index === turns.length - 1}
                streaming={index === turns.length - 1 ? streaming : null}
                speaker={speaker}
                sessionId={sessionId}
                canApprove={canApprove}
                onOpenFile={onOpenFile}
                onOpenDiff={onOpenDiff}
                nameOf={lookupName}
              />
            ))}

            {/* 我刚发出、流里还没出现的那几句。灰的：它们还没轮到 */}
            {pending.map((text, index) => (
              <div key={index} className="flex items-start gap-1.5">
                <span className="shrink-0 text-xs leading-6 text-loom-faint/60">{speaker}：</span>
                <div className="min-w-0 flex-1 text-loom-faint">
                  <Markdown>{text}</Markdown>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
      </LivePane>
    </ToolCatalogContext.Provider>
  )
}

/**
 * 事件里带的是用户 id，名字在这一层现查（见 {@link SessionStreamView} 的 nameOf）。
 *
 * 查不到（人不在这张成员表里、或 id 为空）时说「有人」，不编名字：编一个会把一句
 * 话挂到错的人名下，而看的人不会怀疑。
 */
function who(
  nameOf: ((id: string) => string | null) | undefined,
  id: string | null | undefined,
): string {
  return (id ? nameOf?.(id) : null) || '有人'
}

function Item({
  item,
  speaker,
  sessionId,
  canApprove,
  onOpenFile,
  nameOf,

}: {
  /**
   * 不含用量那条：那一行旁边还要挂"这一轮用了多久"，而时长是整轮的属性，不是某条
   * 消息的，所以它由上面那一层一起画。用 `Exclude` 排掉它，下面那个 switch 仍然
   * 穷尽：以后往 `StreamItem` 加东西，这里照样编译不过。
   */
  item: Exclude<StreamItem, { kind: 'usage' }>
  speaker: string
  sessionId: string | null
  canApprove: boolean
  onOpenFile?: (path: string) => void
  /** 见 {@link SessionStreamView} 的 nameOf：事件里带的是 id，名字在这一层查 */
  nameOf: (id: string) => string | null

}) {
  switch (item.kind) {
    case 'user':
      // 人说的那一句要看得出来是人说的：一段几十步的流水里分不出"我让它做的"和
      // "它做的"，读起来就不是记录。用最轻的办法分：句首标出是谁；同一流里的
      // 「平台：」「X 的 agent 捎来一句：」用的也是它。
      //
      // 不用气泡、不用底色：那些是聊天的形状，而这是一段工作过程（见类注释）。
      //
      // 前缀和正文并排而不是前后拼接：正文要过 markdown，markdown 是块级的，直接
      // 拼会把「你：」挤到自己一行。
      return (
        // 与下面灰显回声用同一套排版：回声落库后换成它，位置不该跳。
        <div className="flex items-start gap-1.5">
          <span className="shrink-0 text-xs leading-6 text-loom-faint">{speaker}：</span>
          <div className="min-w-0 flex-1">
            <Markdown>{item.text}</Markdown>
          </div>
        </div>
      )

    case 'agent':
      return (
        <div className="space-y-2">
          {/* 思考在上、答案在下，而且折着：它是"它为什么这么说"，多数时候人要看
              它说了什么 */}
          {item.reasoning && <Thinking text={item.reasoning} />}
          {/* 正文可能为空：模型有一轮只调了工具、一个字没说，而思考要落库（带工具
              调用的那条消息必须把思考一起带回去，见后端 AgentTurn）。渲染空 markdown
              只会多出空档 */}
          {/* 正文贴最左边：与行首那些记号同一条线；工具行文字往后缩是因为前面有
              记号占位 */}
          {item.text !== '' && <Markdown>{item.text}</Markdown>}
        </div>
      )

    case 'note':
      // 来源必须标出来：模型看到它时会被标成"来自谁"，人也要能分清。
      // 名字按 id 现查：事件里只有 id（见 SessionStreamView 的 nameOf）。
      return (
        <p className="whitespace-pre-wrap break-words text-sm leading-6 text-muted-foreground">
          <span className="text-loom-faint">{who(nameOf, item.fromUserId)} 的 agent 捎来一句：</span>
          {item.text}
        </p>
      )


    case 'tool':
      return (
        <ToolLine
          name={item.name}
          args={item.args}
          outcome={item.outcome}
          unfinished={item.unfinished}
          speaker={speaker}
          onOpenFile={onOpenFile}

        />
      )

    case 'retry':
      // 一次失败的重试：要显示，但不是重点。它多数时候几秒钟就过去，存在的全部
      // 意义是让那几秒不至于一片死寂（见 LlmRetryScheduled）。
      return (
        <p className="text-xs text-loom-faint">
          {item.reason}，{formatDelay(item.delayMs)}后重试（第 {item.attempt + 1} / {item.maxAttempts} 次）
        </p>
      )

    case 'approval':
      if (item.approved === null) {
        return (
          <PendingApproval
            sessionId={sessionId}
            callId={item.callId}
            reason={item.reason}
            canApprove={canApprove}
            speaker={speaker}
          />
        )
      }
      // 答复之后不画"某某批准了那次调用"：能批的只有会话主人自己，观战的人按不动，
      // 那行字只是在复述他刚点的一下。拒绝也不用单独说：调用行上写着"X 拒绝了"。
      return null

    case 'notice': {
      // 带 actor 的是某个人做的一个动作（回滚），名字按 id 现查后拼在正文前；
      // 不带的（同步、压缩）是平台自己的事，没有主语。
      const actor = item.actorId === undefined || item.actorId === null
        ? null
        : who(nameOf, item.actorId)
      return <Notice tone={item.tone} text={actor === null ? item.text : `${actor} ${item.text}`} />
    }

    // 位置标记、改动、状态都不给人看：checkpoint 与 changes 留给回滚和推导，state
    // 只当"这一轮什么状态"的事实来源。显式列出而不是让它们掉出 switch：那种隐式
    // 行为会被下一个 default 或 noImplicitReturns 打掉，而"故意不画"和"忘了画"
    // 该分得清。
    case 'checkpoint':
    case 'changes':
    case 'state':
      return null
  }
}

/**
 * 一轮。
 *
 * 做成组件而不是 map 里的一段 JSX：两件事只有"轮"这一层知道。这一轮是否还在跑
 * （折起来的那几项靠它决定要不要收回折叠，见 {@link useDisclosure}，得从这一层
 * 子树传下去）；这一轮的起止时间（由第一条和最后一条消息决定，是整轮的属性）。
 */
type ToolItem = Extract<StreamItem, { kind: 'tool' }>

/** 一轮里要画的一个单元：一条事件，或者连续只读调用合成的一组。 */
interface TurnEntry {
  item: StreamItem
  at: number
  /** 有它就是一组（合并的是这几个只读调用） */
  group?: ToolItem[]
}

/**
 * 把连续的、只读的翻文件调用合成一组（读文件 / grep / glob）。
 *
 * 判据是工具声明的形状，不是工具名（见 `ToolSurface`）。三种情况不进组：带结构化
 * 卡片的结果（联网搜索等；折进去就把"看了哪些网页"藏了）、失败的、没跑完的；
 * 后两种要留在外面让人一眼看见。
 */
function groupToolReads(turn: StreamItem[], catalog: Map<string, ToolView> | null): TurnEntry[] {
  const quiet = (item: StreamItem): item is ToolItem => {
    if (item.kind !== 'tool' || item.unfinished !== null || item.outcome === null) return false
    if (!item.outcome.success || item.outcome.presentation != null) return false
    const shape = catalog?.get(item.name)?.shape
    return shape === 'read' || shape === 'search'
  }
  const entries: TurnEntry[] = []
  let run: { item: ToolItem; at: number }[] = []
  const flush = () => {
    if (run.length >= 2) {
      entries.push({ item: run[0].item, at: run[0].at, group: run.map((one) => one.item) })
    } else {
      run.forEach((one) => entries.push({ item: one.item, at: one.at }))
    }
    run = []
  }
  turn.forEach((item, at) => {
    if (quiet(item)) {
      run.push({ item, at })
      return
    }
    flush()
    entries.push({ item, at })
  })
  flush()
  return entries
}

/**
 * 一组只读调用：一行说清"翻了多少"，点开才是逐条。
 *
 * 一整轮里读六次文件、搜三次，一行一条的话，二十来行里找"它改了哪儿"就变成了
 * 一行行扫。合并之后，流读起来是"想了想 → 翻了几份文件 → 改了这里"。
 */
function ReadGroup({
  items,
  speaker,
  sessionId,
  canApprove,
  onOpenFile,
  nameOf,
}: {
  items: ToolItem[]
  speaker: string
  sessionId: string | null
  canApprove: boolean
  onOpenFile?: (path: string) => void
  nameOf: (id: string) => string | null
}) {
  const [expanded, setExpanded] = useDisclosure()
  const catalog = useToolCatalog()
  const reads = items.filter((item) => catalog?.get(item.name)?.shape === 'read').length
  const searches = items.length - reads
  const label = [
    reads > 0 ? `看了 ${reads} 个文件` : '',
    searches > 0 ? `搜了 ${searches} 次` : '',
  ].filter((part) => part !== '').join('、')

  return (
    <div className="space-y-2">
      <p className="flex items-baseline gap-2 text-sm">
        <span className="flex w-4 shrink-0 items-center self-center">
          {/* 组里每一条都成功才可能成组（见 groupToolReads），所以是恒定的成功点。
              复用工具行那颗：同一个记号不该有第二份尺寸和颜色 */}
          <StatusDot running={false} failed={false} unfinished={false} label="都成功了" />
        </span>
        <span className="text-muted-foreground">{label}</span>
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className={`ml-auto shrink-0 ${TOGGLE}`}
        >
          {expanded ? '收起' : '展开'}
        </button>
      </p>
      {expanded &&
        items.map((item, index) => (
          <Item
            key={index}
            item={item}
            speaker={speaker}
            sessionId={sessionId}
            canApprove={canApprove}
            onOpenFile={onOpenFile}
            nameOf={nameOf}
          />
        ))}
    </div>
  )
}

function Turn({
  turn,
  change,
  anchor,
  live,
  streaming,
  speaker,
  sessionId,
  canApprove,
  onOpenFile,
  onOpenDiff,
  nameOf,

}: {
  turn: StreamItem[]
  /** 这一轮改了哪些文件。没改就是 null */
  change: TurnChanges | null
  /** 挂在那一轮的根元素上；轮次导航靠它把某一轮滚进视野 */
  anchor?: (element: HTMLDivElement | null) => void
  /** 这一轮是否还在跑。只有最后那一轮是 */
  live: boolean
  /** 正在长出来的那一段。只有最后那一轮有，前面几轮传 null */
  streaming: Streaming | null
  speaker: string
  sessionId: string | null
  canApprove: boolean
  onOpenFile?: (path: string) => void
  onOpenDiff?: (commitSha: string, path: string) => void
  /** 见 {@link SessionStreamView} 的 nameOf */
  nameOf: (id: string) => string | null

}) {
  const span = turnSpan(turn)
  /** 工具的声明（认"是不是只读翻文件"用它，不认工具名） */
  const catalog = useToolCatalog()

  // 这一轮的用量结算。一轮可能落好几条，见 mergedUsage。
  const usages = turn.filter((item) => item.kind === 'usage')
  const lastUsageAt = turn.reduce((last, item, at) => (item.kind === 'usage' ? at : last), -1)
  const usage = mergedUsage(usages)

  /** 这一轮就此收场了（失败 / 被打断 / 被拒绝），见上面的 turnEnd */
  const stopped = turn.some((one) => one.kind === 'notice' && one.turnEnd !== undefined)

  /**
   * 这一轮是否真的跑完了，用量行只在跑完之后画。
   *
   * 只判"是不是还挂着等人批"不够：挂起那一刻后端先结算一次，批准后它接着跑，那段
   * 时间状态已离开 AWAITING_APPROVAL；照画的话续跑一开始就冒出挂起时的用量，真跑完
   * 再变一次。判据见 {@link usageLineVisible}：终态才算完成。
   */
  const finished = usageLineVisible(turn, live, stopped)

  return (
    <LiveTurn.Provider value={live}>
      {/* scroll-mt-4：跳过来时上面留一点缝，不然那一轮第一行会贴着顶边 */}
      <div ref={anchor} className="scroll-mt-4 space-y-4">
        {groupToolReads(turn, catalog).map(({ item, at, group }) =>
          group !== undefined ? (
            <ReadGroup
              key={at}
              items={group}
              speaker={speaker}
              sessionId={sessionId}
              canApprove={canApprove}
              onOpenFile={onOpenFile}
              nameOf={nameOf}
            />
          ) : item.kind === 'usage' ? (
            // 用量和"这一轮用了多久"一起画：它们是同一类事实，而时长是整轮的属性，
            // 不该挂在某条消息上。
            //
            // 整轮只画一次，画在最后那一条的位置上：被批准打断的轮次会有两条结算
            // （挂起一条、续跑完一条），各画各的会在屏幕上出现两行"这一轮用了多少"。
            // 你说的"一轮"是你发的那一句话，不是后端的执行单位。
            // 没跑完不画：挂起和续跑都还在这一轮里，见 finished。
            (at === lastUsageAt && usage !== null && finished) ? (
              <TurnTail
                key={at}
                item={usage}
                change={change}
                startedAt={span.startedAt}
                endedAt={span.endedAt}
                stopped={stopped}
                onOpenFile={onOpenFile}
                onOpenDiff={onOpenDiff}
              />
            ) : null
          ) : (
            <Item
              key={at}
              item={item}
              speaker={speaker}
              sessionId={sessionId}
              canApprove={canApprove}
              onOpenFile={onOpenFile}
              nameOf={nameOf}
            />
          ),
        )}

        {/* 正在长出来的那一段属于最后一块：它是这一轮还没说完的话。单列一块会和
            它自己那句话之间空出一整档块间距，看起来像"上一轮说完了，又开了一轮" */}
        {streaming !== null && (
          <>
            {/* 它和上面那些长得不一样是有意的：那些已经发生，这段还没，淡一档就在
                说这件事。

                但"淡"不该淡掉排版：裸 `<p>` 会让人一边写一边看到星号和井号，等落地
                才变成排版好的；现在两边走同一套渲染，唯一还淡着的只有颜色 */}
            {/* 正文一出现就说明思考结束：把思考自动折回去，让位给答案 */}
            {streaming.reasoning && (
              <Thinking text={streaming.reasoning} running={streaming.text === ''} />
            )}
            {/* 流式正文和落库后重画的那一份同一个左边界（都贴最左边），否则答案
                会在两档之间横跳 */}
            {streaming.text && <StreamingMarkdown text={streaming.text} />}
          </>
        )}
      </div>
    </LiveTurn.Provider>
  )
}

/**
 * 轮次导航：右边缘一列刻度，一轮一格。
 *
 * 十几轮之后"刚才那段在哪儿"只能靠滚，而滚是线性的，人记事却按"第几次"。刻度把
 * 会话长度和位置变成一眼能看见，也让"回到第 3 轮"从往上拖半天变成一次点击。
 *
 * 每格挂一句话（那一轮用户说的头几个字）：没有标注的刻度只能告诉你"有 14 轮"，
 * 告诉不了哪一格是你要找的。
 *
 * 不跟着滚动高亮当前格：那要每帧算"哪一轮在视野里"，换来的是装饰性的位置指示，
 * 而人已经知道自己滚到哪儿了。
 */
function TurnRail({ turns, onJump }: { turns: StreamItem[][]; onJump: (index: number) => void }) {
  return (
    <nav aria-label="轮次导航" className="flex flex-col items-end gap-1.5 py-1">
      {turns.map((turn, index) => (
        <button
          key={index}
          type="button"
          title={turnLabel(turn, index)}
          onClick={() => onJump(index)}
          // 刻度要读成一个个可点的记号，而不是一排蹭痕：够亮（30% 的 loom-faint
          // 铺在 #0a0a0c 上只是几粒灰）、彼此分开（gap-1.5）、指上去换成明确亮色。
          // 位置也挪离滚动条（见 LivePane 的 right-2.5）：贴着时这几粒东西最容易被
          // 读成滚动条坏了。
          className="h-1.5 w-4 rounded-full bg-loom-faint/45 transition-colors hover:bg-foreground/70"
        />
      ))}
    </nav>
  )
}

/** 一格刻度上的话：第几轮 + 用户说的头几个字。 */
function turnLabel(turn: StreamItem[], index: number): string {
  const said = turn.find((item) => item.kind === 'user')
  const text = said?.kind === 'user' ? said.text.trim().replaceAll(/\s+/g, ' ') : ''
  if (text === '') {
    return `第 ${index + 1} 轮`
  }
  return `第 ${index + 1} 轮 · ${text.length > 24 ? `${text.slice(0, 24)}…` : text}`
}

/**
 * 一轮收尾那一行：用量 + 用时。
 *
 * 两件事同一行，因为都是"这一轮花了多少"；中间用间距分开，不用中点串成一句。
 *
 * 时长不跟着用量折叠：用量点开才看明细（那是一份账单），而"跑了多久"是一眼就要
 * 知道的数；折起来等于把最常见的问题藏在点击后面。
 */
function TurnTail({
  item,
  change,
  startedAt,
  endedAt,
  stopped,
  onOpenFile,
  onOpenDiff,
}: {
  item: Extract<StreamItem, { kind: 'usage' }>
  change: TurnChanges | null
  startedAt: string | null
  endedAt: string | null
  /** 这一轮没正常跑完（失败 / 被打断 / 被拒绝停下） */
  stopped: boolean
  onOpenFile?: (path: string) => void
  onOpenDiff?: (commitSha: string, path: string) => void


}) {
  // 没跑完的轮次不报"用时"：那个数字在失败或被打断时说明不了什么，大半是等超时、
  // 等批准或白烧掉的，而人会把它读成"这一轮干了这么久"。
  const duration = stopped ? null : formatSpan(startedAt, endedAt)
  return (
    // 改动那一格排最后：它展开的文件列表很宽（一条长路径就能撑开整行），排中间的
    // 话一展开就把后面的东西挤到下一行，"这一轮用了多久"会跳位置
    <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1 border-t border-border/40 pt-2">
      <UsageRow
        input={item.input}
        output={item.output}
        cached={item.cached}
        context={item.context}
      />
      {duration !== null && <span className="text-xs text-loom-faint">{duration}</span>}
      <ChangesRow change={change} onOpenFile={onOpenFile} onOpenDiff={onOpenDiff} />
    </div>
  )
}

/**
 * 「这一轮改了 N 个文件」：点开是逐文件的增删。
 *
 * 必须问后端而不是数上面的工具行：命令也会改文件（格式化器、代码生成、`sed -i`、
 * 编译产物）。只数 `edit_file` / `write_file` 的话，一轮跑了 `mvn spotless:apply`
 * 改了十二个文件，这里会说"没改什么"，那比不说更糟。
 *
 * 这些数由后端在收尾时算好随流过来（`WorkspaceChanges`）：比的是每一步的提交和它
 * 的第一父提交，所以命令改的也算。不再每次读的时候现拿 git 算，也不受提交被 gc
 * 影响。
 */
function ChangesRow({
  change,
  onOpenFile,
  onOpenDiff,
}: {
  change: TurnChanges | null
  onOpenFile?: (path: string) => void
  onOpenDiff?: (commitSha: string, path: string) => void


}) {
  const [open, setOpen] = useDisclosure()
  if (change === null || change.files.length === 0) {
    return null
  }
  const { added, deleted } = changeTotals(change.files)

  return (
    <details
      className="group"
      open={open}
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className={`${FOLD_SUMMARY} w-fit items-baseline gap-1.5 text-xs`}>
        <FoldChevron />
        {/* 改动太多时后端只记了前一批，数字如实带"+"，不拿偏小的数当完整报告 */}
        改了 {change.files.length}{change.truncated ? '+' : ''} 个文件
        <span className="ml-1 font-mono">
          <span className="text-loom-added-mark">+{added}</span>{' '}
          <span className="text-loom-removed-mark">−{deleted}</span>
        </span>
      </summary>
      <ul className="mt-2 space-y-0.5 border-l-2 border-border pl-3 text-xs text-loom-faint">
        {change.files.map((file) => (
          <li key={file.path} className="flex items-baseline gap-3">
            {/* 路径可点。这里的点和流里别处不一样：别处打开"它现在是什么样"，这里
                打开"这一轮对它做了什么"，回滚后只剩后者看得见。没挂 onOpenDiff
                才退回打开文件本身 */}
            <button
              type="button"
              onClick={() =>
                onOpenDiff ? onOpenDiff(file.commitSha, file.path) : onOpenFile?.(file.path)
              }
              className="min-w-0 truncate text-left font-mono underline-offset-2 transition-colors hover:text-muted-foreground hover:underline"
            >
              {file.path}
            </button>
            <span className="ml-auto shrink-0 font-mono">
              {file.binary ? (
                // 二进制文件 git 给不出行数，只能说"它变了"；写 +0 −0 是在说没改。
                <span className="text-loom-faint/70">二进制</span>
              ) : (
                <>
                  <span className="text-loom-added-mark">+{file.added}</span>{' '}
                  <span className="text-loom-removed-mark">−{file.deleted}</span>
                </>
              )}
            </span>
          </li>
        ))}
      </ul>
    </details>
  )
}

/**
 * 一轮里那几条用量结算合成一条。
 *
 * 一轮会有好几条，因为后端的"一轮"是你发的那句话，落账单位却是执行：批准续跑会
 * 让后端把同一轮收尾两次（挂起一次、答完一次），两个 `TURN_TOKENS_USED` 落在同一条
 * 用户消息底下，界面上就出现两行"这一轮用了多少"。
 *
 * 不在后端合并：那两个事件是两次执行各自的账，分开记是对的——答完批准前服务挂了，
 * 前一段花的钱不能丢。让审计层迁就展示层是反的。
 *
 * token 相加（那句话真花的钱是两段之和）；上下文读数取最后一个，它是"收尾时上下文
 * 有多大"，两个时刻的规模相加没有意义。
 */
function mergedUsage(usages: StreamItem[]): Extract<StreamItem, { kind: 'usage' }> | null {
  let merged: Extract<StreamItem, { kind: 'usage' }> | null = null
  for (const item of usages) {
    if (item.kind !== 'usage') {
      continue
    }
    merged =
      merged === null
        ? item
        : {
            kind: 'usage',
            input: merged.input + item.input,
            output: merged.output + item.output,
            cached: merged.cached + item.cached,
            context: item.context ?? merged.context,
            at: item.at ?? merged.at,
          }
  }
  return merged
}

/** 一轮的起止时间。两个都来自服务端，所以差值不受浏览器时钟影响。 */
function turnSpan(turn: StreamItem[]): { startedAt: string | null; endedAt: string | null } {
  const opening = turn.find((item) => item.kind === 'user')
  // 用量那条是每轮收尾才写的（后端 finishTurn 和 checkpoint、状态迁移一起落），
  // 所以"这一轮跑完没有"看它就行，不必认状态迁移，也不必区分"等用户"和"等批准"
  // （那两个在事件流里长得一样）。
  //
  // 取最后一条而不是第一条：被批准打断时后端收尾两次（见 mergedUsage），取第一条
  // 的话用时只算到挂起那一刻，而"这一轮多久"包括你盯着批准按钮想的那几十秒。
  const closing = turn.findLast((item) => item.kind === 'usage')
  return {
    startedAt: opening?.kind === 'user' ? opening.at : null,
    endedAt: closing?.kind === 'usage' ? closing.at : null,
  }
}

/**
 * 「这一轮跑了多久」。
 *
 * 只在跑完之后显示：跑着时也能拿当前时间减开始时间，但那是浏览器的钟减服务端的钟，
 * 两台机器的时钟差多少这个数就差多少。旁边正在发生的事本来就在动，不缺这一处。
 */
function formatSpan(startedAt: string | null, endedAt: string | null): string | null {
  if (startedAt === null || endedAt === null) {
    return null
  }
  const start = Date.parse(startedAt)
  const end = Date.parse(endedAt)
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return null
  }
  const seconds = Math.max(0, Math.round((end - start) / 1000))
  if (seconds < 1) {
    return '不到 1 秒'
  }
  if (seconds < 60) {
    return `用时 ${seconds} 秒`
  }
  return `用时 ${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}

/**
 * 一轮用掉的 token。两层：外面一个总数，点开才是明细。
 *
 * 不铺在一行里：「输入 1,673 tokens，输出 53 tokens」在一行里放了两个没被解释过的
 * 量，等于没写。外面只说这一轮用了多少 token；想看某轮为什么贵再点开。
 *
 * 总数是两个数相加，不是另取一个：取来的那个迟早和这两个对不上。
 */
function UsageRow({
  input,
  output,
  cached,
  context,
}: {
  input: number
  output: number
  cached: number
  context: ContextReading | null
}) {
  const [open, setOpen] = useDisclosure()
  return (
    <details
      className="group"
      open={open}
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className={`${FOLD_SUMMARY} w-fit items-center gap-1.5 text-xs`}>
        <FoldChevron />
        {/* 说"这一轮用了多少"，不说"用量是多少"：后者把动作名词化了，读的人
            得先还原成句子 */}
        这一轮用了 {formatTokens(input + output)} tokens

        {/* 上下文跟在这一行后面，不混进那句话：它回答另一个问题（"还能聊多久"
            vs "这一轮花了多少"），用竖线隔开读成两件事。见 ContextRing */}
        {context !== null && (
          <>
            <span className="text-border">|</span>
            <ContextRing reading={context} />
            {/* "上下文"三个字不能省：光一个"19%"会让人以为它是上面 token 数的
                比例（比如缓存命中率）；一个数没有名字时，旁边那个数会替它编一个 */}
            上下文 {contextPercent(context)}%
          </>
        )}
      </summary>
      <dl className="mt-2 space-y-0.5 border-l-2 border-border pl-3 text-xs text-loom-faint">
        <div className="flex gap-2">
          <dt>输入</dt>
          <dd className="font-mono">{formatTokens(input)} tokens</dd>
        </div>
        <div className="flex gap-2">
          <dt>输出</dt>
          <dd className="font-mono">{formatTokens(output)} tokens</dd>
        </div>
        {/* 命中缓存的部分单价低得多，在 BYOK 下是一项钱，不是技术指标。放明细里：
            外面那行说"这一轮花了多少"，这一条回答"这笔钱怎么花掉的" */}
        {cached > 0 && (
          <div className="flex gap-2">
            <dt>其中缓存命中</dt>
            <dd className="font-mono">
              {formatTokens(cached)} tokens（{cacheHitPercent(cached, input)}）
            </dd>
          </div>
        )}
        {/* 环上百分比的分母写出来：比例不写分母，读的人只能猜 */}
        {context !== null && (
          <div className="flex gap-2">
            <dt>上下文</dt>
            <dd className="font-mono">
              {formatTokens(context.tokens)} / {formatTokens(context.window)} tokens
            </dd>
          </div>
        )}
      </dl>
    </details>
  )
}

/** 环的直径（像素）。外面那行是 text-xs，比一行字稍高一点就够。 */
const RING_SIZE = 13
const RING_RADIUS = 5
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS

/**
 * 上下文用了窗口的多少。
 *
 * 做成环而不是数字：它每一轮都出现一次，一串数字要逐个读才知道涨没涨，而弧度一扫
 * 就比得出来——一行行看下去能看出上下文一轮轮鼓起来、压缩那一下又掉下去。
 *
 * 快满了也不变色：到某个比例并不会出事，平台会去压缩。给不会出事的东西上红，等于
 * 把"红"这个信号用掉一次。
 */
function ContextRing({ reading }: { reading: ContextReading }) {
  const fraction = Math.min(1, reading.tokens / reading.window)
  return (
    <svg
      width={RING_SIZE}
      height={RING_SIZE}
      viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}
      // 语义由后面那行字（"上下文 19%"）担着，SVG 只是把同一件事画出来。
      aria-hidden
      className="shrink-0"
    >
      <circle
        cx={RING_SIZE / 2}
        cy={RING_SIZE / 2}
        r={RING_RADIUS}
        fill="none"
        strokeWidth="1.5"
        className="stroke-border"
      />
      <circle
        cx={RING_SIZE / 2}
        cy={RING_SIZE / 2}
        r={RING_RADIUS}
        fill="none"
        strokeWidth="1.5"
        strokeLinecap="round"
        className="stroke-muted-foreground"
        strokeDasharray={`${fraction * RING_CIRCUMFERENCE} ${RING_CIRCUMFERENCE}`}
        // 从十二点开始顺时针。SVG 的圆默认从三点钟起，不转这一下每个环都像被
        // 啃掉一口。
        transform={`rotate(-90 ${RING_SIZE / 2} ${RING_SIZE / 2})`}
      />
    </svg>
  )
}

/**
 * 折叠按钮长什么样。工具结果、diff、搜索结果共用这一串：它们做的是同一件事，
 * 长成三个样子只会让人每次重新认一遍。
 *
 * 平时没有下划线：它是一行辅助文字，不该和正文抢注意力；指上去才出现，回答"这个
 * 能按"。
 */
const TOGGLE =
  'text-loom-faint underline-offset-2 transition-colors hover:text-muted-foreground hover:underline'

/**
 * 折叠标题行共用的样子：整行可点、原生三角不要、灰、指上去变亮。
 * 三种行（思考 / 用量 / 改动）各自再加宽度和字号。
 */
const FOLD_SUMMARY =
  'flex cursor-pointer list-none text-loom-faint transition-colors hover:text-muted-foreground [&::-webkit-details-marker]:hidden'

/**
 * 折叠行左边那颗箭头。
 *
 * 它占固定的 16px 槽并垂直居中，所以不管这一行是 `items-baseline` 还是
 * `items-center`，箭头都落在同一条线上；三处各写一份时，一处靠 `translate-y`、
 * 一处靠 `items-center`，最后谁也不齐。
 */
function FoldChevron() {
  return (
    <span className="flex w-4 shrink-0 items-center self-center">
      <ChevronRight className="size-3 transition-transform group-open:rotate-90" />
    </span>
  )
}

/**
 * 工具结果和 diff 的折叠按钮上写什么。几个折叠按钮共用这一句话。
 */
function expandLabel(expanded: boolean, hidden: number): string {
  return expanded ? '收起' : hidden === 1 ? '还有 1 行' : `还有 ${hidden} 行`
}

/**
 * 一个极小的读数也要有个数，所以至少显示 1%。
 *
 * 四舍五入到 0 会让人以为环坏了：画面上有看得见的小弧，旁边写着 0%。
 */
function contextPercent(reading: ContextReading): number {
  const percent = Math.round((reading.tokens / reading.window) * 100)
  return Math.max(1, Math.min(100, percent))
}

/**
 * 缓存命中的比例，连同分母缺失时的那句话一起返回。
 *
 * 取不到输入（`input` 为 0）时写"无输入"：那时"命中 0%"和"没有输入"是两句不同
 * 的话，显示前者是在说一件没发生的事。
 */
function cacheHitPercent(cached: number, input: number): string {
  if (input <= 0) {
    return '无输入'
  }
  return `${Math.round((cached / input) * 100)}%`
}

/**
 * 这一轮是否还在跑。
 *
 * 用 context 是因为"展开/折起"是每个组件自己的 `useState`，而"这一轮变成历史时把
 * 它收回去"是跨组件的规矩，属于"轮"这一层。靠 props 传的话，Item → ToolLine →
 * ToolResult → DiffView 每一层都要多背一个无关布尔值。
 */
const LiveTurn = createContext(true)

/**
 * 折起/展开的状态。这一轮跑完之后自动收回折叠。
 *
 * 一轮跑下来，人可能点开三四处的细节；下一轮开始时屏幕该回到基线，否则展开的越积
 * 越多，一段对话滚下去全是摊开的细节。
 *
 * 写在渲染里而不是 effect 里（React 官方的"根据 prop 调整 state"写法）：同一次
 * 渲染生效，不会先画一帧"还开着"再收回；写在 effect 里会闪那一下，而它恰好发生在
 * 用户刚发出一条消息、正在看屏幕时。
 */
function useDisclosure(initialOpen = false): [boolean, (open: boolean) => void] {
  const live = useContext(LiveTurn)
  // 正在发生的细节可以默认展开（思考过程就是）；历史默认折着。
  // 不看 live：思考增量可能先于"这一轮在跑"的状态事件到达，等它再展开会漏掉开头
  const [open, setOpen] = useState(initialOpen)
  const [wasLive, setWasLive] = useState(live)
  if (wasLive !== live) {
    setWasLive(live)
    if (!live) {
      setOpen(false)
    }
  }
  return [open, setOpen]
}

/**
 * 模型的思考过程。折着，但折着的那一行一直在说话。
 *
 * 事件流一直带着 `AssistantMessage.reasoning`，折叠时没人接它，于是推理只在流式
 * 那一小段"活"过，消息落地就没了，想看完整内容没有入口。
 *
 * 折着不等于看不见：只有「思考过程」四个字的折叠条看起来和"这里没东西"没区别，
 * 所以人不会点。现在那一行右边跟着它正在想的那一句，一眼看得出方向，想看全文再点开。
 *
 * 摘要一段段往前推：取"最近一个写完的段落的第一行"，不取最后一行——那行每个字都在
 * 变，摘要会跟着抖成噪声。
 *
 * 整行都能点，不是只有小三角：折叠条本身就是一行字，去瞄一个比字还小的目标太难。
 *
 * 折着的时候不渲染 markdown：否则每帧都要重新解析整段思考，而人根本没在看。
 *
 * 正在思考时默认展开，答案一开始就自动折回去：思考是"它现在在想什么"，值得跟着看；
 * 答案开始后，人要看的是答案。手动折起/展开不跟着抖——只有 running 真的变化时才动。
 */
function Thinking({ text, running = false }: { text: string; running?: boolean }) {
  const [open, setOpen] = useDisclosure(running)
  const [wasRunning, setWasRunning] = useState(running)
  // 跟着 running 走：开始思考时展开，思考结束（答案开始或消息落地）时折回去。
  // 写在渲染里而不是 effect 里，避免先画一帧旧状态再跳。
  if (wasRunning !== running) {
    setWasRunning(running)
    setOpen(running)
  }
  const summary = thinkingSummary(text, running)

  return (
    <details
      className="group"
      open={open}
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className={`${FOLD_SUMMARY} w-full items-baseline gap-2 text-sm`}>
        {/* 一行里左边是行为（在思考），右边是可展开（箭头）：先认出是什么，
            再决定要不要展开 */}
        {/* 行首转开的箭头就是全部记号：折叠/展开本身说明了"这里还能打开"。它占
            固定宽度槽（见 FoldChevron），后面那列字才和工具行、正文对齐 */}
        <FoldChevron />
        <span className="shrink-0">思考</span>
        {summary !== '' && (
          <>
            <span aria-hidden className="shrink-0 text-loom-faint/50">
              ·
            </span>
            <span className={`min-w-0 truncate ${running ? 'loom-shimmer' : ''}`}>{summary}</span>
          </>
        )}
      </summary>
      {open && (
        <div className="mt-2 border-l-2 border-border pl-6 text-loom-faint">
          {/* 和正文同一个字号，只用颜色和左侧竖线表示"这是它想的，不是它说的"；
              中文斜体会糊，只留暗色 */}
          <Markdown>{text}</Markdown>
        </div>
      )}
    </details>
  )
}

/**
 * 折叠着的那一行显示什么。
 *
 * 写完的和正在写的取法不同：写完了取整段思考的第一行（那是它开始想的事，结论通常
 * 已在回答里说过）；还在写取"最近一个写完的段落"的第一行，判据是那一段第一行后面
 * 确实还有内容。
 *
 * 星号要剥掉：这一行是纯文本，不是 markdown。不剥的话模型写 `**关键**`，人看到的
 * 是四个星号夹着两个字。
 */
function thinkingSummary(text: string, running: boolean): string {
  const clean = (line: string) => line.replaceAll('**', '').trim()

  const paragraphs: string[][] = []
  let paragraph: string[] = []
  for (const line of text.split('\n')) {
    if (line.trim() === '') {
      if (paragraph.length > 0) paragraphs.push(paragraph)
      paragraph = []
    } else {
      paragraph.push(line)
    }
  }
  if (paragraph.length > 0) paragraphs.push(paragraph)

  const first = paragraphs[0]
  if (!running) {
    return first === undefined ? '' : clean(first[0])
  }
  for (let index = paragraphs.length - 1; index >= 0; index--) {
    const candidate = paragraphs[index]
    // 不是最后一段 → 一定写完了；是最后一段 → 至少得写到第二行，第一行才算写完。
    if (index < paragraphs.length - 1 || candidate.length > 1) {
      return clean(candidate[0])
    }
  }
  return ''
}

/**
 * 一次正在等批复的调用：agent 停在这儿，整轮不会往下走。
 *
 * 答复之后不用刷新：`ToolApprovalResolved` 从 SSE 推回来，卡片随之收掉（不另画
 * 一行"某某批准了"——能批的只有会话主人自己），执行器随后续跑，后面的内容接着长出来。
 */
function PendingApproval({
  sessionId,
  callId,
  reason,
  canApprove,
  speaker,
}: {
  sessionId: string | null
  callId: string
  /** 后端为什么要问。空串 = 老事件没有这一项 */
  reason: string
  canApprove: boolean
  /** 等的是谁的批准。观战时是会话主人的名字，不是正在看的人 */
  speaker: string
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function answer(approved: boolean) {
    if (sessionId === null) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      await approvals.resolve(sessionId, callId, approved)
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
      {/* 不是 loom-faint：这一刻 agent 是停着的，这一行是全屏唯一在做事的入口 */}
      {/* 等谁批要说对：观战的人没有按钮，对他说"等你批准"是一句他做不到的话 */}
      <span className="text-xs text-muted-foreground">等{speaker}批准一次调用</span>

      {/* 为什么问你。这是后端判据说的（见 CommandApproval）：三种情况要人过目的
          东西完全不同，不说的话这个"批准"就是走过场。老事件没有这一项，不显示空话 */}
      {reason !== '' && <span className="text-xs text-loom-faint">{reason}</span>}

      {canApprove && (
        <span className="flex items-baseline gap-1.5">
          {/* 批准是这一刻唯一推动事情的动作（整轮停着等它），所以是真按钮不是
              一行字：文字按钮会被扫过去，而 agent 停着时每一秒都是用户在等 */}
          <Button size="xs" disabled={busy} onClick={() => void answer(true)}>
            批准
          </Button>
          <Button size="xs" variant="outline" disabled={busy} onClick={() => void answer(false)}>
            拒绝
          </Button>
        </span>
      )}

      {error && (
        <span role="alert" className="text-xs text-destructive">
          {error}
        </span>
      )}
    </div>
  )
}

/**
 * 一次工具调用：一行调用，有的还有一行结果。
 *
 * 结果行只在它带着信息时出现：搜索命中、写文件结论、失败报错是这一步的产出；
 * "成功读完一个文件""一条成功命令的 stdout"不是——前者只是把调用行又数了一遍，
 * 后者是回执。判据集中在 {@link resultWorthShowing}。
 *
 * 结果单独一行而不是挤进调用行：若只有调用那行，人看得出它跑了、看不出跑出了
 * 什么；一次 `read_file` 和一次 `write_file` 长得一样，而它们对代码做的事完全
 * 不同。完整输出不是给人读的（那是给模型读的），所以只给前几行，多的折起来。
 *
 * 结果行用缩进表示父子关系，不套盒子：几十次调用几十个盒子会把页面变成仪表盘。
 *
 * 失败时调用行只变红，不换内容：我们的结果行本来就默认露出三行、是红的，"错在哪"
 * 一直看得见；把错误提上来会和参数摘要抢宽度，实测两样都被截断。这一行只负责
 * "它跑的是什么"，错在哪下一行完整地说。
 */
function ToolLine({
  name,
  args,
  outcome,
  unfinished,
  speaker,
  onOpenFile,
}: {
  name: string
  args: string
  outcome: ToolOutcome | null
  /** 非空 = 它存在过、但没有结果。见 {@link Unfinished} */
  unfinished: Unfinished | null
  /** 这条会话的主人怎么称呼。拒绝是他做的，不是正在看的人 */
  speaker: string
  /** 点那个路径打开它。不传就只是一行字（观战时不留点了没反应的入口） */
  onOpenFile?: (path: string) => void


}) {
  const failed = outcome !== null && !outcome.success
  // 正在跑要两个条件：没有结果，也没有"没跑完"这个事实。少了后者，崩溃恢复后那条
  // 调用会一直闪。规则只在一处：toolIsRunning。
  const running = toolIsRunning(outcome, unfinished)
  // 这个工具声明的样子。取不到就是空，下面几处各自退化成显示工具名/原始参数。
  const tool = useTool(name)
  // 参数串只解析一遍：主语、路径、diff 都要用它，各解析一次等于同一个 JSON 解三遍。
  const parsed = useMemo(() => parseArgs(args), [args])
  const detail = summarize(tool, parsed)
  const path = pathOf(tool, parsed)
  /** `write_file` 的正文：新建时拿它画代码预览（覆盖时不用，见 ToolResult） */
  const written = typeof parsed?.content === 'string' ? parsed.content : null
  // 主语是外部网址时（抓取），这行本身就是入口，结果里不再重复一条同样的链接。
  const link = tool?.shape === 'web' ? externalUrl(detail) : null
  // 这次调用改了什么只算一遍：下面那一行也要用它画 diff。两处各算一遍 LCS 的话，
  // 流式期间父组件每帧重渲染，等于每帧算两遍；outcome 的引用只在结果到达时换。
  const diff = useMemo(() => diffOf(tool, parsed, outcome), [tool, parsed, outcome])

  // 调用行右侧那一小块，两副面孔不会同时出现：没跑完 → 为什么没跑完；失败 →
  // 退出码（只有真的跑起来过才有）。
  //
  // 成功时什么都不写：这一步做了什么，它自己那一行已经说了；改了多少行去 diff 里数。
  // "跑完了但退出码是 1"和"进程压根没跑起来"是两件事，后者不用再写一句话：行首
  // 红点已经把"这一行没成"说完了。
  const suffix =
    unfinished !== null
      ? unfinished === 'rejected'
        ? `${speaker}拒绝了`
        : UNFINISHED_WORD[unfinished]
      : failed && outcome !== null && outcome.exitCode !== null
        ? `退出码 ${outcome.exitCode}`
        : null

  return (
    // 和正文一样大（14px）。层级靠颜色和字重，不靠把工具行缩小一号：缩小之后一段
    // 流水就是一屏小字，眼睛要来回换档。
    <div className="text-sm">
      <p className="flex items-baseline gap-2">
        {/* 行首只有一个点：状态由颜色和呼吸说，行为由紧跟着的动词说。见 StatusDot */}
        <span className="flex w-4 shrink-0 items-center self-center">
          <StatusDot
            running={running}
            failed={failed}
            unfinished={unfinished !== null}
            label={stateWord(outcome, unfinished, speaker)}
          />
        </span>

        {/* 工具名是动词，不是接口名：名字回答"在发生什么"，不是"调了哪个 API" */}
        <span
          className={`shrink-0 ${running ? 'loom-shimmer' : ''} ${
            failed ? 'text-destructive' : 'text-muted-foreground'
          }`}
        >
          {verb(tool, name)}
        </span>

        {detail !== '' &&
          (path !== null && onOpenFile !== undefined ? (
            // 参数里的路径点得开：中栏就是看文件的地方，人读到"修改
            // HelloWorld.java"时想看的正是它。
            <button
              type="button"
              onClick={() => onOpenFile(path)}
              className={`min-w-0 truncate font-mono text-loom-faint underline-offset-2 transition-colors hover:text-muted-foreground hover:underline ${
                running ? 'loom-shimmer' : ''
              }`}
            >
              {detail}
            </button>
          ) : link !== null ? (
            // 网址点得开：和文件路径同理，只是开到浏览器里。
            <a
              href={link}
              target="_blank"
              rel="noreferrer"
              className={`min-w-0 truncate font-mono text-loom-faint underline-offset-2 transition-colors hover:text-muted-foreground hover:underline ${
                running ? 'loom-shimmer' : ''
              }`}
            >
              {detail}
            </a>
          ) : (
            <span
              className={`min-w-0 truncate font-mono text-loom-faint ${running ? 'loom-shimmer' : ''}`}
            >
              {detail}
            </span>
          ))}

        {/* 右侧那一小块放最右边且不参与省略：一行只该有一处被省略。推到边上靠它
            自己的 ml-auto，再垫 flex-1 是做同一件事的第二遍 */}
        {suffix !== null && (
          <span className="ml-auto shrink-0 font-mono text-loom-faint">{suffix}</span>
        )}
      </p>

      {/* 还没有结果时没有结果行：上面那个「…」已经在说；没跑完的也没有，原因在
          右边那一格 */}
      {outcome !== null && resultWorthShowing(tool, outcome) && (
        <ToolResult outcome={outcome} diff={diff} path={path} written={written} />
      )}
    </div>
  )
}

/**
 * 「没跑完」在右端那一格怎么写。
 *
 * 两种分开说，因为它们让人做的事不一样：被取消是我干的（按了停，不用查），
 * 中断是平台没的（意外，值得看一眼）。
 */
const UNFINISHED_WORD: Record<Exclude<Unfinished, 'rejected'>, string> = {
  cancelled: '被取消',
  interrupted: '进程中断',
  'no-result': '没有结果',
}

/**
 * 行首那一个点：工具调用的全部视觉标记。
 *
 * 不按工具分类给图标，只用一个 7px 圆点说状态——运行中呼吸、成功绿、失败红、
 * 没跑完是暗的。行为由紧跟着的动词说，不需要再来一个图标说一遍。
 *
 * 这颗绿点比 diff 的 `loom-added-mark` 亮，两者不是同一个东西：它是一行只有一颗的
 * "状态"，diff 记号一行一颗、铺满整个改动，亮成那样会抢走正文。别合并成一个令牌。
 */
function StatusDot({ running, failed, unfinished, label }: {
  running: boolean
  failed: boolean
  unfinished: boolean
  /** 读屏器念的状态；视觉状态由颜色和右端承担 */
  label: string
}) {
  const color = failed
    ? 'bg-destructive'
    : unfinished
      ? 'bg-loom-faint/40'
      : running
        ? 'bg-loom-faint'
        : 'bg-emerald-400'
  return (
    <span role="img" aria-label={label} className="relative size-[7px] shrink-0 self-center">
      {/* 跑着的时候外面再扩一圈：静止的圆点只说明"这里有个调用"，
          呼吸的圈才说明"它现在还在跑" */}
      {running && (
        <span
          aria-hidden
          className={`absolute inset-0 animate-ping rounded-full opacity-40 motion-reduce:hidden ${color}`}
        />
      )}
      <span className={`relative block size-[7px] rounded-full transition-colors ${color}`} />
    </span>
  )
}

/** 一次调用的结果。最多三行，多的折起来。 */
const RESULT_PREVIEW_LINES = 3

/**
 * 搜索结果折叠时显示几条。
 *
 * 四条：会话栏比 deepseek-harness 的正文列窄，一行一条时四条刚好一屏，八条就
 * 成了一面墙。
 * 多出来的靠"展开其余 N 条"。
 */
const SOURCE_PREVIEW_ROWS = 4

/** diff 一次显示几行。十二行：改一个函数差不多就是这个量，再多该折了。 */
const DIFF_PREVIEW_ROWS = 12

/**
 * 新建文件时代码预览显示几行。
 *
 * 10 是"一眼看得出写了什么"的量，而不是"把文件铺开"；多的走「还有 N 行」。
 */
const CREATED_FILE_PREVIEW_LINES = 10

/**
 * 这次调用的结果是否值得单独占一行。
 *
 * 失败必须说：报错是接下来那条路的入口，藏起来等于让人自己重跑一遍命令。
 *
 * 成功时读文件和跑命令都不说：读文件的结果是一整份内容，流里没人读它；"读了 N 行"
 * 只是把调用行又数了一遍。命令 stdout 是回执，不是这一步做了什么，那由命令那一行
 * 和 agent 随后的正文说。
 *
 * 别的工具的成功结果留着：搜索结果是可点的链接、写文件是新建的代码（覆盖时只给
 * 结论）、抓取是标题，都是这一步真正的产出。认不出工具声明时照旧显示：宁可多一行，
 * 也不要把有用的结果藏掉。
 */
function resultWorthShowing(tool: ToolView | undefined, outcome: ToolOutcome): boolean {
  if (!outcome.success) {
    return true
  }
  return tool?.shape !== 'read' && tool?.shape !== 'execute'
}

/**
 * 一次调用的结果。
 *
 * 改了文件的调用，结果是 diff 不是一句话：`edit_file` 的文本是"已修改 X（替换 20
 * 字符为 24 字符）"，说了改了多少，没说改成了什么；而读代码的人要知道后者。
 *
 * diff 不用问后端：`edit_file` 的参数里就带着 `old_string` 和 `new_string`，前端
 * 自己能算（见 {@link diffOf}）。
 */
function ToolResult({
  outcome,
  diff,
  path,
  written,
}: {
  outcome: ToolOutcome
  /** 这次调用改了什么，由上面那一行算好传下来，不在这里重算 */
  diff: DiffRow[] | null
  /** 主语路径（工具声明的主体）。上面那一行已解析过参数，这里不再解析 */
  path: string | null
  /** `write_file` 的正文（参数里的 `content`）；别的工具或覆盖时是 null */
  written: string | null
}) {
  // 后端只给界面看的结构化结果。缺失 / 坏数据 / 旧事件都是 null，那时照旧渲染文本。
  const web = outcome.presentation ?? null

  return (
    <div className="mt-1 flex gap-2">
      {/* 转角是"这一行属于上面那次调用"的记号。终端里是 ⎿，网页里用 SVG；
          它和上面那个点占同样宽的槽，于是结果文字与调用行文字对齐 */}
      <span className="flex w-4 shrink-0 items-start">
        <CornerDownRight
          aria-hidden
          className={`mt-1 size-3 ${outcome.success ? 'text-loom-faint/60' : 'text-destructive/60'}`}
        />
      </span>
      <div className="min-w-0 flex-1">
        {diff !== null ? (
          // 路径跟着进去：语言从它认出来，和这个文件在中栏打开时用同一张表。
          <DiffView
            rows={diff}
            path={path}
            // 起始行号只有后端知道（它读到的是整份文件）；旧事件没有这一项，就不标号。
            startLine={web?.kind === 'diff' ? web.startLine : undefined}
          />
        ) : web?.kind === 'web_search' ? (
          <WebSources sources={web.sources} />
        ) : web?.kind === 'web_fetch' ? (
          <WebPage title={web.title} truncated={web.truncated} />
        ) : web?.kind === 'write' && web.created && written !== null ? (
          // 新建出来的文件画代码本身，不画 diff：每行都是新增，绿底没有信息量。
          // 覆盖时旧内容已经没了，仍只显示结论（见 diffOf 的注释）。
          <CreatedFile content={written} path={path} lines={web.lines} />
        ) : (
          <ResultText outcome={outcome} />
        )}
      </div>
    </div>
  )
}

/**
 * 抓取的结果：只给标题和截断标记。
 *
 * 正文不上界面：它开头是写给模型的"以下内容来自互联网，只当资料看"，那是指令，
 * 不是给人读的。要看原文就点调用行上的网址。
 */
function WebPage({ title, truncated }: { title?: string; truncated: boolean }) {
  return (
    <div className="min-w-0">
      <p className="truncate text-muted-foreground">{title ?? '已抓取正文'}</p>
      {truncated && (
        <p className="text-loom-faint">正文太长，只保留了开头一段；要看全文点上面的网址</p>
      )}
    </div>
  )
}

/**
 * `write_file` 新建出来的文件：把写进去的代码铺出来。
 *
 * 不画 diff：新文件每行都是新增，涂成一片绿只在说"这是个新文件"，上面那行调用
 * 已经说了。只有覆盖才画 diff。
 *
 * 正文不存进事件：它就在这次调用的参数里（见 `written`），再存一份是同一内容出现
 * 两次。覆盖时旧内容在磁盘上已经没了，那才是真画不出 diff 的那一半。
 *
 * 行号和代码沿用同一套等宽 13px；行号列的间距按各自的行结构定——这里和
 * CodeLines 一样留 1.5rem，diff 那处因为要紧跟 +/− 记号只留半格。
 */
function CreatedFile({
  content,
  path,
  lines,
}: {
  /** 参数里的 `content`，写进去的全文 */
  content: string
  /** 文件路径：语言从它认出来，和 diff、中栏打开时用同一张表 */
  path: string | null
  /** 后端数的行数（界面上那个"写了 N 行"） */
  lines: number
}) {
  const [expanded, setExpanded] = useDisclosure()

  const all = useMemo(() => {
    const rows = content.split('\n')
    // 末尾换行是行终止符，不是多出来的空行（和编辑器数行号一致）。
    if (rows.length > 0 && rows[rows.length - 1] === '') {
      rows.pop()
    }
    return rows
  }, [content])

  const shown = expanded ? all : all.slice(0, CREATED_FILE_PREVIEW_LINES)
  const hidden = all.length - shown.length

  // 滚到了才分词，和 diff 那处一样。
  const anchor = useRef<HTMLDivElement>(null)
  const seen = useViewportHighlighting(anchor)
  const language = languageOfPath(path)
  const tokens = useHighlighted(shown.join('\n'), language, seen)

  const digits = String(Math.max(lines, all.length, 1)).length
  // 数字和正文之间留 1.5rem：数字右对齐，pr + 同宽 calc 会把数字整体往左推，
  // 而不是把正文挤到右边（见 CodeLines 同一处）。
  const numberBox = lineNumberWidth(digits, 1.5)

  if (all.length === 0) {
    return <p className="text-loom-faint">（空文件）</p>
  }

  return (
    <div ref={anchor} className="min-w-0">
      <p className="mb-1 text-xs text-loom-faint">{lines} 行</p>
      <div className="font-mono text-[13px] leading-5">
        {shown.map((line, index) => (
          <div key={index} className="flex">
            <span
              style={{ width: numberBox }}
              className="shrink-0 select-none pr-6 text-right text-loom-faint/50"
            >
              {index + 1}
            </span>
            <span className="whitespace-pre-wrap break-all">
              <DiffLineText tokens={tokens?.[index] ?? null} plain={line} segments={null} changedClass="" />
            </span>
          </div>
        ))}
        {(hidden > 0 || expanded) && (
          <div className="flex">
            {/* 按钮和代码同一列：它属于这段代码，不属于行号那一列 */}
            <span className="shrink-0" style={{ width: numberBox }} />
            <button type="button" onClick={() => setExpanded(!expanded)} className={TOGGLE}>
              {expandLabel(expanded, hidden)}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

/**
 * 搜索结果卡片：折叠时一条来源一行（标题 + 域名/日期），展开时每条多两行摘要。
 *
 * 它比给模型看的文本多出来的只有一样：能点开的链接。文本里的 URL 是排给模型读的，
 * 人要点的是标题。
 */
function WebSources({ sources }: { sources: WebSource[] }) {
  const [expanded, setExpanded] = useDisclosure()
  const shown = expanded ? sources : sources.slice(0, SOURCE_PREVIEW_ROWS)
  const hidden = sources.length - shown.length

  return (
    <div className="min-w-0 space-y-1">
      {shown.map((source, index) => {
        const day = dayOf(source.date)
        // 和调用行那个网址同一个守卫（只认 http/https）：来源链接也是外面来的，
        // 不能因为"长得像网址"就塞进 href。
        const href = externalUrl(source.url)
        const label = source.title ?? hostOf(source.url)
        // 标题里已带站名时（"Change Log | DeepSeek API Docs"）不再重复，只留日期。
        const site = source.siteName !== undefined && !(source.title ?? '').includes(source.siteName)
          ? source.siteName
          : source.siteName === undefined ? hostOf(source.url) : undefined
        const last = index === shown.length - 1
        return (
          <div key={source.url} className="min-w-0">
            {/* 一条来源一行：标题在前，来源和日期在后。摘要只在展开时出现：
                折叠时八条各占三行会是一面墙 */}
            <p className="flex min-w-0 items-baseline gap-1.5">
              {href === null ? (
                // 认不出是 http/https：照常显示标题，但不做成链接——点一个
                // javascript: 或 file: 不是"打开来源"。
                <span className="min-w-0 truncate text-muted-foreground">{label}</span>
              ) : (
                <a
                  href={href}
                  target="_blank"
                  rel="noreferrer"
                  className="min-w-0 truncate text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
                >
                  {label}
                </a>
              )}
              <span className="shrink-0 text-loom-faint">
                {[site, day].filter((part) => part !== undefined && part !== '').join(' · ')}
              </span>
              {/* 折叠时：接在最后一行末尾、靠右（官网的「查看全部」也在那一行） */}
              {last && !expanded && hidden > 0 && (
                <button
                  type="button"
                  onClick={() => setExpanded(!expanded)}
                  className={`ml-auto shrink-0 ${TOGGLE}`}
                >
                  {`展开其余 ${hidden} 条`}
                </button>
              )}
            </p>
            {expanded && source.snippet !== undefined && (
              <p className="line-clamp-2 text-loom-faint">{source.snippet}</p>
            )}
          </div>
        )
      })}
      {/* 展开后：收起放在整个列表末尾、靠右——挂最后一条来源上会跟着它的长度跑，
          而那一条往往还有摘要，按钮就飘到列表中间 */}
      {expanded && (
        <div className="flex justify-end">
          <button
            type="button"
            onClick={() => setExpanded(false)}
            className={TOGGLE}
          >
            收起
          </button>
        </div>
      )}
    </div>
  )
}


/** 不是文件改动、也没有卡片可画的那些调用，结果就是一段文本。 */
function ResultText({ outcome }: { outcome: ToolOutcome }) {
  const [expanded, setExpanded] = useDisclosure()
  const lines = resultLines(outcome)
  const shown = expanded ? lines : lines.slice(0, RESULT_PREVIEW_LINES)
  const hidden = lines.length - shown.length

  return (
    <>
      <pre
        className={`whitespace-pre-wrap break-words font-mono text-[13px] leading-5 ${
          outcome.success ? 'text-muted-foreground' : 'text-destructive'
        }`}
      >
        {shown.join('\n')}
      </pre>
      {(hidden > 0 || expanded) && (
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className={`mt-0.5 ${TOGGLE}`}
        >
          {expandLabel(expanded, hidden)}
        </button>
      )}
    </>
  )
}

/**
 * 一段改动：未变的上下文、删掉的行、加上的行。
 *
 * 行号从后端来：前端只有被替换的那一段，不知道绝对位置；那个数字只有真正读到文件
 * 的后端知道，随结果带过来（`startLine`）。没带就不标号：按提交看的整段改动就是
 * 那种（它可能跨几个 hunk）。
 *
 * 删除的行标旧行号（它在新文件里已经没有了），其余标新行号：往下读到的号连续递增，
 * 和打开改完后的文件看到的一致。列宽按最大行号的位数定，用 `ch`。
 *
 * 一对删/加行里再标一层词：一行三十个字符只换了一个数，整行涂深只说明这行变了；
 * 把变的词再深一档，读的人才知道变的是什么。哪几个词换了由 {@link wordDiffs} 算。
 *
 * 颜色不是唯一信号：底色只帮忙扫，`+` / `−` 才是真正的信息，读屏器也说得出来。
 *
 * 默认只露前 {@link DIFF_PREVIEW_ROWS} 行（会话流里的内联结果）；中栏按提交打开的
 * 整份文件把 `previewRows` 传成总行数，并先滚到第一处增删。
 */
export function DiffView({
  rows,
  path,
  startLine,
  previewRows = DIFF_PREVIEW_ROWS,
  focusFirstChange = false,
}: {
  rows: DiffRow[]
  path: string | null
  /** 这一段在文件里的起始行号（后端随结果带过来）；没有就不标号 */
  startLine?: number
  /**
   * 收起时露出多少行。
   *
   * 会话流里的工具结果用默认值，避免一屏都是 diff；中栏按提交打开的整份文件传
   * `rows.length`，直接铺满，不再要求人点一次"还有 N 行"。
   */
  previewRows?: number
  /**
   * 整份文件打开时先滚到第一处改动。
   *
   * 上下文铺满之后文件会从第一行开始；人点"这一轮的改动"时先想看的仍是改了什么，
   * 所以落点放在第一处增删上，前面的行还在，往上滚就能看。
   */
  focusFirstChange?: boolean
}) {
  const [expanded, setExpanded] = useDisclosure()
  const shown = expanded ? rows : rows.slice(0, previewRows)
  const hidden = rows.length - shown.length

  // 滚到了才分词，和别处一样：一段流水里可能挂几十个 diff。
  const anchor = useRef<HTMLDivElement>(null)
  const seen = useViewportHighlighting(anchor)
  const language = languageOfPath(path)

  // 整份文件从第一行铺开，但落点先放到第一处增删：人点开时先要看的是改了什么，
  // 前面的上下文已经在上方，往上滚就有
  useEffect(() => {
    if (!focusFirstChange) {
      return
    }
    anchor.current?.querySelector('[data-diff-change]')?.scrollIntoView({ block: 'center' })
  }, [focusFirstChange, rows])

  // 两侧各自当成一整份文档去分词，再按行贴回去。
  //
  // 一行一行单独分词不行：块注释、多行字符串、模板字符串都跨行，单独看每行会把
  // 中间那几行当普通代码上错色。语言从被改文件的路径认，和中栏打开时同一张表。
  const newTokens = useHighlighted(sideText(rows, 'removed'), language, seen)
  const oldTokens = useHighlighted(sideText(rows, 'added'), language, seen)

  // 词级差异按整段算：收起时露出的几行也可能和后面的行配成对。
  const words = useMemo(() => wordDiffs(rows), [rows])

  /**
   * 行号列宽度。先走一遍两侧的行号游标拿到最大数，不能拿"显示出来的行数"估：
   * 中间那些 gap 也占行号。
   *
   * 两种来源：统一 diff 的行自带号（按提交看的入口）；会话里那段改动只有起始行，
   * 按增删行数现场推。
   */
  const numberDigits = useMemo(() => {
    let max = startLine ?? 0
    for (const row of rows) {
      if (row.kind !== 'gap' && row.line !== undefined) {
        max = Math.max(max, row.line)
      }
    }
    if (startLine !== undefined) {
      let oldLine = startLine
      let newLine = startLine
      for (const row of rows) {
        if (row.kind === 'gap') {
          oldLine += row.hidden
          newLine += row.hidden
          max = Math.max(max, oldLine, newLine)
          continue
        }
        max = Math.max(max, row.kind === 'removed' ? oldLine : newLine)
        if (row.kind === 'removed') {
          oldLine++
        } else if (row.kind === 'added') {
          newLine++
        } else {
          oldLine++
          newLine++
        }
      }
    }
    // 数字两侧各留一个字符的空。
    return max > 0 ? String(max).length : null
  }, [rows, startLine])

  // 数字和正文之间只留半格，比 CreatedFile / CodeLines 窄：diff 的行号后面紧跟
  // +/− 记号，留宽了正文会被挤到右边。数字右对齐 + 同宽的 calc，数字整体往左推，
  // 正文起点不变。
  const numberBox = numberDigits === null ? null : lineNumberWidth(numberDigits, 0.5)

  // 每一侧各有一个游标：碰到这一侧的行就往下走一格。它们只在这次渲染里活，
  // shown 又是 rows 的前缀，所以两个游标始终对得上。
  let newAt = 0
  let oldAt = 0

  // 行号：旧、新各一个游标，从后端给的那一行起数。未变的行两边一起走，删行只走旧、
  // 增行只走新；改动前后错开的那几行自然对齐。
  let oldLine = startLine ?? 0
  let newLine = startLine ?? 0

  return (
    <div ref={anchor} className="font-mono text-[13px] leading-5">
      {shown.map((row, index) => {
        if (row.kind === 'gap') {
          if (startLine !== undefined) {
            oldLine += row.hidden
            newLine += row.hidden
          }
          // 和正文同一个左边界：读的时候眼睛停在代码那一列，不该为这句话往左跑。
          return (
            <div key={index} className="flex text-loom-faint/70">
              {numberBox !== null && <span className="shrink-0" style={{ width: numberBox }} />}
              <span className="w-4 shrink-0" />
              {`… ${row.hidden} 行未变`}
            </div>
          )
        }
        const added = row.kind === 'added'
        const removed = row.kind === 'removed'
        // 上下文行两侧都有：颜色从新侧取（两边文字一样），但旧侧游标也要走；
        // 少了这一步，后面的删行会取到前面某行上下文的分词，显示另一行的内容。
        const tokens = removed ? oldTokens?.[oldAt++] : newTokens?.[newAt++]
        if (row.kind === 'context') {
          oldAt++
        }
        // 删除行标旧号，其余标新号。行自带号时优先用它（统一 diff 每行都知道自己
        // 是第几行），
        // 只有只知道起点的改动才用游标数。
        const number = row.line ?? (startLine === undefined ? null : removed ? oldLine : newLine)
        if (startLine !== undefined) {
          if (removed) {
            oldLine++
          } else if (added) {
            newLine++
          } else {
            oldLine++
            newLine++
          }
        }
        // 词级差异：删行用 removed 侧，加行用 added 侧。
        const segments = removed
          ? words[index]?.removed ?? null
          : added
            ? words[index]?.added ?? null
            : null
        return (
          <div
            key={index}
            data-diff-change={added || removed ? 'true' : undefined}
            className={`flex ${added ? 'bg-loom-added' : removed ? 'bg-loom-removed' : ''}`}
          >
            {number !== null && (
              <span
                style={{ width: numberBox ?? undefined }}
                className={`shrink-0 select-none pr-2 text-right ${
                  added
                    ? 'text-loom-added-mark/70'
                    : removed
                      ? 'text-loom-removed-mark/70'
                      : 'text-loom-faint/50'
                }`}
              >
                {number}
              </span>
            )}
            {/* 语义挂在这个记号上（role="img"），旁边不再加 sr-only：加了的话，
                复制这段 diff 会得到每行前面一个「增加」。记号本身还是 + / −，
                复制出去正好是它该有的样子 */}
            <span
              role="img"
              aria-label={added ? '增加' : removed ? '删除' : '未变'}
              className={`w-4 shrink-0 select-none text-center ${
                added
                  ? 'text-loom-added-mark'
                  : removed
                    ? 'text-loom-removed-mark'
                    : 'text-loom-faint/50'
              }`}
            >
              {added ? '+' : removed ? '−' : ' '}
            </span>
            <span
              className={`whitespace-pre-wrap break-all ${
                tokens === undefined || tokens === null
                  ? added || removed
                    ? 'text-muted-foreground'
                    : 'text-loom-faint'
                  : ''
              }`}
            >
              <DiffLineText
                tokens={tokens ?? null}
                plain={row.text}
                segments={segments}
                changedClass={added ? 'bg-loom-added-word' : 'bg-loom-removed-word'}
              />
            </span>
          </div>
        )
      })}
      {/* 折叠按钮和正文同一列：它属于这段代码，不属于左边那两列 */}
      {(hidden > 0 || expanded) && (
        <div className="flex">
          {numberBox !== null && <span className="shrink-0" style={{ width: numberBox }} />}
          <span className="w-4 shrink-0" />
          <button type="button" onClick={() => setExpanded(!expanded)} className={TOGGLE}>
            {expandLabel(expanded, hidden)}
          </button>
        </div>
      )}
    </div>
  )
}

/**
 * 一侧的完整文本：把这一侧的行按顺序拼起来交给分词器。
 *
 * `gap` 行两侧都不属于：它代表"这里有几行没显示"，那几行没被加也没被删。
 */
function sideText(rows: DiffRow[], drop: 'added' | 'removed'): string {
  const lines: string[] = []
  for (const row of rows) {
    if (row.kind === 'gap' || row.kind === drop) {
      continue
    }
    lines.push(row.text)
  }
  return lines.join('\n')
}

/**
 * 一行的文字。有分词就按 token 上色，没有就原样吐出；再叠上"哪几个词真换了"那层
 * 更深的底。
 *
 * memo 是必要的：流式期间父组件每帧重渲染，而 token 在两次分词之间是同一个引用。
 */
const DiffLineText = memo(function DiffLineText({
  tokens,
  plain,
  segments,
  changedClass,
}: {
  tokens: ThemedToken[] | null
  plain: string
  /** 词级差异；null = 整行没有可单独标的 */
  segments: DiffSegment[] | null
  /** 真正换了的那几个词上那层更深的底 */
  changedClass: string
}) {
  if (segments === null) {
    if (tokens === null) {
      return <>{plain}</>
    }
    return (
      <>
        {tokens.map((token, index) => (
          <span key={index} style={tokenStyle(token)}>
            {token.content}
          </span>
        ))}
      </>
    )
  }

  return (
    <>
      {piecesOf(tokens, segments).map((piece, index) => (
        <span
          key={index}
          style={piece.color === undefined ? undefined : { color: piece.color }}
          className={piece.changed ? changedClass : undefined}
        >
          {piece.text}
        </span>
      ))}
    </>
  )
})

/**
 * 把"按变化切好的段"和"按语法切好的 token"叠成同一串 span。
 *
 * 两套切法互不相干（一套按改没改、一套按语法），按字符位置求交集：游标在 token 里
 * 往前走，走到段的边界就换一个 changed 标记。`tokens` 为 null 时整段原样切成 span，
 * 只是不上色。
 */
function piecesOf(
  tokens: ThemedToken[] | null,
  segments: DiffSegment[],
): { text: string; color?: string; changed: boolean }[] {
  const pieces: { text: string; color?: string; changed: boolean }[] = []
  let tokenAt = 0
  let used = 0
  for (const segment of segments) {
    let left = segment.text.length
    while (left > 0 && tokens !== null && tokenAt < tokens.length) {
      const token = tokens[tokenAt]!
      const take = Math.min(left, token.content.length - used)
      pieces.push({
        text: token.content.slice(used, used + take),
        color: token.color,
        changed: segment.changed,
      })
      used += take
      left -= take
      if (used >= token.content.length) {
        tokenAt++
        used = 0
      }
    }
    // 分词器没吐全（认不出语言、还没分词、或两边对不齐）：剩下的原样补上。
    if (left > 0) {
      pieces.push({ text: segment.text.slice(segment.text.length - left), changed: segment.changed })
    }
  }
  return pieces
}

/**
 * 结果行说什么。
 *
 * 走到这里的都是该露出来的那些（读文件、跑命令的成功结果已在
 * {@link resultWorthShowing} 被挡掉）：一句话结论、搜索命中行、或者命令报错，
 * 原样铺出来就对。
 *
 * 空输出也要有一句话：一片空白让人分不清"没输出"还是"没加载出来"。
 */
function resultLines(outcome: ToolOutcome): string[] {
  const text = outcome.output.trim()
  if (text === '') {
    return ['（没有输出）']
  }
  const lines = text.split('\n')
  return outcome.truncated ? [...lines, '（后面的输出被截断了）'] : lines
}

/**
 * 这次调用的动作词。
 *
 * 事件流存的是接口名（`read_file`），那是给机器看的：下划线、英文、说的是哪个函数。
 * 人话那一面由工具自己声明（见 {@link ToolView.label}）；前端按工具名查表会随工具
 * 增长而过期，症状只是"显示得糙"。
 *
 * 取不到声明就原样显示工具名：多一个工具的代价是没翻译，而不是显示错。
 */
function verb(tool: ToolView | undefined, name: string): string {
  return tool?.label ?? name
}

/**
 * 从参数里挑一样最像"它在干什么"的显示（路径、命令、模式），其余丢掉。
 *
 * 整串 JSON 铺在流里等于让人去解析它；人只需要认出它动了哪个文件。
 *
 * 是哪一项由工具声明（{@link ToolView.subjectKey}），不在这里按顺序试：顺序本身
 * 说不清（"path 比 pattern 更算主语"？），而且会对每个工具都试同一串键。工具没
 * 声明主语就是不显示，而不是碰巧试中别的字段。
 */
function summarize(
  tool: ToolView | undefined,
  args: Record<string, unknown> | null,
): string {
  const key = tool?.subjectKey
  // undefined 是"工具没声明主语"（线上 null 即字段缺席），和"声明了但取不到值"是两件事。
  if (key === undefined) {
    return ''
  }
  const value = args?.[key]
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.join(' ')
  return ''
}

/**
 * 参数串解析成对象。解不出来就是 null，不是空对象：解不出来表示"这次调用没有可用
 * 参数"，空对象表示"参数是空的"。参数是模型给的，形状不能假定。
 */
function parseArgs(argsJson: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(argsJson)
    return parsed !== null && typeof parsed === 'object'
      ? (parsed as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

/**
 * 这次调用动的是哪个文件，只有它才值得做成可点的入口。
 *
 * `glob` 的 pattern、`run_command` 的命令行里也可能有路径，但那要猜；猜错会打开
 * 一个不相干的文件，比不能点更糟。判据是工具声明的 subjectIsPath（见
 * {@link ToolView.subjectIsPath}），不是工具名名单。
 */
function pathOf(
  tool: ToolView | undefined,
  args: Record<string, unknown> | null,
): string | null {
  const key = tool?.subjectKey
  if (tool?.subjectIsPath !== true || key === undefined) {
    return null
  }
  const path = args?.[key]
  return typeof path === 'string' && path !== '' ? path : null
}

/**
 * 这次调用把文件改成了什么样；不是文件改动、或算不出来，就是 null。
 *
 * 失败的编辑不给 diff：那次改动没有发生，画出来等于给人看一件没发生过的事，且它
 * 和"改了但回退了"在画面上分不清。
 */
function diffOf(
  tool: ToolView | undefined,
  args: Record<string, unknown> | null,
  outcome: ToolOutcome | null,
): DiffRow[] | null {
  if (outcome === null || !outcome.success || tool?.shape !== 'edit') {
    return null
  }
  if (args === null) {
    return null
  }
  // edit_file 的参数里带着被替换的那一段（新旧都有），所以它是真的 diff。
  const before = args.old_string
  const after = args.new_string
  if (typeof before === 'string' && typeof after === 'string') {
    return diffLines(before, after)
  }

  // 剩下那条是整份写入（write_file），不给 diff。
  //
  // 参数里只有"写成了什么"，没有"写之前是什么"；"整份都是新增"只对新建成立。
  // 覆盖已有文件时那句话是假的：旧内容是被抹掉的，不是从来没存在过，而界面上它
  // 和新建长得一样，看的人分不出来。
  //
  // Claude Code 在这一处算的是写那一刻的真 patch，它手里有新有旧。我们不照做，理由是
  // 代价不对等：要拿到旧内容，要么让工具把整份旧内容塞进事件流（一次覆盖就是几十
  // 上百 KB 落在库里），要么自己养行级 diff；而我们有文件面板，想知道现在长什么样
  // 点一下就是全文。一条画得出来的假 diff 比没有更坏。
  //
  // 新建不同：那次调用本来就带着全文，画成代码预览（CreatedFile）是忠实的。这里
  // 返回 null 后，ToolResult 会看 presentation 的 write.created 决定走哪条。
  return null
}

/**
 * 打算等多久，说成人话。
 *
 * 不到一秒说毫秒：0 和 500 都落在这一档，"约 500 毫秒后重试"比"约 0.5 秒"读得快。
 */
function formatDelay(delayMs: number): string {
  return delayMs < 1_000 ? `${delayMs} 毫秒` : `${(delayMs / 1_000).toFixed(1)} 秒`
}

/** 说给读屏器听的话。和 {@link UNFINISHED_WORD} 一一对应，改一个就要改另一个。 */
const UNFINISHED_STATE_WORD: Record<Exclude<Unfinished, 'rejected'>, string> = {
  cancelled: '被取消了，没有结果',
  interrupted: '平台中断了，没有结果',
  'no-result': '没有结果',
}

/** 状态说给人听（也说给读屏器）。记号画得出来，但读不出来。 */
function stateWord(
  outcome: ToolOutcome | null,
  unfinished: Unfinished | null,
  speaker: string,
): string {
  if (unfinished !== null) {
    return unfinished === 'rejected'
      ? `${speaker}拒绝了这次调用，没有结果`
      : UNFINISHED_STATE_WORD[unfinished]
  }
  if (outcome === null) return '运行中'
  if (outcome.success) return '已完成'
  return outcome.exitCode === null ? '失败，没跑成' : `失败，退出码 ${outcome.exitCode}`
}

/** 一行小字。只有异常上色，正常的事情不需要颜色。 */
function Notice({ tone, text }: { tone: NoticeTone; text: string }) {
  const color: Record<NoticeTone, string> = {
    plain: 'text-loom-faint',
    good: 'text-muted-foreground',
    bad: 'text-destructive',
  }
  return <p className={`text-xs ${color[tone]}`}>{text}</p>
}
