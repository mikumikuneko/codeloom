import { ChevronRight, CornerDownRight } from 'lucide-react'
import { createContext, memo, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ThemedToken } from 'shiki'

import { Button } from '@/components/ui/button'
import { LivePane } from '@/components/workspace/LivePane'
import { Markdown, StreamingMarkdown } from '@/components/workspace/Markdown'
import { errorText, approvals, sessions as sessionsApi, type ToolView } from '@/lib/api'
import { languageOfPath, useHighlighted, useViewportHighlighting } from '@/lib/highlight'
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
 * <h2>它之前左边有一条彩色竖线，删掉了</h2>
 * 那条线的颜色表示"这是谁的 agent"（青 / 品红）。删它的理由不是不好看，是
 * **它没在干那件事**：
 *
 * <ul>
 *   <li>它要起作用，前提是**两条流同时在屏幕上** —— 而这一栏是标签页
 *       （会话 / 聊天 / 观战），永远只有一条在屏幕上。那个比较从来没发生过。</li>
 *   <li>而"这是谁的"**标签上已经说了两遍**：名字，和名字前面那个点。</li>
 * </ul>
 *
 * <p>代价倒是实打实的：一条贯穿整屏的彩色竖线是这一栏最响的东西，而它在和内容抢注意力。
 *
 * <h2>没有气泡</h2>
 * agent 说的话是**满宽的一块文本**，不是一条气泡。气泡适合"你一句我一句"的短对话
 * （聊天室就是那样），而这里的内容是一整段工作过程：一段回答、一次工具调用、
 * 一次验证结论。给它们套上气泡，等于说"这些是同一类东西" —— 它们不是。
 *
 * <h2>和聊天室共用 {@link LivePane}</h2>
 * 滚动、贴底、断线提示收在那个外壳里；这里只负责"一条消息长什么样"。
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
   * <p>**事件里带的都是用户 id**（用户名会变 —— 它现在虽然也唯一，但唯一管不了
   * "这句话当年是谁说的"，所以名字不能当身份），名字在这一层现查：
   * 改过用户名之后，历史那几行显示的也跟着变。
   * 查不到时返回 {@code null}，各处给一句诚实的说法（"有人"/"协作者"），**不编名字**。
   */
  nameOf?: (id: string) => string | null
  /**
   * 能不能批挂起的调用。**只有会话所有者能** —— 后端是 {@code requireDriver}。
   *
   * <p>观战时它是 false：B 用自己的 key 驱动自己的 agent，A 既不知道它在干什么、
   * 也不知道为什么，让 A 点这个"同意"等于让 A 替 B 的选择负责。
   */
  canApprove?: boolean
  /**
   * 这条会话的主人怎么称呼 —— 用来标"他说的那几句"。
   *
   * <p>看自己的会话时是「你」，观战时是他的名字。**不能写死成「你」**：
   * 观战的时候那一行是他说的话，挂在你名下就等于你看见几句自己从没说过的话。
   */
  speaker: string
  /**
   * 贴在底部的东西（输入框 / 回滚面板）。观战时没有 —— 别人的会话你插不上话。
   *
   * <h2>为什么它是一个函数、而接收 fold 出来的条目</h2>
   * 因为回滚面板要的就是**这条流**（"能退回哪儿"、"这一轮改了什么"都在里面）。
   * 让它在外面自己再拉一次，就会出现两份数据：屏幕上显示的和面板里列的可能对不上 ——
   * 那正是它从前那个 bug 的成因（面板拉到的是一段被截断的历史）。
   *
   * <p>做成函数而不是另开一个 context：条目就在这个组件里折出来的，
   * 交给渲染底部的那个人是最短的一条路 —— 不用中间任何一层记得转发。
   */
  footer?: (items: StreamItem[]) => React.ReactNode
  /**
   * 观战栏空着时的说法。
   *
   * <p>三种空法不是同一件事：还没有队友 / 队友还没有会话 / 正在打开。
   * 写死成一句"对方还没有会话。把邀请链接发给对方"会对已经进来的人说错话 ——
   * 而"列表还没刷新"的时候还会把人往"他是不是没进来"的方向带。
   */
  emptyHint?: string
  /**
   * 点流里提到的文件。
   *
   * <p>看自己的会话时是"在中栏打开它"；观战时文件在**对方的树**里，调用方给的
   * 是另一种动作（说明它还没合并，而不是去读一棵里面没有它的树）。
   */
  onOpenFile?: (path: string) => void
  onOpenDiff?: (commitSha: string, path: string) => void

  /**
   * agent 动了工作区里的文件。
   *
   * <p>**这是"树该重拉了"这个信号唯一的来源** —— 左边那棵树看不见磁盘，
   * 它只在挂载和这个信号到达时去拉（见 {@code FileTree}）。后端往磁盘写文件
   * 和界面之间本来没有任何一根线，这是接上的那根。
   *
   * <p>观战那处**不传**：对方的 agent 改的是**他的**树，我这边的树一个字没动。
   * 把它接上去，只会让人看着一棵一直在重拉、内容却永远不变的树。
   */
  onWorkspaceChanged?: () => void
}) {
  const { items, streaming, disconnected, empty } = useSessionStream(sessionId)

  /**
   * 名字查不到时说「有人」—— **不编名字**（见下面那个 who）。
   * 没接这一层时（这条流不给人看的那种用法）同样走它。
   */
  const lookupName = nameOf ?? (() => null)

  /**
   * 工具声明的样子，供这一整棵树渲染用。
   *
   * <p>它取一次就够 —— 声明是**静态的**（见 {@code useToolCatalog}）。
   * 挂在这里而不是每个用到的小组件里各自取，是因为"取"这件事有加载态，
   * 而工具行不该各自闪一下。
   */
  const catalog = useToolCatalog()

  /**
   * 这一轮是不是还在跑。
   *
   * <p>判据只有一个：**这一轮最后那条状态**（见 {@link turnIsActive}）。
   * 它喂的是"按 Esc 打断"和下面那行状态 —— 判错的方向里，说"没在跑"更贵：
   * 人正要打断一次跑了几十秒的构建，而按钮不响应。
   */
  const running = turnIsActive(items)

  /** 停止信号发出去了、但还没真的停。**只为了给一句话的反馈**，见下面那个 effect */
  const [stopping, setStopping] = useState(false)

  const writes = useMemo(() => workspaceWrites(items), [items])

  // 停了（或者本来就没在跑）就把那句话收掉
  useEffect(() => {
    if (!running) setStopping(false)
  }, [running])

  /**
   * 按 Esc 打断正在跑的那一轮。
   *
   * <h2>为什么挂在 window 上，而不是这一块区域上</h2>
   * 人要打断的时候，手多半不在输入框上 —— 他刚看着 agent 在跑。
   * 挂在这一块上的话，只有焦点正好落在里面才算数。
   *
   * <p>**不抢别人已经处理过的**：右键菜单按 Esc 是把自己关掉，那会 preventDefault。
   *
   * <p>按下之后要在输入框上面挂一句「正在停…」，不能就此不管：它是个**信号**
   * （后端回 202），取消只在工具边界上被检查 —— 发出去了不等于停了。
   * 中间那几秒没有反馈的话，按的人只会以为这个键又没生效。
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
   * 我刚发出去、还**没在流里出现**的那些话。
   *
   * <h2>为什么拿"用户消息条数"当判据</h2>
   * 发请求那条路现在不等回执（见 {@code Composer.send}），所以"发出去了"和
   * "流里看得见"之间有一段空白 —— 排队的那句要等到这一轮收尾才落库。
   * 这中间得让说话的人看见自己那句话，否则就是"点了没反应"。
   *
   * <p>只数条数、不比内容：同一句话发两遍是允许的（用户就是说了两遍）。
   */
  const userCount = items.reduce((count, item) => (item.kind === 'user' ? count + 1 : count), 0)
  const pending = usePendingEcho(sessionId)
  useEffect(() => {
    if (sessionId !== null) {
      observeStream(sessionId, userCount)
    }
  }, [sessionId, userCount])

  /**
   * 上一次通知过的时候，这个数是多少。
   *
   * <p>必须记着它：`items` 每一批事件都换一个新数组，**没有这一步的话，
   * 只要这个数大于零就会每批都通知一次** —— 左边那棵树就被打成了每帧重拉。
   * 见 {@code changes} 上面那句同类的话。
   */
  const notified = useRef(0)
  useEffect(() => {
    // 归零只有一种原因：换了会话（items 被清空）。记号得跟着归零 ——
    // 否则新会话的头几次改动会被上一条会话那个大数压住，一直不通知
    if (writes === 0) {
      notified.current = 0
      return
    }
    if (writes > notified.current) {
      // 一次只通知一次，**不管中间多了几条**：调用方要的是"现在去拉一遍"，
      // 而不是"补上那三遍"。拉一次拿到的就是最新的全部内容
      notified.current = writes
      onWorkspaceChanged?.()
    }
  }, [writes, onWorkspaceChanged])

  // **回滚用的位置标记不进分块。**
  //
  // 它是看不见的（渲染时返回 null），所以留着只会有坏处，而且是两处：
  //
  //  一、开头那一条会独占一个**空块**。空块自己没有高度，可 `space-y-8` 会给
  //     它**下一个**兄弟留 32px —— 屏幕上就是"第一条消息上面凭空多一块空白"。
  //  二、更要紧：轮次号是按块在数组里的**下标**去查的（见下面 changesByTurn 和
  //     TurnRail）。会话开头那条 checkpoint 多占一格，后面每一轮的编号就整体错一格 ——
  //     "这一轮改了哪些文件"会挂到别人那一轮头上，而那看起来完全像真的。
  const turns = groupIntoTurns(items)

  /**
   * 每一轮改了哪些文件。
   *
   * <h2>它不再是"另发一个请求去问"</h2>
   * 从前这里（以及回滚面板）每次都会去问后端要一份"每轮改了什么"，而后端是**读的时候**
   * 拿 git 现算的 —— 于是每收尾一轮就重算一遍整条历史，整条会话下来是 O(n²)，
   * 而且某个提交被 gc 掉之后那一轮就永远算不出来了。
   *
   * <p>现在这份记录是**后端在收尾时算一次、落进事件流**的（见后端的 {@code WorkspaceChanges}），
   * 于是它跟着 {@code items} 一起来 —— 和屏幕上那些消息**同出一份 fold**，
   * 不可能出现"上面显示改了 a.txt、下面那一行说没改"这种事。
   */
  const changesByTurn = new Map(turnChanges(turns).map((entry) => [entry.turn, entry]))

  /**
   * 每一轮的根元素。轮次导航点一下，就把那一轮滚进视野 —— 见 {@link TurnRail}。
   *
   * <p>换会话时不清空：那些元素已经不在 DOM 里了，`scrollIntoView` 对着一棵
   * 脱了树的节点什么也不做。为它加一套清理反而多一处会写错的地方。
   */
  const turnRefs = useRef<(HTMLDivElement | null)[]>([])

  function jumpTo(index: number) {
    turnRefs.current[index]?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }

  // ★ **只有一个 return。** 这不是风格问题。
  //
  //   上一版在"还没有会话"时提前 return 掉了整个面板 —— 而底部那一条
  //   （输入框）是**打第一句话的唯一入口**：一条会话就是在用户说出第一句话的那一刻
  //   才产生的（见 Composer 的类注释）。于是界面上写着"在下面说一句话"，
  //   而下面什么都没有 —— 整条路堵死在起点。
  //
  //   把分支收进 children 之后，`footer` 在**这个组件里只出现一次**，
  //   "某条路径忘了带上它"在结构上就没有发生的余地了。
  return (
    <ToolCatalogContext.Provider value={catalog}>
      <LivePane
      // 没有会话时不会有连接，也就不会有"断线"这回事
      disconnected={sessionId !== null && disconnected}
      progress="连接断了，正在重连…"
      footer={
        <>
          {/* 贴在输入框**上方**而不是流末尾：它说的是"这一轮正在收尾"，
              是个状态，翻上去看历史的时候也该看得见 */}
          {/* 这一行**常驻**（不按 Esc 时占着一个不换行空格）：它是插进布局的元素，
              按条件挂出来会把滚动区挤矮一行 —— 状态行不该改变布局 */}
          <p role="status" className="shrink-0 px-4 pt-2 text-xs text-loom-faint">
            {stopping ? '正在停…' : ' '}
          </p>
          {footer?.(items)}
        </>
      }
      // 只有一轮的话那条刻度毫无意义 —— 它标的就是"这里可以跳"
      rail={turns.length > 1 ? <TurnRail turns={turns} onJump={jumpTo} /> : undefined}
    >
      {sessionId === null ? (
        footer ? (
          // **贴顶写，不居中**：这一栏的上方是**空的**，那句话要指的是**下面那个输入框**
          //（"在下面说一句话，就有了"）。居中之后，人一进来先看到一大片空白，
          // 再在中间找到一行字
          <p className="px-4 pt-6 text-sm leading-6 text-muted-foreground">
            还没有会话。在下面说一句话，就有了。
          </p>
        ) : (
          // 观战没有输入框，上面那句话没有东西可指 —— 那就回到全站统一的空状态：
          // 落在正中间（"这里本来就没有东西"），不贴顶（"内容没加载出来"的样子）
          <div className="pane-absent h-full">
            <p className="text-muted-foreground">
              {emptyHint ?? '对方还没有会话。把邀请链接发给对方，他就能进来。'}
            </p>
          </div>
        )
      ) : (
        <div className="px-4 py-4">
          {/* `space-y-8` 是**块与块**之间的间距，块内部的 `space-y-4` 是另一半 ——
              两档间距就是这段流唯一的骨架，见 groupIntoTurns */}
          <div className="space-y-8">
            {empty && !streaming.text && !streaming.reasoning && (
              <p className="text-sm leading-6 text-muted-foreground">
                这里会显示这条会话的每一步。
              </p>
            )}

            {turns.map((turn, index) => (
              // **只有最后那一轮算"正在跑"**
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

            {/* 我刚发出去、流里还没出现的那几句。**灰的**：它们还没轮到 ——
                Claude Code 也是这么分的（"show in gray until Claude starts responding to them"） */}
            {pending.map((text, index) => (
              <div key={index} className="flex items-start gap-1.5">
                <span className="shrink-0 text-sm leading-6 text-loom-faint/60">{speaker}：</span>
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
 * 事件里带的是 **用户 id**，名字在这一层现查（见 {@link SessionStreamView} 的 nameOf）。
 *
 * <p>查不到（人不在这张成员表里、或者 id 是空的）时说「有人」，**不编一个名字出来** ——
 * 那比"暂时不知道是谁"更糟：它把一句话挂到错的人名下，而看的人不会怀疑。
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
   * **不含用量那条** —— 那一行旁边还要挂"这一轮用了多久"，
   * 而时长是**整轮**的属性，不是那一条消息的属性，所以它由上面那一层一起画。
   * 这里用 {@code Exclude} 把它排掉，于是下面那个 switch 仍然是穷尽的：
   * 以后往 {@code StreamItem} 里加东西，这里照样会编译不过。
   */
  item: Exclude<StreamItem, { kind: 'usage' }>
  speaker: string
  sessionId: string | null
  canApprove: boolean
  onOpenFile?: (path: string) => void
  /** 见 {@link SessionStreamView} 的 nameOf —— 事件里带的是 id，名字在这一层查 */
  nameOf: (id: string) => string | null

}) {
  switch (item.kind) {
    case 'user':
      // 人说的那一句要**看得出来是人说的**。从前它和 agent 的回答长得一模一样 ——
      // 一段几十步的流水里，分不出"我让它做的"和"它做的"，读起来就不是记录了。
      //
      // 用一个最轻的办法分：句首标出是谁。这**不是新花样** —— 同一条流里的
      // 「平台：」「X 的 agent 捎来一句：」用的就是它，这里只是把最后一种补齐。
      //
      // 刻意不用气泡、不用底色：那些是"聊天"的形状，而这是一段工作过程
      // （理由见类注释里"没有气泡"那段）
      //
      // 前缀和正文**并排**（而不是前后拼接）：正文要过 markdown，而 markdown 是块级的
      // —— 直接拼的话，那个「你：」会被挤到自己一行上去
      return (
        // **这是原本的样子**：句首标出是谁，正文跟在后面。
        // 和下面那条"还没轮到"的灰显回声用的是同一套排版 —— 回声一落库就换成它，
        // 位置不该跳一下
        <div className="flex items-start gap-1.5">
          <span className="shrink-0 text-sm leading-6 text-loom-faint">{speaker}：</span>
          <div className="min-w-0 flex-1">
            <Markdown>{item.text}</Markdown>
          </div>
        </div>
      )

    case 'agent':
      return (
        <div className="space-y-2">
          {/* 思考过程在上、答案在下，而且**折着**：它是"它为什么这么说"，
              多数时候人要看的是它说了什么 */}
          {item.reasoning && <Thinking text={item.reasoning} />}
          {/* 正文可能是空的：模型有一轮**只调了工具、一个字没说**，
              而它的思考要落库（带工具调用的那条消息必须把思考一起带回去，
              见后端 AgentTurn）。那种轮次只有思考这一行 ——
              渲染一个空的 markdown 只会多出一段空档 */}
          {/* 正文**贴最左边**：和行首那些记号（点 / 箭头）同一条线，而不是和它们的文字对齐 ——
              工具行的文字往后缩，是因为它前面有个记号要先占位 */}
          {item.text !== '' && <Markdown>{item.text}</Markdown>}
        </div>
      )

    case 'note':
      // 来源必须标出来：模型看到它时会被标成"来自谁"，人也一样要能分清。
      // 名字**按 id 现查** —— 事件里只有 id（见 SessionStreamView 的 nameOf）
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
      // 一次失败的重试。**要显示，但不是重点**：它绝大多数时候几秒钟就过去了，
      // 而它存在的全部意义就是让那几秒不至于是一片死寂（见 LlmRetryScheduled）
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
      // **答复之后不画一行"某某批准了那次调用"**：能批的只有这条会话的主人自己，
      // 观战的人本来就按不动 —— 那行字等于在告诉你"你刚才点了一下"。
      // 拒绝也不用单独说：那条调用自己的行上写着"X 拒绝了"（见 ToolLine 的结局词，
      // 观战时 X 是会话主人的名字，不是正在看的人）
      return null

    case 'notice': {
      // 带 actor 的是"**某个人**做的一个动作"（回滚）—— 名字按 id 现查，拼在正文前面。
      // 不带的（同步、压缩）是平台自己发生的事，没有主语（见 StreamItem 上的 actorId）
      const actor = item.actorId === undefined || item.actorId === null
        ? null
        : who(nameOf, item.actorId)
      return <Notice tone={item.tone} text={actor === null ? item.text : `${actor} ${item.text}`} />
    }

    // 位置标记：它**不给人看**，留在流里只为了回滚时能把流截断到准确的位置。
    // 写明一条而不是让它从这里漏下去（不写也能编过），是因为"这个类型故意不画"
    // 和"忘了画"在代码里长得一模一样，而它们该被人分得清
    case 'checkpoint':
    case 'changes':
    // 状态那条也**不显示**：它进流只为了让"这一轮现在什么状态"有个事实来源（见 turnStateOf）。
    // 这里显式列出来，而不是靠"掉出 switch 就是 undefined"—— 那种隐式行为会被下一个人
    // 加一个 default 或者开 noImplicitReturns 时打掉
    case 'state':
      return null
  }
}

/**
 * 一轮。
 *
 * <h2>为什么它是一个组件，而不是 map 里的一段 JSX</h2>
 * 两件事都只有"轮"这一层知道：
 *
 * <ul>
 *   <li><b>这一轮还在跑吗</b> —— 折起来的那几项靠它决定要不要收回折叠
 *       （见 {@link useDisclosure}），而它得**从这一层的子树上**传下去。
 *       写在 map 里的话，就得自己包一层 provider，那段 JSX 会平白深两级。</li>
 *   <li><b>这一轮的起止时间</b> —— 它由这一轮**第一条和最后一条**消息决定，
 *       是整轮的属性。写成 map 里的表达式，就要在调用处把
 *       {@code turnSpan(turn)} 的结果拆成两个 prop 到处传。</li>
 * </ul>
 */
type ToolItem = Extract<StreamItem, { kind: 'tool' }>

/** 一轮里要画的一个单元：一条事件，或者"连续的只读调用"合成的一组。 */
interface TurnEntry {
  item: StreamItem
  at: number
  /** 有它就是一组（合并的是这几个只读调用） */
  group?: ToolItem[]
}

/**
 * 把**连续的、只读的**翻文件调用合成一组（读文件 / grep / glob）。
 *
 * <p>判据是工具**声明的形状**，不是工具名（见 {@code ToolSurface}）。三种情况不进组：
 * 带卡片的联网搜索（折进去就把"看了哪些网页"藏了）、失败的、没跑完的 ——
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
 * <p>一整轮里读六次文件、搜三次，如果一行一条，二十来行里找"它改了哪儿"就变成了
 * 一行行扫。合并成一句之后，流读起来是"想了想 → 翻了几份文件 → 改了这里"。
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
          {/* 组里每一条都成功才有可能成组（见 groupToolReads），所以这里是一个恒定的成功点。
              复用工具行那颗 —— 同一个记号不该有第二份尺寸和颜色 */}
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
  /** 这一轮改了哪些文件。**没改就是 null** */
  change: TurnChanges | null
  /** 挂在那一轮的根元素上 —— 轮次导航靠它把某一轮滚进视野 */
  anchor?: (element: HTMLDivElement | null) => void
  /** 这一轮是不是还在跑。**只有最后那一轮是** */
  live: boolean
  /** 正在长出来的那一段。**只有最后那一轮有**，前面几轮传 null */
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
  /** 工具的声明（认"是不是只读的翻文件"用它，不认工具名） */
  const catalog = useToolCatalog()

  // 这一轮的用量结算。**一轮可能落好几条** —— 见 mergedUsage
  const usages = turn.filter((item) => item.kind === 'usage')
  const lastUsageAt = turn.reduce((last, item, at) => (item.kind === 'usage' ? at : last), -1)
  const usage = mergedUsage(usages)

  /** 这一轮**就此收场**了（失败 / 被打断 / 被拒绝）—— 见上面那几个 turnEnd */
  const stopped = turn.some((one) => one.kind === 'notice' && one.turnEnd !== undefined)

  /**
   * 这一轮**真的跑完了没有** —— 用量行只在跑完之后画。
   *
   * <p>只判"是不是还挂着等人批"是不够的：挂起那一刻后端会先结算一次，批准之后
   * 它**接着跑**，而那段时间里状态已经离开 AWAITING_APPROVAL 了 —— 照画的话，
   * 续跑一开始就会冒出挂起那一刻的用量，等真跑完再变一次。判据见
   * {@link usageLineVisible}：终态才算完成。
   */
  const finished = usageLineVisible(turn, live, stopped)

  return (
    <LiveTurn.Provider value={live}>
      {/* scroll-mt-4：跳过来的时候上面留一点缝，不然那一轮的第一行会贴着顶边 */}
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
            // 用量和"这一轮用了多久"一起画 —— 它们是同一类事实，
            // 而时长是整轮的属性，不该挂在某一条消息上。
            //
            // ★ **整轮只画一次**，画在最后那一条的位置上：被批准打断的轮次会有两条
            //   （挂起那一刻一条、答完续跑之后一条），各画各的就会在屏幕上出现
            //   两行"这一轮用了多少"、两行"改了 2 个文件" —— 而你说的"一轮"
            //   是你发的那一句话，不是后端的执行单位
            // **没跑完不画**：挂起等人批、以及批准之后的续跑，都还在这一轮里 —— 见 finished
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

        {/* 正在长出来的那一段**属于最后那一块** —— 它是这一轮还没说完的话。
            单列一块的话，它和自己那句话之间会空出一整档块间距，
            看起来像"上一轮说完了，又开了一轮"。 */}
        {streaming !== null && (
          <>
            {/* 它和上面那些**长得不一样**是有意的：那些已经发生了，
                这一段还没 —— 淡一档，就是在说这件事。

                但"淡"不该淡掉排版：从前这里是两个裸的 `<p>`，
                于是它一边写你一边看到的是一堆星号、井号和反引号，
                等整句话落地才"啪"一下变成排版好的。那不是"还没写完"的观感，
                是"这个界面不会渲染"的观感。现在两边走同一套渲染，
                唯一还淡着的只有颜色 */}
            {streaming.reasoning && <Thinking text={streaming.reasoning} running />}
            {/* 流式正文和落库后重画的那一份**同一个左边界**（都贴最左边），
                否则答案在"流式长出来"和"落库后重画"之间会横跳 */}
            {streaming.text && <StreamingMarkdown text={streaming.text} />}
          </>
        )}
      </div>
    </LiveTurn.Provider>
  )
}

/**
 * 轮次导航：右边缘那一列刻度，一轮一格。
 *
 * <h2>为什么要它</h2>
 * 一条会话跑上十几轮之后，"刚才那段在哪儿"就只能靠滚 —— 而滚是**线性**的，
 * 人记事却是按"第几次"记的。这一列刻度把整条会话的长度和位置变成一眼能看见的东西，
 * 也让"回到第 3 轮"从"往上拖半天"变成一次点击。
 *
 * <h2>为什么刻度上要看得到内容</h2>
 * 一列没有标注的刻度只能告诉你"有 14 轮"，告诉不了你哪一格是你要找的那一轮 ——
 * 那就等于把滚动条换了个样子。所以每一格挂一句话：**那一轮用户说的头几个字**。
 * 他是按"我让它改问候语那次"记事的，不是按"第 5 轮"。
 *
 * <h2>为什么它只标刻度、不跟着滚动高亮当前那一格</h2>
 * 那需要在滚动时算"哪一轮在视野里"，而这件事每一帧都要重算一遍 ——
 * 换来的是一个**装饰性**的位置指示：人已经知道自己滚到哪儿了。
 * 等真的有人找不到北了再说。
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
          // 刻度要读成"一个个可以点的记号"，而不是一排蹭痕：**够亮**（30% 的 loom-faint
          // 铺在一整片 #0a0a0c 上就是几粒灰）、**彼此分开**（gap-1.5）、指上去换成一个明确的亮色。
          // 位置也挪离了滚动条（见 LivePane 里的 right-2.5）—— 贴着它的时候，
          // 这四粒东西最容易被读成"滚动条渲染坏了"
          className="h-1.5 w-4 rounded-full bg-loom-faint/45 transition-colors hover:bg-foreground/70"
        />
      ))}
    </nav>
  )
}

/** 一格刻度上那句话：第几轮 + 用户说的头几个字。 */
function turnLabel(turn: StreamItem[], index: number): string {
  const said = turn.find((item) => item.kind === 'user')
  const text = said?.kind === 'user' ? said.text.trim().replaceAll(/\s+/g, ' ') : ''
  if (text === '') {
    return `第 ${index + 1} 轮`
  }
  return `第 ${index + 1} 轮 · ${text.length > 24 ? `${text.slice(0, 24)}…` : text}`
}

/**
 * 一轮收尾那一行：**用量 + 用时**。
 *
 * <h2>为什么这两件事放在同一行</h2>
 * 它们是同一类东西 —— "这一轮花了多少"。中间用**间距**分开，不用 {@code ·} 串成一句：
 * 那种写法会把两个各自独立的事实裹成一句读不下去的话。
 *
 * <h2>为什么时长不跟着用量一起折叠</h2>
 * 用量点开才看明细（那是一份账单），而"跑了多久"是**一眼就要知道**的那个数。
 * 跟着折起来，等于把最常见的问题藏在一层点击后面。
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
  // **没跑完的轮次不报"用时"。**
  //
  // 那个数字在一轮失败或被打断时说明不了什么：它大半是等超时、等批准、或者白烧掉的，
  // 而人会把它读成"这一轮干了这么久"。deepseek-harness 也是这么分的 —— 只有正常收尾的那一轮
  // 才给时长，aborted 和 error 一律不给（它那边那句判断逐字是
  // `reason === 'aborted' || reason === 'error' ? undefined : formatRunDuration(...)`）。
  const duration = stopped ? null : formatSpan(startedAt, endedAt)
  return (
    // **改动那一格排在最后**：它展开出来的文件列表很宽（一条长路径就能把整行撑开），
    // 排在中间的话，一展开就把后面的东西挤到下一行去 —— 而"这一轮用了多久"
    // 不该因为点开了文件列表就跳位置
    <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
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
 * 「这一轮改了 N 个文件」—— 点开是逐文件的增删。
 *
 * <h2>为什么它必须问后端，而不是数上面那些工具行</h2>
 * 因为**命令也会改文件**：格式化器、代码生成、`sed -i`、编译产物。
 * 数 {@code edit_file} / {@code write_file} 的话，一轮里 agent 跑了
 * {@code mvn spotless:apply} 改了十二个文件，这里会说"没改什么" ——
 * 而那句话是**不完整的**，比不说更糟。
 *
 * <p>这些数是**收尾时算好、随事件流过来的**（后端 {@code WorkspaceChanges}）：比的是每一步的提交
 * 和它的第一父提交，所以**命令改的也算**（格式化器、代码生成、{@code sed -i}）。
 * 从前它是"每次读的时候现拿 git 算一遍"—— 于是这一行每出现一次就重走一遍历史，
 * 而且那个提交被 gc 掉之后这一轮就永远算不出来了。现在是**每个轮次算一次**，之后照着读。
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
        {/* 改动太多时后端只记下了前一批 —— 那个数字如实带个"+"，
            而不是拿一个偏小的数当完整的报 */}
        改了 {change.files.length}{change.truncated ? '+' : ''} 个文件
        <span className="ml-1 font-mono">
          <span className="text-loom-added-mark">+{added}</span>{' '}
          <span className="text-loom-removed-mark">−{deleted}</span>
        </span>
      </summary>
      <ul className="mt-2 space-y-0.5 border-l-2 border-border pl-3 text-xs text-loom-faint">
        {change.files.map((file) => (
          <li key={file.path} className="flex items-baseline gap-3">
            {/* 路径可点。**这里的点和流里别处的路径不一样**：别处打开的是"它**现在**是什么样"，
                这里打开的是"**这一轮**对它做了什么" —— 回滚之后只剩后者看得见。
                （没人挂那个回调时才退回旧行为：打开文件本身） */}
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
                // 二进制文件 git 给不出行数，只能说"它变了" —— 写 +0 −0 是在说没改
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
 * <h2>为什么一轮会有好几条</h2>
 * 因为**后端的"一轮"是你发的那一句话，而它落账的单位是执行**。批准续跑会让后端
 * 把同一轮收尾两次（挂起那一刻一次、答完之后一次），于是两个 `TURN_TOKENS_USED`
 * 落在同一条用户消息底下 —— 界面上就出现两行"这一轮用了多少"。
 *
 * <p>这件事代码里早就知道：回滚点的注释就写着"后端自己的轮次号在批准续跑时会多推一格"，
 * 所以按**用户消息**分组才是对的。用量行当初漏了这一条。
 *
 * <p>**不在后端合并**：那两个事件是**两次执行各自的账**，分开记是对的 ——
 * 万一答完批准之前服务就挂了，前一段花的钱不能丢。让审计层去迁就展示层是反的。
 *
 * <p>token **相加**（那条消息真花的钱就是两段之和）；
 * 上下文读数取**最后**那个 —— 它是"收尾时上下文有多大"，
 * 不是一笔能相加的账（两个时刻的规模加起来没有意义）。
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

/** 一轮的起止时间。**两个都来自服务端**，所以它们的差不受浏览器时钟影响。 */
function turnSpan(turn: StreamItem[]): { startedAt: string | null; endedAt: string | null } {
  const opening = turn.find((item) => item.kind === 'user')
  // 用量那条是**每轮收尾才写的**（后端 finishTurn 里和 checkpoint、状态迁移一起落），
  // 所以"这一轮跑完没有"看它就行 —— 不必再去认那几个状态迁移，
  // 也就不必小心区分"等用户"和"等批准"（那两个在事件流里长得一样）。
  //
  // **取最后一条，不是第一条**：被批准打断时后端会收尾两次（见 mergedUsage），
  // 取第一条的话"用时"只算到挂起那一刻 —— 而你问"这一轮多久"指的是整轮，
  // 包括你盯着那个批准按钮想的那几十秒
  const closing = turn.findLast((item) => item.kind === 'usage')
  return {
    startedAt: opening?.kind === 'user' ? opening.at : null,
    endedAt: closing?.kind === 'usage' ? closing.at : null,
  }
}

/**
 * 「这一轮跑了多久」。
 *
 * <h2>只在**跑完之后**显示</h2>
 * 跑着的时候也能显示（拿当前时间去减开始时间），但那是**浏览器的钟去减服务端的钟** ——
 * 两台机器的时钟差多少，这个数就差多少。而它旁边正在发生的事（那行扫光的工具调用、
 * 正在长的正文）本来就在动，不缺这一处。
 *
 * <p>等这一轮收尾那两笔账都落了，起止就都是服务端的时间，差值才是真的。
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
 * 一轮用掉的 token。**两层**：外面一个总数，点开才是明细。
 *
 * <h2>为什么不能铺在一行里</h2>
 * 从前它是「这一轮：输入 1,673 tokens，输出 53 tokens」。那一行里有两个
 * **没被解释过的量**：什么算"输入"？"用了"指的是什么？一行字里两个不知道，
 * 这行字就等于没写 —— 而它本来是想让人知道"这一轮花了多少"的。
 *
 * <p>现在外面那句只说一件事：这一轮用了多少 token。人扫到这里就懂了，
 * 不必先弄明白"输入"和"输出"分别指什么。想要明细的（比如发现某轮特别贵、
 * 想看是不是上下文太长）再点开。
 *
 * <p>总数是**两个数相加**，不是从别处再取一个：取来的那个和这两个迟早会不一致，
 * 而那时候人看到的是"加起来对不上"。
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
        {/* 说"这一轮用了多少"，不说"用量是多少"：后者是把一件事名词化了一下，
            而要读的人得先把它还原成句子。中文里动词开头的句子更好读 */}
        这一轮用了 {formatTokens(input + output)} tokens

        {/* 上下文那件事**跟在这一行后面**，而不是混进那句话里 ——
            它回答的是另一个问题（"还能聊多久" vs "这一轮花了多少"），
            所以用竖线隔开，读起来是两件事。见 ContextRing */}
        {context !== null && (
          <>
            <span className="text-border">|</span>
            <ContextRing reading={context} />
            {/* "上下文"这三个字不能省：光一个"19%"会让人以为它是上面那个 token 数的
                某种比例（比如缓存命中率）。一个数没有名字的时候，旁边那个数就会替它编一个 */}
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
        {/* 命中缓存的那部分**单价低得多**，所以在 BYOK 下它是一项**钱**，不是一项技术指标。
            放在明细里而不是外面那一行：外面那行说"这一轮花了多少"，
            而这一条回答的是"这笔钱是怎么花掉的" */}
        {cached > 0 && (
          <div className="flex gap-2">
            <dt>其中缓存命中</dt>
            <dd className="font-mono">
              {formatTokens(cached)} tokens（{cacheHitPercent(cached, input)}%）
            </dd>
          </div>
        )}
        {/* 环上那个百分比的分母写出来 —— 一个比例不写分母，读的人只能猜 */}
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

/** 环的直径（像素）。**外面那一行是 text-xs**，所以它比一行字稍微高一点点就够了。 */
const RING_SIZE = 13
const RING_RADIUS = 5
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS

/**
 * 上下文用了窗口的多少。
 *
 * <h2>为什么是一个环，不是一个数字</h2>
 * 因为它是**每一轮都出现一次**的东西。一串数字要一个一个读才知道涨了没有，
 * 而环的弧度是**扫一眼就比得出来**的 —— 一行行看下去，能看出上下文一轮轮鼓起来、
 * 压缩那一下又掉下去。这正是这个数据唯一有用的读法。
 *
 * <h2>它不警告</h2>
 * 快满了**也不变色**。因为到了某个比例并不会出事 —— 平台会去压缩，会话继续。
 * 给一个不会出事的东西上红，等于把"红"这个信号用掉一次。
 */
function ContextRing({ reading }: { reading: ContextReading }) {
  const fraction = Math.min(1, reading.tokens / reading.window)
  return (
    <svg
      width={RING_SIZE}
      height={RING_SIZE}
      viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}
      // 语义由后面那行字（"上下文 19%"）担着，这个 SVG 只是把同一件事画出来
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
        // 从十二点开始顺时针走。SVG 的圆默认从三点钟起，不转这一下的话
        // 每个环看起来都像被啃掉了一口
        transform={`rotate(-90 ${RING_SIZE / 2} ${RING_SIZE / 2})`}
      />
    </svg>
  )
}

/**
 * 折叠按钮长什么样。工具结果、diff、搜索结果**共用这一串** ——
 * 它们做的是同一件事，长成三个样子只会让人每次重新认一遍。
 *
 * <p>平时没有下划线：它是一行辅助文字，不该和正文抢注意力；指上去才出现，
 * 那一下是在回答"这个能按"。
 */
const TOGGLE =
  'text-loom-faint underline-offset-2 transition-colors hover:text-muted-foreground hover:underline'

/**
 * 折叠**标题行**共用的样子：整行可点、原生三角不要、灰、指上去变亮。
 * 三种行（思考 / 用量 / 改动）各自再加宽度和字号。
 */
const FOLD_SUMMARY =
  'flex cursor-pointer list-none text-loom-faint transition-colors hover:text-muted-foreground [&::-webkit-details-marker]:hidden'

/**
 * 折叠行左边那颗箭头。
 *
 * <p>它**占一个固定的 16px 槽并垂直居中**，所以不管这一行是 `items-baseline`
 * 还是 `items-center`，箭头都落在同一条线上 —— 三处各写一份的时候，
 * 一处靠 `translate-y` 挪、一处靠 `items-center` 顶，最后谁也不齐。
 */
function FoldChevron() {
  return (
    <span className="flex w-4 shrink-0 items-center self-center">
      <ChevronRight className="size-3 transition-transform group-open:rotate-90" />
    </span>
  )
}

/**
 * 工具结果和 diff 的折叠按钮上写什么。两处用**同一句话**，所以只有这一处 ——
 * 各写一遍的话，改文案会漏一处，而两处长得一样、不容易发现。
 */
function expandLabel(expanded: boolean, hidden: number): string {
  return expanded ? '收起' : hidden === 1 ? '还有 1 行' : `还有 ${hidden} 行`
}

/**
 * 一个极小的读数也要有个数，所以**至少显示 1%**。
 *
 * <p>四舍五入到 0 会让人以为那个环坏了：画面上有一个看得见的小弧，旁边写着 0%。
 */
function contextPercent(reading: ContextReading): number {
  const percent = Math.round((reading.tokens / reading.window) * 100)
  return Math.max(1, Math.min(100, percent))
}

/**
 * 缓存命中的比例。
 *
 * <p>取不到输入的场合（{@code input} 是 0）返回 {@code null}：那种情况下
 * "命中 0%"和"没有输入"是两句不一样的话，而显示前者是在说一件没发生的事。
 */
function cacheHitPercent(cached: number, input: number): number | null {
  if (input <= 0) {
    return null
  }
  return Math.round((cached / input) * 100)
}

/**
 * 这一轮**还在跑吗**。
 *
 * <h2>为什么这件事要用 context</h2>
 * "展开/折起"是每个组件自己的 {@code useState}。而"**这一轮变成历史时，把它收回去**"
 * 是一条**跨组件的规矩** —— 它属于"轮"这一层，不属于任何一条消息。
 * 靠 props 往下传的话，{@code Item → ToolLine → ToolResult → DiffView} 这条路上
 * 每一层都要多背一个和它无关的布尔值。
 */
const LiveTurn = createContext(true)

/**
 * 折起来/展开的那点状态。**这一轮跑完之后自动收回折叠。**
 *
 * <h2>为什么要有这条规矩</h2>
 * 一轮跑下来，人可能点开了三四处（思考、diff、命令输出）。下一轮开始时，
 * 屏幕该**回到基线** —— 否则展开的会越积越多，一段对话滚下去全是摊开的细节，
 * 而"哪些是我当前关心的"就看不出来了。
 *
 * <h2>为什么写在渲染里，不写在 effect 里</h2>
 * React 官方那条"根据 prop 调整 state"的写法：它在**同一次渲染**里生效，
 * 不会先画出一帧"还开着"的样子再收回去。写在 {@code useEffect} 里就会闪那一下 ——
 * 而这一下恰好发生在用户刚发出一条消息、正在看屏幕的时候。
 */
function useDisclosure(): [boolean, (open: boolean) => void] {
  const live = useContext(LiveTurn)
  const [open, setOpen] = useState(false)
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
 * 模型的思考过程。**折着，但折着的那一行一直在说话。**
 *
 * <h2>它从前是被丢掉的</h2>
 * 事件流里一直带着 {@code AssistantMessage.reasoning}，只是折叠的时候没人接它 ——
 * 于是推理过程在界面上只"活"了流式那一小段（那几行淡字），消息一落地就没了。
 * 想看它完整说了什么，没有任何入口。
 *
 * <h2>折着不等于看不见</h2>
 * 一个只有「思考过程」四个字的折叠条，看起来和"这里没东西"没有区别 ——
 * 所以人不会去点它。现在那一行右边跟着**它正在想的那一句**：
 * 一眼看得出它在往哪个方向想，想看全文再点开。
 *
 * <p>写的时候那行摘要会**一段一段往前推**：取的是"最近一个写完了的段落的第一行"。
 * 不能取"最后一行" —— 那一行每个字都在变，摘要会跟着抖，抖成一片噪声。
 *
 * <h2>整行都能点</h2>
 * 不是只有那个小三角能点。折叠条本身就是一行字，要去点它左边的箭头，
 * 是让人瞄一个比字还小的目标。
 *
 * <h2>点开才渲染正文</h2>
 * 折着的时候**不渲染** markdown（见下面的 {@code open}）—— 不然每一帧都要
 * 把整段思考重新解析一遍，而那段内容人根本没在看。
 */
function Thinking({ text, running = false }: { text: string; running?: boolean }) {
  const [open, setOpen] = useDisclosure()
  const summary = thinkingSummary(text, running)

  return (
    <details
      className="group"
      open={open}
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className={`${FOLD_SUMMARY} w-full items-baseline gap-2 text-sm`}>
        {/* 一行里左边是**行为**（在思考），右边是**可展开**（箭头）——
            两件事分开，人扫一行时先认出是什么，再决定要不要展开 */}
        {/* 行首那个**转开的箭头**就是它全部的记号：折叠 / 展开这件事自己就说明
            "这里还能打开"。不加图标、不在行尾再放一个箭头。
            它占一个**固定宽度**的槽（见 {@link FoldChevron}），后面那列字才和工具行、正文对齐 */}
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
          {/* **和正文同一个字号**，只用颜色和左侧那条线表示"这是它想的，不是它说的" ——
              Claude Code 也是这么做的（暗色 + 斜体；中文斜体会糊，这里只留暗色） */}
          <Markdown>{text}</Markdown>
        </div>
      )}
    </details>
  )
}

/**
 * 折叠着的那一行显示什么。
 *
 * <p>写完的和正在写的取法**不一样**：
 * <ul>
 *   <li><b>写完了</b> → 取整段思考的第一行。那是它开始想的那件事，
 *       而结论通常已经在下面的回答里说过了，再摘一遍是重复。</li>
 *   <li><b>还在写</b> → 取"最近一个写完的段落"的第一行。判据是**那一段的第一行
 *       后面确实还有内容**（还在写的最后一段，它的第一行可能才打到一半）。</li>
 * </ul>
 *
 * <p>星号要剥掉：这一行是**纯文本**，不是 markdown。不剥的话，
 * 模型写 {@code **关键**}，人看到的就是四个星号夹着"关键"两个字 ——
 * 那正是这个产品最不该出现的东西：记号漏到了给人看的地方。
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
    // 不是最后一段 → 它一定写完了；是最后一段 → 它至少得写到第二行，
    // 第一行才算写完
    if (index < paragraphs.length - 1 || candidate.length > 1) {
      return clean(candidate[0])
    }
  }
  return ''
}

/**
 * 一次正在等批复的调用 —— **agent 就停在这儿，整轮不会往下走**。
 *
 * <h2>它从前来的时候没有地方可答</h2>
 * 后端那条路一直是完整的：白名单外的命令让整轮停下、状态进 {@code AWAITING_APPROVAL}、
 * 落一条 {@code ToolApprovalRequested}，而 {@code POST /approvals/{callId}} 能把答复送回去
 * 并自动续跑。缺的只是这几个按钮 —— 界面上写着"等你批准一次调用"，
 * 人却找不到任何地方能批。（Claude Code 在这种时候给的是一个能直接答的提示。）
 *
 * <h2>答复之后不用刷新</h2>
 * 那条 {@code ToolApprovalResolved} 会从 SSE 推回来，卡片随之收掉（不另画一行
 * "某某批准了"—— 能批的只有会话主人自己，那句话只是在复述他刚点的那一下），
 * 执行器随后续跑，后面的内容接着长出来。
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
  /** 后端为什么要问。空串 = 老事件里没这一项（那时它还不存在） */
  reason: string
  canApprove: boolean
  /** 等的是**谁的**批准。观战时是会话主人的名字 —— 不是正在看的人 */
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
      {/* 不是 loom-faint：这一刻 agent 是**停着的**，这一行是全屏唯一在做事的入口 */}
      {/* **等谁批**要说对：观战的人没有按钮，对他说"等你批准"是一句他做不到的话 */}
      <span className="text-xs text-muted-foreground">等{speaker}批准一次调用</span>

      {/* **为什么问你。** 这一句是后端判据说的（见 CommandApproval）——
          三种情况要人过目的东西完全不同（不认识那个程序 / 动了工作区外面的路径 /
          这行命令看不懂），不说的话，这个"批准"就是走过场。
          老事件里没有这一项，那时它不在（不显示一行空话） */}
      {reason !== '' && <span className="text-xs text-loom-faint">{reason}</span>}

      {canApprove && (
        <span className="flex items-baseline gap-1.5">
          {/* 批准是这一刻**唯一在推动事情的动作**（整轮停着等它），
              所以它是个真的按钮，不是一行字 —— 文字按钮会被扫过去，
              而 agent 停着的时候每一秒都是用户在等 */}
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
 * 一次工具调用 —— **一行调用，有的还有一行结果**。
 *
 * <h2>结果行只在它带着信息的时候出现</h2>
 * 搜到的那几行、写文件的结论、失败的报错 —— 这些是这一步的产出，要看得见。
 * 而"成功读完一个文件""一条成功命令的 stdout"不是：前者只是把调用行又数了一遍，
 * 后者是回执（那一步做了什么，命令自己那一行已经说了）。它们白占的是
 * "一步一步"之间最贵的竖直空间。判据集中在 {@link resultWorthShowing}。
 *
 * <h2>为什么结果要单独一行，而不是挤进调用行</h2>
 * 这一栏从前只有调用那行，于是人**看得出它跑了，看不出跑出了什么** ——
 * 一次 `read_file` 和一次 `write_file` 在界面上长得一样，而它们对代码做的事情
 * 完全不同。该露的结果露在下一行，扫的时候不用去拆调用行。
 *
 * <p>完整输出仍然不是给人读的（那是给模型读的），所以只给**前面几行**，
 * 多的折起来。这个取舍是从 Claude Code 那儿看来的：它给 bash 输出 3 行，
 * 超过就写一句"还有 N 行"。
 *
 * <h2>结果行缩进在下面，而不是套一个框</h2>
 * 父子的关系靠**缩进**表达，不靠给每次调用套一个盒子 —— 一段流水里几十次调用，
 * 几十个盒子会把页面变成仪表盘。同样是看它来的。
 *
 * <h2>失败的时候，调用行**只变红，不换内容**</h2>
 * Claude Code 在失败时会把结果的第一行提到调用行上（因为它的结果是折叠卡，
 * 不展开就看不见错在哪）。**这条我们试过，然后撤了**：
 *
 * <p>我们的结果行本来就默认露出三行，红的，所以"错在哪"一直是看得见的。
 * 而把错误挪上来会和参数摘要抢同一行的宽度 —— 实测两样都被截断，
 * 命令行被挤没、错误被截成「找不…」（而那半句的关键词恰好在后面）。
 * 一句话里塞两个都被截断的东西，比两个都不塞更糟。
 *
 * <p>所以这一行只负责一件事：**它跑的是什么**。错在哪，下一行完整地说。
 * 染红已经足够让它在扫视时跳出来。
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
  /** 这条会话的主人怎么称呼。拒绝是**他**做的，不是正在看的人 */
  speaker: string
  /** 点那个路径打开它。不传就只是一行字（观战时不留一个点了没反应的入口） */
  onOpenFile?: (path: string) => void


}) {
  const failed = outcome !== null && !outcome.success
  // **正在跑要两个条件**：没有结果，也**没有**"没跑完"这个事实。
  // 少了后半个条件，崩溃恢复后重进会话，那条调用会一直闪 —— 界面在说它还在跑，
  // 而它早就没了。这是这条判断唯一容易写错的地方（规则本身只有一处：`toolIsRunning`）
  const running = toolIsRunning(outcome, unfinished)
  // 这个工具**声明的样子**。取不到就是空 —— 那时下面几处各自退化成"显示工具名/原始参数"
  const tool = useTool(name)
  // 参数串**只解析一遍**：主语、路径、diff 都要用它，各解析一次等于同一个 JSON 解三遍
  const parsed = useMemo(() => parseArgs(args), [args])
  const detail = summarize(tool, parsed)
  const path = pathOf(tool, parsed)
  /** `write_file` 的正文：新建时拿它画代码预览（覆盖时不用，见 ToolResult） */
  const written = typeof parsed?.content === 'string' ? parsed.content : null
  // 工具声明的主语是一个外部网址时（抓取），这行本身就是它的入口 ——
  // 结果里不再重复一条同样的链接
  const link = tool?.shape === 'web' ? externalUrl(detail) : null
  // 这次调用改了什么**只算一遍**：下面那一行也要用它去画 diff。
  // 从前两处各算一遍 LCS，而流式期间父组件每一帧都重渲染 —— 等于每帧算两遍。
  // 依赖里 outcome 的引用只在结果真的到达时换一次，不是每帧都换
  const diff = useMemo(() => diffOf(tool, parsed, outcome), [tool, parsed, outcome])

  // 调用行右侧那一小块。**两副面孔**，不会同时出现：
  //   没跑完 → 为什么没跑完
  //   失败 → 退出码（**只有真的跑起来过才有**）
  //
  // 成功时**什么都不写**：这一步做了什么，它自己那一行已经说了（改了哪个文件、
  // 跑了什么）；改了多少行去 diff 里数，行尾再挂一个 +N −M 是同一件事的第三个说法，
  // 而它占的是行尾最显眼的位置。
  //
  // "跑完了但退出码是 1" 和 "进程压根没跑起来" 是两件事，但后者**不用再写一句话**：
  // 行首那个红点已经把"这一行没成"说完了，右端再写"没跑成"是用两种记号说同一件事
  const suffix =
    unfinished !== null
      ? unfinished === 'rejected'
        ? `${speaker}拒绝了`
        : UNFINISHED_WORD[unfinished]
      : failed && outcome !== null && outcome.exitCode !== null
        ? `退出码 ${outcome.exitCode}`
        : null

  return (
    // **和正文一样大**（14px）。Claude Code 的层级靠颜色和字重，不靠把工具行缩小一号 ——
    // 缩小之后一段流水就是一屏小字，眼睛要来回换档
    <div className="text-sm">
      <p className="flex items-baseline gap-2">
        {/* 行首只有一个点：状态（运行中 / 成功 / 失败 / 没跑完）由它的颜色和呼吸来说，
            行为由紧跟着的动词说。见 {@link StatusDot} */}
        <span className="flex w-4 shrink-0 items-center self-center">
          <StatusDot
            running={running}
            failed={failed}
            unfinished={unfinished !== null}
            label={stateWord(outcome, unfinished, speaker)}
          />
        </span>

        {/* 工具名是**动词**，不是接口名：名字回答"在发生什么"，
            而不是"调了哪个 API"。`read_file` 对看的人不是一句话 */}
        <span
          className={`shrink-0 ${running ? 'loom-shimmer' : ''} ${
            failed ? 'text-destructive' : 'text-muted-foreground'
          }`}
        >
          {verb(tool, name)}
        </span>

        {detail !== '' &&
          (path !== null && onOpenFile !== undefined ? (
            // 参数里那个路径**点得开**：中栏就是看文件的地方，而人读到
            // "修改 HelloWorld.java" 时想看的正是它
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
            // 网址点得开：和文件路径那条同理，只是开到浏览器里
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

        {/* 右侧那一小块放在**最右边而且不参与省略**：一行里只该有一处被省略，
            两处的话两处都读不出来。推到边上靠它自己的 `ml-auto` ——
            再垫一个 `flex-1` 是做同一件事的第二遍 */}
        {suffix !== null && (
          <span className="ml-auto shrink-0 font-mono text-loom-faint">{suffix}</span>
        )}
      </p>

      {/* 还没有结果的时候没有结果行 —— 上面那个「…」已经在说这件事了。
          没跑完的也没有：它没有结果可说，原因已经在右边那一格 */}
      {outcome !== null && resultWorthShowing(tool, outcome) && (
        <ToolResult outcome={outcome} diff={diff} path={path} written={written} />
      )}
    </div>
  )
}

/**
 * 「没跑完」在右端那一格怎么写。
 *
 * <p>两种分开说，因为它们该让人做的事不一样：**被取消是我干的**（我按了停，
 * 不用去查为什么），**中断是平台没的**（那是意外，值得看一眼）。
 */
const UNFINISHED_WORD: Record<Unfinished, string> = {
  cancelled: '被取消',
  interrupted: '进程中断',
  rejected: '你拒绝了',
  'no-result': '没有结果',
}

/**
 * 行首那**一个点**：工具调用的全部视觉标记。
 *
 * <p>照 Claude Code（`ToolUseLoader`）的做法：没有按工具分类的图标，
 * 只有一个 7px 的圆点说状态 —— 运行中呼吸、成功绿、失败红、没跑完是暗的。
 * 行为由紧跟着的**动词**说（读取 / 修改 / 运行 / 搜索），不需要再来一个图标说一遍；
 * 两个记号挤在行首，谁都不显眼。
 *
 * <p>这颗绿点用的是 `emerald-400`，比 diff 那个 `loom-added-mark` 亮 ——
 * **两者不是同一个东西**：它是一行只有一颗的"状态"，而 diff 的记号一行一颗、
 * 铺满整个改动，亮成那个样子会抢走正文。别把它们合并成一个令牌。
 */
function StatusDot({ running, failed, unfinished, label }: {
  running: boolean
  failed: boolean
  unfinished: boolean
  /** 读屏器念的那句"这一行是什么状态" —— 视觉上的状态由颜色和右端承担 */
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
    <span
      role="img"
      aria-label={label}
      className={`size-[7px] shrink-0 self-center rounded-full ${color} ${running ? 'animate-pulse' : ''}`}
    />
  )
}

/** 一次调用的结果。**最多三行**，多的折起来。 */
const RESULT_PREVIEW_LINES = 3

/**
 * 搜索结果折叠时显示几条。
 *
 * <p>四条：我们的会话栏比 deepseek-harness 的正文列窄得多，一行一条的话四条刚好一屏内看完，
 * 而八条就开始变成一面墙。多出来的靠"展开其余 N 条"。
 */
const SOURCE_PREVIEW_ROWS = 4

/** diff 一次显示几行。**十二行**：改一个函数差不多就是这个量，再多就该折了。 */
const DIFF_PREVIEW_ROWS = 12

/**
 * 新建文件时，代码预览一次显示几行。
 *
 * <p>**和 Claude Code 同一个数**（`MAX_LINES_TO_RENDER = 10`）：它给的是
 * "一眼看得出写了什么"的量，而不是"把文件铺开"。多的走「还有 N 行」。
 */
const CREATED_FILE_PREVIEW_LINES = 10

/**
 * 这次调用的结果**值不值得单独占一行**。
 *
 * <h2>失败必须说</h2>
 * 报错是接下来那条路的入口（它要修的就是这个）。藏起来等于让人自己把命令重跑一遍。
 *
 * <h2>成功时，读文件和跑命令**都不说**</h2>
 * 读文件的结果是一整份内容：流里没人读它（要看就在中栏打开），而原来下面那句
 * "读了 N 行"只是把调用行又数了一遍。跑命令的 stdout 是**回执**，不是这一步
 * 做了什么 —— 那件事由命令自己那一行和 agent 随后的正文说。它们正是"一步一步"
 * 之间最占地方的噪音。
 *
 * <p>别的工具的成功结果留着：搜索结果是一组要点的链接、写文件是新建出来的那段代码
 * （覆盖时只给结论）、抓取是标题 —— 那些都是这一步真正的产出。
 *
 * <p>认不出工具声明时**照旧显示**：宁可多一行，也不要把真正有用的结果藏掉。
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
 * <h2>改了文件的调用，结果是 diff，不是一句话</h2>
 * 一次 {@code edit_file} 的文本结果是一句"已修改 X（第 3 行附近，替换 20 字符为 24 字符）" ——
 * 那句话说了**改了多少**，没说**改成了什么**。而要读代码的人要知道的正是后者：
 * 它是不是把我那行删了、是不是改了判断条件。
 *
 * <p>diff 的数据不用问后端要：{@code edit_file} 的参数里就带着 {@code old_string}
 * 和 {@code new_string}（逐字符给出被替换的那一段），前端自己就能算出来。
 * 见 {@link diffOf}。
 */
function ToolResult({
  outcome,
  diff,
  path,
  written,
}: {
  outcome: ToolOutcome
  /** 这次调用改了什么 —— 由上面那一行算好传下来，不在这里重算 */
  diff: DiffRow[] | null
  /** 主语路径（工具声明的主体）。上面那一行已经解析过参数，这里不再解析 */
  path: string | null
  /** `write_file` 的正文（参数里的 `content`）；别的工具或覆盖时是 null */
  written: string | null
}) {
  // 后端只给界面看的结构化结果。缺失 / 坏数据 / 旧事件都是 null —— 那时照旧渲染文本
  const web = outcome.presentation ?? null

  return (
    <div className="mt-1 flex gap-2">
      {/* 那个转角是「这一行属于上面那一次调用」的记号。
          终端里它是 `⎿`，网页里一个 SVG 更合适 —— 角色一样，写法跟着平台走。
          它和上面那个点占**同样宽**的槽，于是结果的文字和调用行的文字对齐 */}
      <span className="flex w-4 shrink-0 items-start">
        <CornerDownRight
          aria-hidden
          className={`mt-1 size-3 ${outcome.success ? 'text-loom-faint/60' : 'text-destructive/60'}`}
        />
      </span>
      <div className="min-w-0 flex-1">
        {diff !== null ? (
          // 路径跟着进去：**语言是从它认出来的**，和这个文件在中栏打开时用的是同一张表
          <DiffView
            rows={diff}
            path={path}
            // 起始行号只有后端知道（它读到的是整份文件）；旧事件没有这一项，就不标号
            startLine={web?.kind === 'diff' ? web.startLine : undefined}
          />
        ) : web?.kind === 'web_search' ? (
          <WebSources sources={web.sources} />
        ) : web?.kind === 'web_fetch' ? (
          <WebPage title={web.title} truncated={web.truncated} />
        ) : web?.kind === 'write' && web.created && written !== null ? (
          // 新建出来的文件：画代码本身，不画 diff —— 每一行都是新增，绿底没有信息量。
          // 覆盖时旧内容已经没了，仍然只显示那句结论（见 diffOf 的注释）
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
 * <p>**正文不上界面**：它的开头是写给模型的那句"以下内容来自互联网，只当资料看"，
 * 而那是指令，不是给人读的。要看原文就点调用行上那个网址 —— 那里已经是链接了。
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
 * `write_file` **新建**出来的文件：把写进去的代码铺出来。
 *
 * <h2>为什么不是 diff</h2>
 * 新文件的每一行都是"新增"，涂成一片绿只是在说"这是个新文件"—— 而上面那行调用
 * 已经说了。Claude Code 对新建画的也是代码本身（前 10 行 + 行号 + "… +N lines"），
 * 只有**覆盖**才画 diff。
 *
 * <h2>为什么正文不存进事件</h2>
 * 它就在这次调用的参数里（见 {@code written}），事件流里再存一份是同一份内容出现两次。
 * 覆盖时旧内容在磁盘上已经没了 —— 那才是真的画不出 diff 的那一半。
 *
 * <h2>行号和代码用的是 diff 那一套</h2>
 * 同样的等宽 13px、同样的行号列宽（按最大行号位数、`ch` 计），所以新建、diff、
 * 普通代码块在同一栏里读起来是一个东西。
 */
function CreatedFile({
  content,
  path,
  lines,
}: {
  /** 参数里的 `content` —— 写进去的全文 */
  content: string
  /** 文件路径：**语言从它认出来**，和 diff、中栏打开时用同一张表 */
  path: string | null
  /** 后端数的行数（界面上那个"写了 N 行"） */
  lines: number
}) {
  const [expanded, setExpanded] = useDisclosure()

  const all = useMemo(() => {
    const rows = content.split('\n')
    // 末尾换行是**行终止符**，不是多出来的一个空行（和编辑器数行号一致）
    if (rows.length > 0 && rows[rows.length - 1] === '') {
      rows.pop()
    }
    return rows
  }, [content])

  const shown = expanded ? all : all.slice(0, CREATED_FILE_PREVIEW_LINES)
  const hidden = all.length - shown.length

  // **滚到了才分词**，和 diff 那一处一样
  const anchor = useRef<HTMLDivElement>(null)
  const seen = useViewportHighlighting(anchor)
  const language = languageOfPath(path)
  const tokens = useHighlighted(shown.join('\n'), language, seen)

  const digits = String(Math.max(lines, all.length, 1)).length
  const numberWidth = `${digits + 2}ch`
  // 数字和正文之间留 1.5rem：数字右对齐，`pr` + 同宽的 `calc` 会把数字整体往左推，
  // 而不是把正文挤到右边（见 CodeLines 同一处）
  const numberBox = `calc(${numberWidth} + 1.5rem)`

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
 * 搜索的结果卡片：折叠时**一条来源一行**（标题 + 域名/日期），展开时每条多两行摘要。
 *
 * <p>它比给模型看的那段文本多出来的东西只有一样：**能点开的链接**。
 * 文本里的 URL 是排给模型读的，人要点的是标题。
 */
function WebSources({ sources }: { sources: WebSource[] }) {
  const [expanded, setExpanded] = useDisclosure()
  const shown = expanded ? sources : sources.slice(0, SOURCE_PREVIEW_ROWS)
  const hidden = sources.length - shown.length

  return (
    <div className="min-w-0 space-y-1">
      {shown.map((source, index) => {
        const day = dayOf(source.date)
        // 和调用行那个网址**同一个守卫**（只认 http/https）：来源里的链接也是外面来的，
        // 不能因为"它长得像网址"就直接塞进 href
        const href = externalUrl(source.url)
        const label = source.title ?? hostOf(source.url)
        // 标题里已经带着站名时（"Change Log | DeepSeek API Docs"）不再重复一遍，
        // 只留日期 —— 一行里同一个词出现两次是最刺眼的那种杂乱
        const site = source.siteName !== undefined && !(source.title ?? '').includes(source.siteName)
          ? source.siteName
          : source.siteName === undefined ? hostOf(source.url) : undefined
        const last = index === shown.length - 1
        return (
          <div key={source.url} className="min-w-0">
            {/* **一条来源一行**：标题在前，来源和日期跟在后面。
                摘要只在展开时出现 —— 折叠着的时候它才是"杂乱"的来源：
                八条各占三行，一面墙都是小字 */}
            <p className="flex min-w-0 items-baseline gap-1.5">
              {href === null ? (
                // 认不出是 http/https：照常显示标题，但**不做成链接** ——
                // 点一个 `javascript:` 或 `file:` 不是"打开来源"
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
              {/* 折叠时：**接在最后一行末尾、靠右**（官网的「查看全部」也在那一行） */}
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
      {/* 展开之后：收起放在**整个列表的末尾、靠右** —— 挂在最后一条来源上会跟着它的长度跑，
          而那一条往往还有一段摘要，按钮就飘到列表中间去了 */}
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
 * 一段改动：没动的上下文、删掉的行、加上的行。
 *
 * <h2>行号从后端来</h2>
 * 前端手上只有"被替换的那一段"，不知道它在文件里的绝对位置 —— 那个数字只有
 * 后端（真正读到文件的那一侧）知道，随结果一起带过来（{@code startLine}）。
 * 没带就不标号：按提交看的整段改动就是那种（它可能跨几个 hunk）。
 *
 * <h2>哪一侧标哪个号</h2>
 * 删除的行标**旧**行号（它在新文件里已经没有了），其余一律标**新**行号 ——
 * 于是往下读到的号是连续递增的，和打开改完之后的文件看到的一致。
 * 列宽按最大那个行号的位数定，用 `ch`：等宽字体下 1ch 正好一位数字，
 * 三位数和四位数都对得齐。
 *
 * <h2>一对删/加行里再标一层词</h2>
 * 一行三十个字符只换了一个数，整行涂深只能说明"这行变了"；把变的那几个词
 * 再深一档，读的人才知道变的是什么。哪几个词换了由 {@link wordDiffs} 算。
 *
 * <h2>颜色不是唯一的信号</h2>
 * 底色只是帮忙扫，`+` / `−` 那两个记号才是真正的信息 —— 它们对读屏器也说得出来。
 */
export function DiffView({ rows, path, startLine }: {
  rows: DiffRow[]
  path: string | null
  /** 这一段在文件里的起始行号（后端随结果带过来）；没有就不标号 */
  startLine?: number
}) {
  const [expanded, setExpanded] = useDisclosure()
  const shown = expanded ? rows : rows.slice(0, DIFF_PREVIEW_ROWS)
  const hidden = rows.length - shown.length

  // **滚到了才分词**，和别处一样：一段流水里可能挂着几十个 diff
  const anchor = useRef<HTMLDivElement>(null)
  const seen = useViewportHighlighting(anchor)
  const language = languageOfPath(path)

  // 两侧**各自当成一整份文档**去分词，再按行贴回去。
  //
  // 一行一行单独分词是不行的：块注释、多行字符串、模板字符串都**跨行**，
  // 单独看每一行会把它们中间那几行当成普通代码上错色 —— 而错色比不上色难看得多。
  // 语言从**被改的那个文件的路径**认，和它在中栏打开时用的是同一张表。
  const newTokens = useHighlighted(sideText(rows, 'removed'), language, seen)
  const oldTokens = useHighlighted(sideText(rows, 'added'), language, seen)

  // 词级差异按**整段**算：收起时露出来的那几行也可能和后面的行配成对
  const words = useMemo(() => wordDiffs(rows), [rows])

  /**
   * 行号列的宽度。**先走一遍两侧的行号游标**拿到最大那个数 ——
   * 不能拿"显示出来的行数"估：中间那些 gap 也占行号。
   *
   * <p>两种来源：统一 diff 的行自带号（按提交看的那个入口）；会话里那段改动
   * 只有起始行，就按增删行数现场推。
   */
  const numberWidth = useMemo(() => {
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
    // 数字两侧各留一个字符的空，和 Claude Code 的 ` number ` 一样
    return max > 0 ? `${String(max).length + 2}ch` : null
  }, [rows, startLine])

  // 数字和正文之间再留半格（和 CreatedFile / CodeLines 一致）：数字右对齐 +
  // 同宽的 calc，数字整体往左推，正文的起点不变
  const numberBox = numberWidth === null ? null : `calc(${numberWidth} + 0.5rem)`

  // 每一侧各有一个游标：碰到这一侧的行就往下走一格。
  // 它们只在这一次渲染里活，`shown` 又是 `rows` 的前缀，所以两个游标始终对得上
  let newAt = 0
  let oldAt = 0

  // 行号：旧、新各一个游标，从后端给的那一行起数。未变的行两边一起走，
  // 删掉的行只走旧的一边，新增的只走新的一边 —— 于是改动前后错开的那几行自然对齐
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
          // **和正文同一个左边界**：人读的时候眼睛停在代码那一列，
          // 不该为了这句话往左跑回去
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
        // 上下文行**两侧都有**：它从新侧取颜色（两边文字一样），但旧侧的游标也要跟着走
        // —— 少了这一步，后面的删行会取到前面某一行上下文的分词，显示出**另一行的内容**
        const tokens = removed ? oldTokens?.[oldAt++] : newTokens?.[newAt++]
        if (row.kind === 'context') {
          oldAt++
        }
        // 删除的行标**旧**行号（它在新文件里已经没有了），其余一律标**新**行号。
        // **行自带号时优先用它**（统一 diff 里每行都知道自己是第几行），
        // 只有会话里那段"只知道起点"的改动才用游标数
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
        // 词级差异：删的那一行用 removed 一侧，加的那一行用 added 一侧
        const segments = removed
          ? words[index]?.removed ?? null
          : added
            ? words[index]?.added ?? null
            : null
        return (
          <div
            key={index}
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
            {/* 语义挂在这个记号上（`role="img"`），**不是**旁边再加一句 sr-only：
                加了的话，人复制这段 diff 会得到每行前面一个「增加」。
                记号本身还是 `+` / `−`，复制出去正好是它该有的样子 */}
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
      {/* 折叠按钮**和正文同一列**：它属于这段代码，不属于左边那两列 */}
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
 * 一侧的完整文本：把这一侧的行**按顺序**拼起来，交给分词器。
 *
 * <p>{@code gap} 行两侧都不属于 —— 它代表"这里有几行没显示"，
 * 而那几行既没被加也没被删，只是被折起来了。
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
 * 一行的文字。有分词就按 token 上色，没有就原样吐出来；
 * 再叠上「哪几个词真的换了」那层更深的底。
 *
 * <p>{@code memo} 是必要的：流式期间父组件每一帧都在重渲染，而分好的 token
 * 在两次分词之间是**同一个引用** —— 不 memo 的话每一帧都要重新 diff 几百个 span。
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
          <span key={index} style={{ color: token.color }}>
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
 * 把「按变化切好的段」和「按语法切好的 token」叠成同一串 span。
 *
 * <p>两套切法互不相干（一套按改没改、一套按语法），所以按**字符位置**求交集：
 * 游标在 token 里往前走，走到段的边界就换一个 changed 标记。
 * 分词器没准备好时 {@code tokens} 是 null —— 那时整段原样切成 span，只是不上色。
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
    // 分词器没吐全（认不出语言、还没分词、或者两边对不齐）：剩下的原样补上
    if (left > 0) {
      pieces.push({ text: segment.text.slice(segment.text.length - left), changed: segment.changed })
    }
  }
  return pieces
}

/**
 * 结果行说什么。
 *
 * <p>走到这里的都是**该露出来**的那些（成功率低的读文件、跑命令已经在
 * {@link resultWorthShowing} 那里被挡掉了）：一句话的结论（"已新建 X（N 行）"）、
 * 搜索命中的那几行、或者一条命令的报错。原样铺出来就是对的。
 *
 * <h2>空输出也要有一句话</h2>
 * 一片空白让人分不清是"没输出"还是"没加载出来"。
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
 * 这一次调用的动作词。
 *
 * <p>事件流里存的是模型调的接口名（{@code read_file}），那是**给机器看的**：
 * 下划线、英文、而且说的是"哪个函数"而不是"在干什么"。人话的那一面现在由
 * **工具自己声明**（见 {@link ToolView.label}）—— 从前它是这个文件里一张按工具名
 * 查的表，而那张表在加第八个工具的那天不会跟着变，症状只是"这条显示得糙"，
 * 看起来不像 bug。
 *
 * <p>取不到声明就**原样显示工具名**：多一个工具的代价是没翻译，而不是显示错。
 */
function verb(tool: ToolView | undefined, name: string): string {
  return tool?.label ?? name
}

/**
 * 参数里挑一样**最像"它在干什么"**的显示出来（路径、命令、模式），其余丢掉。
 *
 * <p>把整串 JSON 铺在流里，等于让人去解析它 —— 而人只需要认出"它动了哪个文件"。
 *
 * <p><b>是哪一项，由工具声明</b>（{@link ToolView.subjectKey}），不在这里按顺序试。
 * 从前这里是一张 {@code ['path','command','pattern','file_path']} 的顺序表：它有两个毛病 ——
 * 顺序本身就是个说不清的东西（"path 比 pattern 更算主语"？），而且它对每个工具都试同一串键。
 * 声明之后，工具没声明主语就是**不显示**，而不是碰巧试中一个别的字段。
 */
function summarize(
  tool: ToolView | undefined,
  args: Record<string, unknown> | null,
): string {
  const key = tool?.subjectKey
  // `undefined` 是"这个工具没声明主语"（线上 null 就是字段缺席），和"声明了但取不到值"是两件事
  if (key === undefined) {
    return ''
  }
  const value = args?.[key]
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.join(' ')
  return ''
}

/**
 * 参数串解析成对象。**解不出来就是 null，不是空对象** ——
 * 这两件事对调用方不一样：解不出来表示"这次调用没有可用的参数"，
 * 而空对象表示"参数是空的"。参数是模型给的，形状不能假定。
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
 * 这次调用动的是哪个文件 —— 只有它才值得做成一个能点的入口。
 *
 * <p>{@code glob} 的 pattern、{@code run_command} 的命令行里也可能有路径，
 * 但那要猜。**猜错的东西点上去会打开一个不相干的文件**，比不能点更糟。
 * 所以判据是工具声明的"我的主语是一个工作区路径"（{@link ToolView.subjectIsPath}），
 * 而不是一张工具名名单。
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
 * 这次调用把文件改成了什么样；不是文件改动、或者算不出来，就是 null。
 *
 * <h2>失败的编辑**不给** diff</h2>
 * 那次改动没有发生。画出来等于给人看一件没发生过的事 ——
 * 而且它和"改了但回退了"在画面上分不清。
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
  // edit_file 的参数里带着被替换的那一段（新旧都有），所以它是**真的** diff
  const before = args.old_string
  const after = args.new_string
  if (typeof before === 'string' && typeof after === 'string') {
    return diffLines(before, after)
  }

  // 剩下那条是整份写入（write_file）。**不给 diff。**
  //
  // 它的参数里只有"写成了什么"，没有"写之前是什么" —— 而"整份都是新增"只对**新建**
  // 成立。覆盖一条已有文件时那句话是假的：旧内容是被抹掉的，不是从来没存在过。
  // 而界面上它长得和新建一模一样，看的人分不出来。（这里从前就是那么画的，
  // 旁边还写着一句更假的话 ——"write_file 只在文件不存在时成功"。）
  //
  // Claude Code 在这一处给的是**工具在写的那一刻算出来的真 patch**，它手里有新有旧。
  // 我们不照做，理由是代价不对等：要拿到"写之前是什么"，要么让工具把整份旧内容塞进
  // 事件流（一次覆盖就是几十上百 KB 落在库里），要么自己养一个行级 diff 算法；
  // 而**我们这边有文件面板** —— 想知道这个文件现在长什么样，点一下就是全文。
  // Claude Code 那边是终端，没有"点开看看"这条路，所以它非得把 patch 算出来不可。
  //
  // 一条画得出来的假 diff 比没有 diff 更坏。
  //
  // **新建**不一样：那次调用本来就带着全文，画成"代码预览"（`CreatedFile`）是忠实的，
  // 而且和 Claude Code 一致 —— 它新建时画的也是代码本身，只有覆盖才画 diff。
  // 这里返回 null 之后，`ToolResult` 会看 presentation 里的 `write.created` 决定走哪条。
  return null
}

/**
 * 打算等多久，说成人话。
 *
 * <p>不到一秒的说毫秒：{@code 0} 和 {@code 500} 都落在这一档，而"约 500 毫秒后重试"
 * 比"约 0.5 秒后重试"读起来快一点。
 */
function formatDelay(delayMs: number): string {
  return delayMs < 1_000 ? `${delayMs} 毫秒` : `${(delayMs / 1_000).toFixed(1)} 秒`
}

/** 说给读屏器听的那句话。和 {@link UNFINISHED_WORD} 一一对应，改一个就要改另一个。 */
const UNFINISHED_STATE_WORD: Record<Unfinished, string> = {
  cancelled: '被取消了，没有结果',
  interrupted: '平台中断了，没有结果',
  rejected: '你拒绝了这次调用，没有结果',
  'no-result': '没有结果',
}

/** 状态说给人听（也是说给读屏器听）。**记号画得出来，但读不出来。** */
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

/** 一行小字。**只有异常上色** —— 正常的事情不需要颜色。 */
function Notice({ tone, text }: { tone: NoticeTone; text: string }) {
  const color: Record<NoticeTone, string> = {
    plain: 'text-loom-faint',
    good: 'text-muted-foreground',
    bad: 'text-destructive',
  }
  return <p className={`text-xs ${color[tone]}`}>{text}</p>
}
