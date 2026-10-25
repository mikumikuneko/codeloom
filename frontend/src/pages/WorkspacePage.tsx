import { LogOut, MessageSquarePlus, PanelRightClose, PanelRightOpen, Settings } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { ChatPanel } from '@/components/workspace/ChatPanel'
import { CodeView } from '@/components/workspace/CodeView'
import { ProjectDiffView } from '@/components/workspace/ProjectDiffView'
import { Composer } from '@/components/workspace/Composer'
import { CollaboratorsDialog } from '@/components/workspace/CollaboratorsDialog'
import { ConflictResolver } from '@/components/workspace/ConflictResolver'
import { EditorTabs, type EditorTab } from '@/components/workspace/EditorTabs'
import { FileTree } from '@/components/workspace/FileTree'
import { RewindPicker } from '@/components/workspace/RewindPicker'
import { SessionHistoryDialog } from '@/components/workspace/SessionHistoryDialog'
import { SessionStreamView } from '@/components/workspace/SessionStreamView'
import { Splitter } from '@/components/workspace/Splitter'
import { Button } from '@/components/ui/button'
import { ApiError, errorText, merge, projects, sessions as sessionsApi, type Conflicts } from '@/lib/api'
import { personDot, slotsOf, type PersonSlot } from '@/lib/people'
import type { NoticeTone } from '@/lib/sessionStream'
import { trackTheirSessions } from '@/lib/sessionUnread'
import { readStorage, removeStorage } from '@/lib/storage'
import { useAsync } from '@/lib/useAsync'
import { usePaneWidths } from '@/lib/usePaneWidths'
import { useAuth } from '@/stores/auth'

const NO_CONFLICTS: Conflicts = { direction: null, conflicts: [] }

/**
 * 两次"重看磁盘"之间至少隔多久。
 *
 * 这个数在说人眼能分辨多快，不是性能：agent 写文件成簇，一次改动拉一次会把一棵
 * 展开了五层的树连着打十几遍，而人看到的只是"文件唰地一下都出现了"。太短等于没
 * 合并，太长会觉得树反应迟钝。
 */
const WORKSPACE_RELOAD_MS = 400

/** 顶栏右侧那行小字。没有弹出层、没有自动消失：它只是把刚才那一下的结果说出来。 */
interface Notice {
  /** 与事件流里那行小字同一个联合类型：两处各写一遍的话，加一种 tone 时这里不会报错 */
  tone: NoticeTone
  text: string
}

/**
 * 工作区。点进项目就是这里，没有中间那一层页面。
 *
 * 左中右三栏各自回答一个问题：左，我的代码现在是什么样；中，这个文件里写了什么；
 * 右，我和他的 agent 分别干了什么。
 *
 * 视觉上它是一整片而不是三块面板：三栏之间只有一条几乎看不见的分界线，没有底色差、
 * 圆角、阴影。每栏各成一块面板会让整个东西变成仪表盘，而这是读代码和读对话的地方。
 *
 * 会话不在这里新建：要等右栏输入框发出第一句话才产生（那一步要带模型配置）。
 * 已有的话，右栏显示你最后看的那一条，历史在右上角图标里。
 */
export function WorkspacePage() {
  const { projectId = '' } = useParams()
  const me = useAuth((s) => s.user)
  const logout = useAuth((s) => s.logout)
  const navigate = useNavigate()

  const project = useAsync(() => projects.one(projectId), [projectId])
  const all = useAsync(() => sessionsApi.forProject(projectId), [projectId])

  // 三栏这一行的宽度，用来夹住左右两栏能拖多远（见 usePaneWidths）。
  const row = useRef<HTMLDivElement>(null)
  const panes = usePaneWidths(row)

  /**
   * 中栏打开过的标签。文件按路径去重，改动按「提交 + 路径」去重；同一份东西再从
   * 树或会话流里点一次只会切过去，不会叠出第二个标签。
   */
  const [editorTabs, setEditorTabs] = useState<EditorTab[]>([])
  const [activeTabKey, setActiveTabKey] = useState<string | null>(null)
  const [closingTabs, setClosingTabs] = useState<
    { kind: 'one' | 'others' | 'all'; key?: string } | null
  >(null)
  const activeTab = editorTabs.find((tab) => tab.key === activeTabKey) ?? null
  /** 左树高亮跟着当前标签走；改动标签也落在它对应的文件路径上 */
  const selectedFile = activeTab?.path ?? null

  function openEditorTab(tab: EditorTab) {
    setEditorTabs((current) =>
      current.some((one) => one.key === tab.key) ? current : [...current, tab],
    )
    setActiveTabKey(tab.key)
  }

  function openFile(path: string | null) {
    if (path === null) {
      if (activeTab?.kind === 'file') {
        closeTab(activeTab.key)
      }
      return
    }
    openEditorTab({ key: `file:${path}`, kind: 'file', path })
  }

  /**
   * 点"这一轮改了哪些文件"里的某一条：中栏切成那一段改动。
   *
   * 再点一次同一条就关掉，和点树里同一个节点是同一种手感。关掉后落到相邻标签。
   */
  function openDiff(commitSha: string, path: string) {
    const key = `diff:${commitSha}:${path}`
    if (activeTabKey === key) {
      closeTab(key)
      return
    }
    openEditorTab({ key, kind: 'diff', path, commitSha })
  }

  function closeTab(key: string) {
    const index = editorTabs.findIndex((tab) => tab.key === key)
    if (index === -1) {
      return
    }
    const next = editorTabs.filter((tab) => tab.key !== key)
    setEditorTabs(next)
    if (activeTabKey === key) {
      setActiveTabKey(next[Math.min(index, next.length - 1)]?.key ?? null)
    }
  }

  function closeOtherTabs(key: string) {
    const target = editorTabs.find((tab) => tab.key === key)
    if (target === undefined) {
      return
    }
    setEditorTabs([target])
    setActiveTabKey(target.key)
  }

  function closeAllTabs() {
    setEditorTabs([])
    setActiveTabKey(null)
  }

  /** 关之前先看有没有未保存的修改；有就交给确认框，不静默丢。 */
  function requestCloseTab(key: string) {
    if (editorTabs.find((tab) => tab.key === key)?.dirty === true) {
      setClosingTabs({ kind: 'one', key })
      return
    }
    closeTab(key)
  }

  function requestCloseOtherTabs(key: string) {
    if (editorTabs.some((tab) => tab.key !== key && tab.dirty === true)) {
      setClosingTabs({ kind: 'others', key })
      return
    }
    closeOtherTabs(key)
  }

  function requestCloseAllTabs() {
    if (editorTabs.some((tab) => tab.dirty === true)) {
      setClosingTabs({ kind: 'all' })
      return
    }
    closeAllTabs()
  }

  function confirmClosingTabs() {
    if (closingTabs === null) {
      return
    }
    if (closingTabs.kind === 'one' && closingTabs.key !== undefined) {
      closeTab(closingTabs.key)
    } else if (closingTabs.kind === 'others' && closingTabs.key !== undefined) {
      closeOtherTabs(closingTabs.key)
    } else {
      closeAllTabs()
    }
    setClosingTabs(null)
  }

  /** 拖拽排序：只换顺序，不换当前标签 */
  function reorderTabs(fromKey: string, toKey: string, position: 'before' | 'after') {
    if (fromKey === toKey) {
      return
    }
    setEditorTabs((current) => {
      const fromIndex = current.findIndex((tab) => tab.key === fromKey)
      if (fromIndex === -1) {
        return current
      }
      const next = current.filter((tab) => tab.key !== fromKey)
      const targetIndex = next.findIndex((tab) => tab.key === toKey)
      if (targetIndex === -1) {
        return current
      }
      const at = position === 'after' ? targetIndex + 1 : targetIndex
      next.splice(at, 0, current[fromIndex])
      return next
    })
  }

  /**
   * 改名或移动之后，把打开着的标签指到新路径。
   *
   * 不跟着搬的话标签停在旧路径上，下次重读或保存会对着一个不存在的文件报错。
   * 目录被移动时，它下面的文件标签一起搬。
   */
  function moveOpenTabs(from: string, to: string) {
    const moved = (path: string) =>
      path === from ? to : path.startsWith(`${from}/`) ? `${to}${path.slice(from.length)}` : path
    setEditorTabs((current) => {
      const seen = new Set<string>()
      const next: EditorTab[] = []
      for (const tab of current) {
        const path = tab.kind === 'file' ? moved(tab.path) : tab.path
        const key = tab.kind === 'file' ? `file:${path}` : tab.key
        // 新路径本来就有标签时留一个，不然同一份文件会出现两个相同身份的标签
        if (seen.has(key)) {
          continue
        }
        seen.add(key)
        next.push(path === tab.path && key === tab.key ? tab : { ...tab, path, key })
      }
      return next
    })
    setActiveTabKey((key) => {
      if (key === null || !key.startsWith('file:')) {
        return key
      }
      return `file:${moved(key.slice('file:'.length))}`
    })
  }

  function markTabDirty(key: string, dirty: boolean) {
    setEditorTabs((current) =>
      current.map((tab) => (tab.key === key ? { ...tab, dirty } : tab)),
    )
  }
  const [tab, setTab] = useState<'session' | 'chat' | 'watch'>('session')
  /**
   * 右栏是否收起。收起不是卸载：聊天室和会话流要一直挂着（见右栏那段注释），
   * 所以这里只把整栏藏进一条窄边，展开后状态还在。
   */
  const [rightCollapsed, setRightCollapsed] = useState(false)
  const [mySession, setMySession] = useState<string | null>(null)
  const [theirSession, setTheirSession] = useState<string | null>(null)

  /**
   * Ctrl/Cmd+A 只在代码区生效。
   *
   * 浏览器默认会选整页，连左右两栏和行号一起选上。这里在捕获阶段拦掉：目标不在
   * data-select-all-scope 里就不让浏览器做默认全选；在代码区里则只选 content 标记
   * 的那一段（代码列，不含行号）。输入框、文本域和可编辑区域保持系统默认，否则
   * 连自己刚打的字都选不了。
   */
  useEffect(() => {
    const onSelectAll = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'a') {
        return
      }
      const target = event.target
      if (
        target instanceof Element &&
        target.closest('input, textarea, [contenteditable], .cm-editor') !== null
      ) {
        return
      }
      const scope = target instanceof Element
        ? target.closest<HTMLElement>('[data-select-all-scope]')
        : null
      event.preventDefault()
      if (scope === null) {
        return
      }
      const content = scope.querySelector<HTMLElement>('[data-select-all-content]') ?? scope
      const range = document.createRange()
      range.selectNodeContents(content)
      const selection = window.getSelection()
      if (selection === null) {
        return
      }
      selection.removeAllRanges()
      selection.addRange(range)
    }
    document.addEventListener('keydown', onSelectAll, true)
    return () => document.removeEventListener('keydown', onSelectAll, true)
  }, [])
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)
  /** 「合并」的确认框。同步不需要：它只动自己的树；合并会动到共享的主干 */
  const [confirmingMerge, setConfirmingMerge] = useState(false)

  // 同步/合并的结果只是"刚才那一下"的回执，不是常驻状态。留着不走，顶栏中间会一直
  // 挂着几小时前的话，像还在发生。失败也在五秒后收掉：要看就再点一次。
  useEffect(() => {
    if (notice === null) {
      return
    }
    const timer = window.setTimeout(() => setNotice(null), 5000)
    return () => window.clearTimeout(timer)
  }, [notice])

  /**
   * 聊天室里有几条是我没看的时候来的。
   *
   * ChatPanel 一直挂着（切到别的标签也不卸载），连接一直开着，否则"对方说话"只有
   * 切回那一刻才知道。它每次消息条数变化都报一声，这里拿上一次看见的条数作差。
   *
   * `chatSeen` 为 null 表示还没拿到第一次历史：那一批不算未读，否则每次进项目都会
   * 凭空多一个点。
   */
  const [chatUnread, setChatUnread] = useState(0)
  const chatCount = useRef(0)
  const chatSeen = useRef<number | null>(null)

  // active 由 ChatPanel 一起报上来：它手上的值就是这一帧的事实，比在这里再维护一个
  // tab ref 少一次同步，也没有"effect 还没跑"的窗口。
  const onChatMessages = useCallback((count: number, active: boolean) => {
    chatCount.current = count
    if (chatSeen.current === null) {
      chatSeen.current = count
      return
    }
    if (active) {
      chatSeen.current = count
      setChatUnread(0)
      return
    }
    setChatUnread(Math.max(0, count - chatSeen.current))
  }, [])

  // 换项目：未读基准归零。上一个项目的"看到第几条"对新项目没有意义。
  useEffect(() => {
    chatSeen.current = null
    chatCount.current = 0
    setChatUnread(0)
  }, [projectId])

  /** 切标签。切到聊天 = 已读；切到会看别人的标签时顺手重拉一次列表（见下面的轮询）。 */
  function openTab(next: 'session' | 'chat' | 'watch') {
    setTab(next)
    if (next === 'chat') {
      chatSeen.current = chatCount.current
      setChatUnread(0)
    }
    if (next !== 'session') {
      project.reload(true)
      all.reload(true)
    }
  }

  /**
   * 磁盘上又变了一版。看着磁盘的两样东西都吃它：左边那棵树，和中栏打开的那份文件。
   * 它们自己都看不见磁盘，见 {@link refreshWorkspace}。
   */
  const [workspaceReload, setWorkspaceReload] = useState(0)
  const workspaceReloadTimer = useRef<number | undefined>(undefined)

  /**
   * 我的工作区在磁盘上变了，让看着磁盘的东西都重看一遍。agent 动文件（会话流推
   * 上来）、同步、合并、裁决落盘、回滚，最后都汇到这里；其中几处经
   * {@link rereadWorkspace} 连冲突清单一起重读。
   *
   * 用 trailing（等安静下来才拉）而不是 leading：leading 的话一串连续改动只有第一
   * 次会拉，刚写出来的新文件要等下次有人动才出现。
   *
   * 中栏也走这一根线：磁盘变了只有一个来源。另接一根迟早会忘，而漏掉的那边不报错，
   * 只是停在旧样子上。
   */
  const refreshWorkspace = useCallback(() => {
    if (workspaceReloadTimer.current !== undefined) {
      return
    }
    workspaceReloadTimer.current = window.setTimeout(() => {
      workspaceReloadTimer.current = undefined
      setWorkspaceReload((n) => n + 1)
    }, WORKSPACE_RELOAD_MS)
  }, [])

  useEffect(() => () => window.clearTimeout(workspaceReloadTimer.current), [])

  /**
   * 左栏那棵树（和中栏那份文件）看的是我的还是主干。
   *
   * 状态在这一层，因为两处必须看同一棵树：树里点一个路径，中栏读的得是同一棵上的
   * 文件。各存各的话，切到主干后点一个文件，中栏给你看自己那份，而两边路径长得一样。
   */
  const [trunk, setTrunk] = useState(false)

  // 换项目或换树时把标签清掉：同一个路径在另一棵树上是另一份内容，留着会把旧标签
  // 指到新树上。
  useEffect(() => {
    setEditorTabs([])
    setActiveTabKey(null)
  }, [projectId, trunk])

  /** 回滚界面开着吗。状态在这一层，因为它要换掉 footer */
  const [rewinding, setRewinding] = useState(false)

  /**
   * 回滚之后要塞回输入框的那句话。
   *
   * 回滚退掉的正是你刚说的那句，而点回滚十有八九是想改两个字重说一遍。它只被读一次：
   * `ComposerBox` 在挂载时拿它当初始值，之后框归它自己。所以打开回滚时清掉它——那
   * 一步正是让输入框重新挂载的一步，不清的话上次退回来的话会再次冒出来。
   */
  const [draft, setDraft] = useState('')

  /**
   * 回滚之后，会话流要从头重拉一遍。
   *
   * 回滚会把对话截断（这是它和"只退代码"的区别），而前端手里那条流是折出来的，
   * 不会因为来了几条新事件就自己变短，界面上会显示一段已不存在的历史。所以整条流
   * 用 key 重建一次；不给它加"重置"信号，信号要一路传到 hook 里，中间每层都得记得
   * 转发。
   */
  const [streamEpoch, setStreamEpoch] = useState(0)

  // 冲突清单：进页面探一次，之后每次同步/合并后再探。没有冲突时是空信封而不是 404，
  // "没有冲突"是正常结果，不是错误。
  const pending = useAsync(
    () => (mySession ? merge.conflicts(mySession) : Promise.resolve(NO_CONFLICTS)),
    [mySession],
  )

  /** 冲突还没裁决完。两个动作此刻都不该再发出去：后端也会拒（409），但按钮该先说话 */
  const conflictPending = (pending.data?.conflicts.length ?? 0) > 0

  /**
   * 这次冲突是点哪个按钮撞出来的。
   *
   * 后端给的方向说的是"冲突落在哪一步"，而合并会先同步，于是点「合并」撞出的冲突
   * 方向也是 INTO_SESSION，裁决页却写着"同步时撞上的"，像点错了按钮。只在这一次
   * 点击里有效；刷新后退回按方向说。
   */
  const [conflictTrigger, setConflictTrigger] = useState<'SYNC' | 'MERGE' | null>(null)

  /**
   * 两个人的列表自己会变：对方进来、开一条新会话、删一条会话。
   *
   * 这些事实没有一条会推给另一侧（SSE 是每条会话一条的，而我们连"对方开了一条新
   * 会话"都不知道）。所以每 5 秒重拉一次项目和会话列表，页面不可见时不拉。理由见
   * `docs/decisions/feature/2026-10-18-live-lists-poll-while-visible.md`。
   *
   * 用 quiet 重拉：不能每 5 秒闪一次"正在打开…"，也不能让一次网络抖动把整页换成
   * 报错。内容没变时 useAsync 不换 data 引用，不会白渲染。
   */
  const liveReload = useRef<() => void>(() => {})
  // 在 effect 里写 ref（而不是渲染期间）。第一次 tick 要等 5 秒后，那时它已经就位。
  useEffect(() => {
    liveReload.current = () => {
      project.reload(true)
      all.reload(true)
    }
  })

  useEffect(() => {
    const tick = () => {
      if (document.visibilityState === 'visible') {
        liveReload.current()
      }
    }
    const timer = window.setInterval(tick, 5000)
    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        liveReload.current()
      }
    }
    window.addEventListener('focus', onVisible)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', onVisible)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])

  async function signOut() {
    await logout()
    navigate('/login', { replace: true })
  }

  /**
   * 磁盘变过之后，把看着磁盘的两样一起重来：左边那棵树，和冲突清单。
   *
   * 它们本是同一份事实的两面（树里有什么 / 哪几个撞上了），一起走过的路径（同步、
   * 合并、裁决）分头重来一定会漏掉一边，而漏掉的那边不报错，只是停在旧样子上。
   */
  function rereadWorkspace() {
    pending.reload()
    refreshWorkspace()
  }

  /**
   * 同步或合并。两者失败方式一样（409），收在一处处理。
   *
   * 409 有三种含义，处置完全不同：有冲突 → 去中栏裁决（重拉冲突清单，界面自己会
   * 切过去）；落后于主干 → 告诉人先同步；会话正在跑 → 等它跑完。后端把原因写进
   * 响应体正是为了这个。
   */
  async function act(
    trigger: 'SYNC' | 'MERGE',
    what: () => Promise<unknown>,
    done: (status: string) => Notice,
  ) {
    setBusy(true)
    setNotice(null)
    try {
      const result = (await what()) as { status?: string } | void
      setNotice(done(result?.status ?? ''))
      rereadWorkspace()
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const behind = e.problem.commitsBehind
        const conflicting = e.problem.conflictingPaths
        if (conflicting && conflicting.length > 0) {
          setConflictTrigger(trigger)
          setNotice({ tone: 'plain', text: `${conflicting.length} 个文件冲突了，在中间裁决` })
          // 撞在冲突上时磁盘也变了：git merge --no-commit 把冲突标记写进了文件。
          // 树必须跟上；中栏此时由裁决页顶替，文件标签也吃同一个重读信号。
          rereadWorkspace()
        } else if (typeof behind === 'number') {
          setNotice({ tone: 'plain', text: `你落后主干 ${behind} 个提交，先同步一下` })
        } else {
          setNotice({ tone: 'plain', text: e.message })
        }
      } else {
        setNotice({ tone: 'bad', text: errorText(e) })
      }
    } finally {
      setBusy(false)
    }
  }

  // 三份派生数组都 memo 住：useAsync 在内容没变时保住了 data 的引用，但 filter / ?? []
  // 每次渲染都造新数组，而它们正是下面几个 effect 和 nameOf 的依赖。不 memo 的话，
  // 整条会话流会跟着父组件每次渲染重算。
  const members = useMemo(() => project.data?.members ?? [], [project.data])

  /**
   * 按 id 查一个人叫什么。事件流里带的都是用户 id（用户名会变），名字在这一层查：
   * 改过名之后历史那几行也跟着变。
   *
   * 它必须是稳定函数：这个值一路传到 memo 过的条目上，每次渲染换新的等于让每条都
   * 重渲染，而条目里有 markdown 和 diff，流式输出时每帧一次。所以挂在成员表上
   * （成员表从 useAsync 来，是稳定的）。
   */
  const nameOf = useMemo(() => {
    const byId = new Map(members.map((member) => [member.id, member.displayName]))
    return (id: string) => byId.get(id) ?? null
  }, [members])
  const mine = useMemo(
    () => all.data?.filter((s) => s.ownerId === me?.id) ?? [],
    [all.data, me?.id],
  )
  const teammate = members.find((m) => m.id !== me?.id)
  const theirs = useMemo(
    () => all.data?.filter((s) => s.ownerId === teammate?.id) ?? [],
    [all.data, teammate?.id],
  )

  /**
   * 观战栏空着时说什么。
   *
   * 三种情况分开说：还没有队友、队友还没有会话、正在打开。一句写死的"对方还没有
   * 会话"会对已经在项目里的人说错话，列表还没刷新时还会把人往错的方向带。
   */
  const watchHint =
    teammate === undefined
      ? '还没有队友。把邀请链接发给对方，他加入之后这里会显示他的会话。'
      : theirs.length === 0
        ? `${teammate.displayName} 还没有会话。他说话之后，这里会实时显示。`
        : '正在打开对方的会话…'

  /**
   * 对方新开的会话，在我没看观战栏时点一个未读点。
   *
   * 会话列表每 5 秒自刷（见上面的轮询），所以"对方开工了"会出现；但我停在别的标签
   * 时它只是安静地躺着。这颗点就是那句话。正在看观战栏时不算未读。
   */
  const [watchUnread, setWatchUnread] = useState(false)
  const theirSeen = useRef<Set<string> | null>(null)
  const theirSeenProject = useRef(projectId)

  useEffect(() => {
    // 换项目：上一个项目"看过哪些"对新项目没有意义。
    if (theirSeenProject.current !== projectId) {
      theirSeenProject.current = projectId
      theirSeen.current = null
      setWatchUnread(false)
      return
    }
    if (all.data === null) {
      return
    }
    const next = trackTheirSessions(
      theirSeen.current,
      theirs.map((s) => s.id),
      tab === 'watch',
    )
    theirSeen.current = next.seen
    setWatchUnread(next.unread)
  }, [projectId, all.data, theirs, tab])

  // 默认选中哪条：记住上次看的那条，记不住就退回列表第一条。
  //
  // 用 localStorage 而不是"最近创建的那条"：会话表里没有创建时间，为默认选谁加一列
  // 代价大于收益；localStorage 丢了（换浏览器、清缓存）只是退回第一条，是合理的退化。
  /**
   * 用户刚点过"新建会话"。
   *
   * 必须有这个标记：下面自动选中的 effect 依赖 `mine`，而列表每次刷新都会得到一个新
   * 数组、让 effect 再跑一遍；光把 `mySession` 置空，下一次就替你把 `mine[0]` 选回来。
   * choose 会清掉它（选了一条就不再算"要新的"）。
   */
  const wantsFresh = useRef(false)

  useEffect(() => {
    if (wantsFresh.current) return
    setMySession((current) => {
      // 选中的那条可能已经不在了（被删、或列表刷新过），留着不行：右栏会一直连一条
      // 不存在的会话，服务端回 404，浏览器永远重连，界面挂着消不掉的"连接断了"。
      // 所以这里不能只填空，也要能清。
      if (current !== null && mine.some((s) => s.id === current)) {
        return current
      }
      if (mine.length === 0) {
        return null
      }
      // 记住的那条同理：也可能是一条已经删掉的。
      const rememberedId = remembered(projectId)
      return rememberedId !== null && mine.some((s) => s.id === rememberedId)
        ? rememberedId
        : mine[0].id
    })
  }, [mine, projectId])

  useEffect(() => {
    if (theirs.length > 0) {
      setTheirSession((current) => current ?? theirs[0].id)
    }
  }, [theirs])

  /**
   * 正在看的那条对方的会话没了（他删了它，或退出了项目）。
   *
   * 不清掉的话右栏会一直连一条不存在的会话：404、EventSource 一直重连，而列表里
   * 那条要等刷新才消失。清掉并说出来：空状态说的是"对方还没有会话"，而真实原因是
   * "你正在看的那条刚被删了"。
   */
  useEffect(() => {
    if (theirSession === null || theirs.some((s) => s.id === theirSession)) {
      return
    }
    setTheirSession(theirs[0]?.id ?? null)
    setNotice({ tone: 'plain', text: '对方的这条会话已经不在了' })
  }, [theirs, theirSession])

  if (project.loading) return <Centered>正在打开…</Centered>
  if (project.error) return <Centered tone="error">{project.error}</Centered>
  if (!project.data) return null

  const data = project.data
  const slots = slotsOf(data.members.map((m) => m.id))
  const mySlot: PersonSlot = slots.get(me?.id ?? '') ?? 'a'
  const theirSlot: PersonSlot = slots.get(teammate?.id ?? '') ?? 'b'

  function choose(sessionId: string) {
    // 选了一条（或刚由第一句话建出来一条）就不再算"要开新的"。
    wantsFresh.current = false
    setMySession(sessionId)
    localStorage.setItem(rememberKey(projectId), sessionId)
  }

  /**
   * 开一条新对话：放掉当前这条，右栏回到"还没有会话"，下一句话就会开一条新的。
   *
   * 不在这里建会话：会话要等右栏真发出第一句话才产生（见类注释）。先建一条空的会
   * 留下垃圾，还会把"记住上次看的那条"顶掉。
   */
  function startNewConversation() {
    wantsFresh.current = true
    setMySession(null)
    // 连记住的那条一起清掉：不清的话刷新后它又被捡回来（见上面的 effect）。
    removeStorage(rememberKey(projectId))
  }

  /**
   * 丢弃一条会话。对话删除，代码保留：树属于「人 + 项目」，不属于某条会话。
   *
   * 删掉的正好是"记住的那条"时，要连记录一起清：不清的话刷新后它又被捡回来，而
   * 那条会话已不存在。清掉之后会落到剩下第一条；一条不剩时右栏回到"还没有会话"。
   *
   * 不在这里拦"正在跑"：后端抢的是那棵树的租约，"正在执行中"也可能是这个人另一条
   * 会话在跑，光看这一行看不出来。与其时灵时不灵地禁用，不如让它如实报回来（那句话
   * 会原样显示在确认框里）。
   */
  async function discardSession(sessionId: string): Promise<string | null> {
    try {
      await sessionsApi.discard(sessionId)
    } catch (e) {
      return errorText(e)
    }
    if (mySession === sessionId) {
      removeStorage(rememberKey(projectId))
    }
    // 这里不要顺手 setMySession(null)。此刻 mine 还是刷新前那份、里面还有刚删的
    // 这条，而下面的 effect 是"空着就从 mine 里挑一条"，会把刚删掉的又选回来，
    // 之后列表刷新也没人纠正。让选中那条先留着，等 reload 回来 mine 里没有它，
    // effect 会自己挪到一条还在的上。
    all.reload()
    return null
  }

  return (
    // overflow-hidden 不是装饰：这个 div 占满视口，自己绝不该滚。少了它，任何横向
    // 溢出都会带来横向滚动条，而 100vh 包含滚动条高度，于是 h-screen 比可用高度高，
    // 整页能纵向滚，三栏被推出去。滚动该发生在里面那几个 overflow-auto。
    <div className="flex h-screen flex-col overflow-hidden">
      {/* 顶栏。这是全站唯一一条真的在分隔东西的线：它下面是三栏各自的滚动区 */}
      <header className="relative flex h-12 shrink-0 items-center gap-3 border-b border-border px-4">
        <Link to="/" className="text-sm text-muted-foreground hover:text-foreground">
          codeloom
        </Link>
        <span className="text-muted-foreground/40">/</span>
        {/* 项目名是这一页的主语，所以比周围重一档：顶栏里只有这一处加重 */}
        <h1 className="truncate text-sm font-medium">{data.name}</h1>

        {/* 「看哪棵树」的切换不在这儿，在左边树头部：那里是它影响的东西所在，答的
            问题跟着那棵树一起看才顺；摆在顶栏会让人以为它管整个页面 */}

        {/* 反馈落在顶栏正中间的空地方。放进右边按钮排里，一出现就会把同步/合并/
            协作/设置/登出挤开。pointer-events-none：窄屏和按钮叠上时也不挡 */}
        {notice && (
          <span
            role="status"
            className={`pointer-events-none absolute left-1/2 max-w-[min(28rem,45vw)] -translate-x-1/2 truncate text-xs ${
              notice.tone === 'bad' ? 'text-destructive' : 'text-muted-foreground'
            }`}
          >
            {notice.text}
          </span>
        )}

        {/* 顶栏只放偶尔做一次的事，不放常用的东西：常用的都在三栏里，顶栏一热闹
            三栏就不安静了。同步和合并正属于这一档 */}
        <div className="ml-auto flex items-center gap-0.5">
          {mySession && (
            <>
              <Button
                variant="ghost"
                size="sm"
                disabled={busy || conflictPending}
                title={
                  conflictPending
                    ? '先裁决冲突，或者放弃合并'
                    : '把主干的改动拉进我的工作区，只影响我的树'
                }
                className="text-muted-foreground"
                onClick={() =>
                  void act('SYNC', () => merge.sync(mySession), (status) => ({
                    tone: 'plain',
                    text: status === 'UP_TO_DATE' ? '主干没有新东西' : '已经同步到最新',
                  }))
                }
              >
                同步
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={busy || conflictPending}
                title={
                  conflictPending
                    ? '先裁决冲突，或者放弃合并'
                    : '把我的改动合进主干，对方也看得到'
                }
                onClick={() => setConfirmingMerge(true)}
              >
                合并
              </Button>
            </>
          )}

          <CollaboratorsDialog project={data} onOpen={() => project.reload(true)} />
          <Button variant="ghost" size="icon-sm" asChild className="text-muted-foreground">
            <Link to="/settings" title="设置">
              <Settings className="size-4" />
            </Link>
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            title="登出"
            className="text-muted-foreground"
            onClick={() => void signOut()}
          >
            <LogOut className="size-4" />
          </Button>
        </div>
      </header>

      {/* 三栏这一行。左右两栏宽度是拖出来的（见 usePaneWidths），走 inline style；
          中间靠 flex-1 吃掉剩下的 */}
      <div ref={row} className="flex min-h-0 flex-1">
        {/* 左：树。它是一条导航，宽度由人拖出来。这一栏的头和身子整块归 FileTree：
            头上两个动作作用在整棵树上，状态留在树里比从这一层往下传干净 */}
        <aside className="flex min-h-0 shrink-0 flex-col" style={{ width: panes.left }}>
          {/* key 挂「项目 + 看哪棵树」：换项目或切到主干都整棵树重建。展开路径、
              每层拉回的内容全是另一棵树上的；只重拉根目录会让展开过的层级留着旧内容，
              而两边路径一样。切主干和换项目在这里是同一件事，所以用同一个 key，
              不另写清理 state 的规则 */}
          <FileTree
            key={`${projectId}:${trunk}`}
            projectId={projectId}
            rootName={data.rootName}
            selected={selectedFile}
            // 点树里的文件 = 打开（或切到）它的标签
            onSelect={openFile}
            onMoved={moveOpenTabs}
            trunk={trunk}
            onTrunk={setTrunk}

            // agent 动过文件、或刚同步/合并完，这个数就涨一次。
            reload={workspaceReload}
          />
        </aside>

        <Splitter
          label="拖动调整项目栏宽度"
          onResize={(delta) => panes.resize('left', delta)}
          onReset={panes.reset}
        />

        {/* 中：代码。两条分界线由两个把手画（它们同时是那条线，见 Splitter）。有
            冲突时切成两份并排，那正是它够宽的理由。

            min-h-0 是这条里最要紧的类：这是内容最高的地方（长 README 好几屏），少了
            它，flex 子项的自动最小高度会把这一栏撑到内容那么高，一路顶破三栏和
            h-screen，整页能滚。有了它，高度停在视口内，多出的部分由里面的
            overflow-auto 接管 */}
        <main className="flex min-h-0 min-w-0 flex-1 flex-col">
          {mySession && (pending.data?.conflicts.length ?? 0) > 0 ? (
            <ConflictResolver
              sessionId={mySession}
              conflicts={pending.data as Conflicts}
              trigger={conflictTrigger}
              onResolved={(outcome) => {
                // 裁决就是把冲突标记从文件里抹掉并落盘，磁盘变了。
                rereadWorkspace()
                const left = outcome.remainingConflicts.length
                if (left > 0) {
                  setNotice({ tone: 'plain', text: `还有 ${left} 个文件要裁决` })
                  return
                }
                // 裁决完不等于合完：撞在同步那一步的冲突，收尾的只是同步，主干还停在
                // 原处，要再点一次合并才落地。这句话必须说，否则用户会以为"冲突没了"
                // 就结束了。
                setNotice(
                  pending.data?.direction === 'INTO_SESSION'
                    ? { tone: 'plain', text: '冲突处理完了。主干还没动，再点一次合并' }
                    : { tone: 'good', text: '冲突处理完了，已经合进主干' },
                )
              }}
              onAbort={() =>
                void act('MERGE', () => merge.abort(mySession), () => ({
                  tone: 'plain',
                  text: '放弃这次合并了，工作区回到合并前',
                }))
              }
            />
          ) : (
            <>
              {editorTabs.length > 0 && (
                <EditorTabs
                  tabs={editorTabs}
                  activeKey={activeTabKey}
                  onActivate={setActiveTabKey}
                  onClose={requestCloseTab}
                  onCloseOthers={requestCloseOtherTabs}
                  onCloseAll={requestCloseAllTabs}
                  onReorder={reorderTabs}
                />
              )}
              {editorTabs.length === 0 ? (
                <CodeView
                  projectId={projectId}
                  path={null}
                  reload={workspaceReload}
                  trunk={trunk}
                />
              ) : (
                editorTabs.map((tab) => (
                  // 每个标签的内容都挂着，只是不活动的 display:none：切走再切回来，
                  // 草稿、光标、Markdown 的编辑状态都还在。卸载会把这些一起丢掉。
                  <div
                    key={tab.key}
                    className={
                      tab.key === activeTabKey
                        ? 'flex min-h-0 flex-1 flex-col'
                        : 'hidden'
                    }
                  >
                    {tab.kind === 'diff' ? (
                      // 看"这一轮对它做了什么"，和文件标签共用同一排标签。
                      <ProjectDiffView
                        projectId={projectId}
                        commitSha={tab.commitSha ?? ''}
                        path={tab.path}
                      />
                    ) : (
                      <CodeView
                        projectId={projectId}
                        path={tab.path}
                        // 和左边那棵树吃同一个版本号：磁盘变了只有一个来源，分开接迟早会忘，
                        // 而漏掉的那边只是停在旧内容上，不报错。
                        reload={workspaceReload}
                        // 和左边那棵树看同一棵，见 trunk 那段。
                        trunk={trunk}
                        onDirtyChange={(dirty) => markTabDirty(tab.key, dirty)}
                      />
                    )}
                  </div>
                ))
              )}
            </>
          )}
        </main>

        {!rightCollapsed && (
          <Splitter
            label="拖动调整会话栏宽度"
            // 往右拖 = 会话栏变窄，所以把符号翻过来：面板那边只知道"往右是正数"。
            onResize={(delta) => panes.resize('right', -delta)}
            onReset={panes.reset}
          />
        )}

        {/* 右：会话。收起用 display:none 而不是卸载：聊天室和会话流在里面保持
            连接，展开后历史、草稿和未读都还在 */}
        <section
          className={`shrink-0 flex-col ${rightCollapsed ? 'hidden' : 'flex'}`}
          style={{ width: panes.right }}
        >
          <header className="pane-head gap-1 px-3">
            <TabButton active={tab === 'session'} onClick={() => openTab('session')}>
              {/* 名字前那个点是"谁"：全站那两个人的颜色，每处都在回答同一个问题 */}
              <span className={`mr-1.5 inline-block size-1.5 rounded-full align-middle ${personDot[mySlot]}`} />
              会话
            </TabButton>
            <TabButton active={tab === 'chat'} onClick={() => openTab('chat')}>
              聊天
              {/* 不在聊天标签时对方说的话，用一个点说出来。颜色用那个人的颜色：
                  全站"谁是谁"就是这一对，未读也是"他说话了" */}
              {chatUnread > 0 && (
                <span
                  role="img"
                  aria-label="有新消息"
                  className={`ml-1.5 inline-block size-1.5 rounded-full align-middle ${personDot[theirSlot]}`}
                />
              )}
            </TabButton>
            {/* 观战始终在，哪怕项目里只有你一个。让它忽隐忽现看起来像功能坏了，
                而它恰恰是这个产品的卖点：一直看得见，点进去说清"还没有人可看" */}
            <TabButton active={tab === 'watch'} onClick={() => openTab('watch')}>
              <span
                className={`mr-1.5 inline-block size-1.5 rounded-full align-middle ${
                  teammate ? personDot[theirSlot] : 'bg-loom-faint'
                }`}
              />
              观战
              {watchUnread && (
                <span
                  role="img"
                  aria-label="对方开始了新的会话"
                  className={`ml-1.5 inline-block size-1.5 rounded-full align-middle ${personDot[theirSlot]}`}
                />
              )}
            </TabButton>

            {/* 动作组统一贴右：ml-auto 落在包裹层上，不然几个按钮各带一个
                ml-auto，flex 会把空档摊到它们之间。收起键始终在最后 */}
            <div className="ml-auto flex items-center gap-1">
              {tab === 'session' && mine.length > 0 && (
                <>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    title="新建会话"
                    aria-label="新建会话"
                    className="text-muted-foreground"
                    onClick={startNewConversation}
                  >
                    <MessageSquarePlus className="size-4" />
                  </Button>
                  <SessionHistoryDialog
                    title="我的会话"
                    sessions={mine}
                    slots={slots}
                    selectedId={mySession}
                    onSelect={choose}
                    onDiscard={discardSession}
                    // 列表每 5 秒自刷，但打开时还是补一次：标题是服务端按第一句话算的，
                    // 刚说完的那句不该等下一 tick。
                    onOpen={() => all.reload()}
                  />
                </>
              )}
              {tab === 'watch' && theirs.length > 0 && (
                <SessionHistoryDialog
                  title={`${teammate?.displayName} 的会话`}
                  sessions={theirs}
                  slots={slots}
                  selectedId={theirSession}
                  onSelect={setTheirSession}
                  // 同上：观战那一栏读的是同一份列表。
                  onOpen={() => all.reload()}
                />
              )}
              <Button
                variant="ghost"
                size="icon-sm"
                title="收起边栏"
                aria-label="收起边栏"
                className="text-muted-foreground"
                onClick={() => setRightCollapsed(true)}
              >
                <PanelRightClose className="size-4" />
              </Button>
            </div>
          </header>

          <div className="min-h-0 flex-1">
            {/* 聊天室一直挂着，只是藏起来。卸载它 = 断开 WebSocket，于是停在别的
                标签时对方说的话要等切回来才拉历史，而人不知道什么时候该切回来。
                挂着之后连接不断，标签上的未读点才有东西可点 */}
            <div className={tab === 'chat' ? 'h-full' : 'hidden'}>
              <ChatPanel
                project={data}
                meId={me?.id}
                nameOf={nameOf}
                active={tab === 'chat'}
                onMessages={onChatMessages}
              />
            </div>
            {tab === 'session' && (
              // 输入框走 footer 交给流本身，不再当它的兄弟节点：两个兄弟各自撑高度，
              // 加起来必然超过容器，输入框会被顶出去。
              // key 里带 epoch：回滚之后整条流重建（对话被截断了，旧条目还在手上的
              // 话界面会显示一段已不存在的历史）。和左边那棵树用 key 重建是同一招。
              <SessionStreamView
                key={`${mySession}:${streamEpoch}`}
                sessionId={mySession}
                speaker="你"
                nameOf={nameOf}
                // 只有会话所有者能批挂起的调用（后端 requireDriver）；观战那处不传。
                canApprove
                // 流里提到的文件点一下在中栏打开：这一栏和中栏本来就是"它说它动了
                // 哪个文件"和"那个文件里写了什么"。
                onOpenFile={openFile}
                // "这一轮改了哪些文件"那一行点的是另一个东西：它这一轮改了什么。
                // 同一文件在两处的两种读法，见 ChangesRow。
                onOpenDiff={openDiff}
                // 接上的那根线：agent 往后端写文件，和左边那棵树之间本来什么都没有。
                // 观战那处刻意不接：对方的 agent 改的是他的树。
                onWorkspaceChanged={refreshWorkspace}
                // 把折出来的条目交给底部：回滚面板要的就是这条流，面板和上面那段
                // 对话读同一份，不可能对不上。
                footer={(items) =>
                  rewinding && mySession !== null ? (
                    <RewindPicker
                      sessionId={mySession}
                      items={items}
                      onClose={() => setRewinding(false)}
                      onRewound={(_session, said) => {
                        setRewinding(false)
                        // 退掉的那句话回到输入框：它正是"刚才那句"，而不是它之前的
                        // （见 Entry.said）。取不到时留空，不编一句。
                        setDraft(said ?? '')
                        // 两件事都要做：流重建（对话被截断）、树重拉（代码退回去）。
                        // 只做一件会留一半旧东西。
                        setStreamEpoch((n) => n + 1)
                        rereadWorkspace()
                      }}
                    />
                  ) : (
                  <Composer
                    project={data}
                    sessionId={mySession}
                    // 这条会话现在用哪个模型：从已拉回来的会话列表里取，不为它多发请求。
                    currentModel={mine.find((s) => s.id === mySession)?.model ?? null}
                    onCreated={(id) => {
                      choose(id)
                      all.reload()
                    }}
                    // 换完模型重拉列表：不拉的话别处（会话历史那列）还挂着旧的。
                    onModelChanged={() => all.reload()}
                    // 输入框为空时连按两下 Esc：回滚界面替掉输入框那一条。
                    // 顺手清草稿：那一开一关会让输入框重新挂载，而初始值只在挂载时读一次。
                    onRewind={() => {
                      setDraft('')
                      setRewinding(true)
                    }}
                    // 上一步回滚退回来的那句话，输回框里让它能改两个字再发。
                    initialText={draft}
                  />
                  )
                }
              />
            )}
            {/* 观战时说话的是他，前缀得是他的名字：写死成"你"会看见几句自己没说过
                的话挂在自己名下 */}
            {tab === 'watch' && (
              <SessionStreamView
                sessionId={theirSession}
                speaker={teammate?.displayName ?? '对方'}
                nameOf={nameOf}
                emptyHint={watchHint}
                // 不要把别人的文件路径接到"我的树"上：那个文件在对方的树里，还没
                // 合并过来；直接当文件标签打开会让中栏读我的工作区，然后报"不是
                // 一个普通文件"，一句和真实原因无关的话。想看这一轮改动走 onOpenDiff。
                onOpenFile={(path) =>
                  setNotice({
                    tone: 'plain',
                    text: `${path} 在${teammate?.displayName ?? '对方'}的树里，还没合并。看这一轮的改动请点「改了 N 个文件」`,
                  })
                }
                // 观战那栏也一样：对方改了什么，正是最想看的。
                onOpenDiff={openDiff}
              />
            )}
          </div>
        </section>

        {/* 收起后的窄边：只留一个展开入口，会话栏本身继续挂着 */}
        {rightCollapsed && (
          <aside className="flex w-9 shrink-0 flex-col border-l border-border/50">
            <header className="pane-head justify-center px-0">
              <Button
                variant="ghost"
                size="icon-sm"
                title="展开边栏"
                aria-label="展开边栏"
                className="text-muted-foreground"
                onClick={() => setRightCollapsed(false)}
              >
                <PanelRightOpen className="size-4" />
              </Button>
            </header>
          </aside>
        )}
      </div>

      {closingTabs !== null && (
        <ConfirmDialog
          title="有未保存的修改"
          description={
            closingTabs.kind === 'one'
              ? '关闭这个标签会丢掉还没保存的修改。'
              : closingTabs.kind === 'others'
                ? '关闭其他标签页会丢掉其中还没保存的修改。'
                : '关闭所有标签页会丢掉还没保存的修改。'
          }
          confirmLabel="仍然关闭"
          onConfirm={async () => {
            confirmClosingTabs()
            return null
          }}
          onClose={() => setClosingTabs(null)}
        />
      )}

      {/* 合并会动到共享主干（对方也看得到，而且会接着验证），先问一句。同步不动
          别人的东西，所以不拦，给它一个 title 说明就够。见
          docs/decisions/feature/2026-10-18-merge-asks-sync-does-not.md */}
      {confirmingMerge && mySession !== null && (
        <ConfirmDialog
          title="合进主干"
          description="把你在这条会话里的改动合进主干，对方也能看到。有冲突的话会停下来，在中间裁决。"
          confirmLabel="合并"
          confirmVariant="default"
          onConfirm={async () => {
            await act('MERGE', () => merge.intoMain(mySession), (status) => ({
              tone: 'plain',
              text: status === 'FAST_FORWARD' ? '合进主干了'
                : status === 'UP_TO_DATE' ? '已经是最新，没有要合的改动'
                  : '合进主干了（有一笔合并提交）',
            }))
            setConfirmingMerge(false)
            return null
          }}
          onClose={() => setConfirmingMerge(false)}
        />
      )}
    </div>
  )
}

function rememberKey(projectId: string) {
  return `codeloom.lastSession.${projectId}`
}

function remembered(projectId: string): string | null {
  return readStorage(rememberKey(projectId))
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      // 标签选中态只有文字颜色差别：不加底、不加下划线、不加粗。三栏里每处加粗和
      // 描边都在和内容抢注意力。
      className={`rounded px-2 py-1 text-sm transition-colors ${
        active ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'
      }`}
    >
      {children}
    </button>
  )
}

function Centered({ children, tone }: { children: React.ReactNode; tone?: 'error' }) {
  return (
    <div className="flex h-screen items-center justify-center">
      <p
        role={tone === 'error' ? 'alert' : undefined}
        className={tone === 'error' ? 'text-sm text-destructive' : 'text-sm text-muted-foreground'}
      >
        {children}
      </p>
    </div>
  )
}
