import { LogOut, MessageSquarePlus, Settings } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { ChatPanel } from '@/components/workspace/ChatPanel'
import { CodeView } from '@/components/workspace/CodeView'
import { Composer } from '@/components/workspace/Composer'
import { CollaboratorsDialog } from '@/components/workspace/CollaboratorsDialog'
import { ConflictResolver } from '@/components/workspace/ConflictResolver'
import { FileTree } from '@/components/workspace/FileTree'
import { RewindPicker } from '@/components/workspace/RewindPicker'
import { SessionHistoryDialog } from '@/components/workspace/SessionHistoryDialog'
import { SessionStreamView } from '@/components/workspace/SessionStreamView'
import { Splitter } from '@/components/workspace/Splitter'
import { Button } from '@/components/ui/button'
import { ApiError, merge, projects, sessions as sessionsApi, type Conflicts } from '@/lib/api'
import { personDot, slotsOf, type PersonSlot } from '@/lib/people'
import { useAsync } from '@/lib/useAsync'
import { usePaneWidths } from '@/lib/usePaneWidths'
import { useAuth } from '@/stores/auth'

const NO_CONFLICTS: Conflicts = { direction: null, conflicts: [] }

/**
 * 两次"重看磁盘"之间至少隔多久。
 *
 * <p>**这个数是在说"人眼能分辨多快"，不是在说性能**。agent 写文件是成簇的
 * （一次写好几个文件，或者一条命令动一片），一次改动拉一次的话，一棵展开了五层的树
 * 会被连着打十几遍 —— 而人看到的只是"文件唰地一下都出现了"，和一次没差别。
 * 太短（0）等于没合并；太长（几秒）就会觉得树"反应迟钝"。
 */
const WORKSPACE_RELOAD_MS = 400

/** 顶栏右侧那行小字。**没有弹出层、没有自动消失** —— 它只是把刚才那一下的结果说出来。 */
interface Notice {
  tone: 'plain' | 'good' | 'bad'
  text: string
}

/**
 * 工作区。**点进项目就是这里，没有中间那一层页面。**
 *
 * <h2>左中右三栏，各自回答一个问题</h2>
 * <ul>
 *   <li><b>左：我的代码现在是什么样</b> —— 文件树，点开看</li>
 *   <li><b>中：这个文件里写了什么</b></li>
 *   <li><b>右：我和他的 agent 分别干了什么</b></li>
 * </ul>
 *
 * <h2>视觉上它是**一整片**，不是三块面板</h2>
 * 三栏之间只有一条几乎看不见的分界线，没有底色差、没有圆角、没有阴影。
 * 那是有意的：一旦每栏各是一块"面板"，整个东西立刻变成仪表盘，
 * 而这是个**读代码和读对话**的地方 —— 越安静越好。
 *
 * <h2>会话不是在这里「新建」的</h2>
 * 会话要等右栏那个输入框发出第一句话才产生（那一步要带上模型配置）。
 * 已经有的话，右栏显示**你最后看的那一条**，历史在右上角那个图标里。
 */
export function WorkspacePage() {
  const { projectId = '' } = useParams()
  const me = useAuth((s) => s.user)
  const logout = useAuth((s) => s.logout)
  const navigate = useNavigate()

  const project = useAsync(() => projects.one(projectId), [projectId])
  const all = useAsync(() => sessionsApi.forProject(projectId), [projectId])

  // 三栏这一行的宽度，用来夹住左右两栏能拖多远（见 usePaneWidths）
  const row = useRef<HTMLDivElement>(null)
  const panes = usePaneWidths(row)

  const [selectedFile, setSelectedFile] = useState<string | null>(null)
  const [tab, setTab] = useState<'session' | 'chat' | 'watch'>('session')
  const [mySession, setMySession] = useState<string | null>(null)
  const [theirSession, setTheirSession] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)

  /**
   * 磁盘上又变了一版。**看着磁盘的两样东西都吃它**：左边那棵树、和中栏打开的那份文件。
   * 它们自己都看不见磁盘 —— 见 {@link refreshWorkspace}。
   */
  const [workspaceReload, setWorkspaceReload] = useState(0)
  const workspaceReloadTimer = useRef<number | undefined>(undefined)

  /**
   * 我的工作区在磁盘上变了，让看着磁盘的东西都重看一遍。**四个来源都走这里**：
   * agent 动文件（会话流推上来）、同步、合并、裁决冲突落盘。
   *
   * <h2>为什么合并一小段再拉</h2>
   * 见 {@link WORKSPACE_RELOAD_MS}。这里用的是 **trailing**（等安静下来才拉），
   * 不是 leading：leading 的话一串连续改动只有第一次会拉，
   * 而那些刚写出来的新文件要等到下次有人动才出现 —— 那正是这个 bug 原来的样子。
   *
   * <h2>为什么中栏也走这一根线</h2>
   * 因为"磁盘变了"只有一个来源。给中栏另接一根的话，迟早有一根会忘掉接 ——
   * 而漏掉的那一边不会报错，它只是**停在旧样子上**（中栏从前就是那样：
   * agent 改完文件，它一直显示改动前的内容，直到有人手动刷新页面）。
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
   * 左栏那棵树（和中栏那份文件）看的是**我的**还是**主干**。
   *
   * <p>状态在这一层，因为**两处必须看同一棵树**：树里点一个路径，中栏读的得是同一棵上的
   * 那个文件。各存各的话，切到主干之后点一个文件，中栏给你看的是自己那份 ——
   * 而两边的路径**长得一模一样**，看不出来。
   */
  const [trunk, setTrunk] = useState(false)

  /** 回滚界面开着吗。**状态在这一层，因为要换掉 footer** —— 见下面那段 */
  const [rewinding, setRewinding] = useState(false)

  /**
   * 回滚之后要塞回输入框的那句话。
   *
   * <p>回滚退掉的正是**你刚说的那一句**，而点回滚十有八九是想改两个字重说一遍 ——
   * 退完让人重新打一遍，这次回滚就白做了一半。
   *
   * <p>它只被读**一次**：{@code ComposerBox} 在挂载时拿它当初始值，之后那个框归它自己。
   * 所以**打开回滚时清掉它** —— 那一步正是让输入框重新挂载的那一步，
   * 不清的话，上一次回滚退回来的那句话会在下一次打开这个面板时又冒出来。
   */
  const [draft, setDraft] = useState('')

  /**
   * 回滚之后，会话流要从头**重拉**一遍。
   *
   * <p>回滚会把对话**截断**（这是它和"只退代码"的区别）。而前端手里那条流是折出来的，
   * 它不会因为来了几条新事件就自己变短 —— 旧的条目还在那儿，界面上会显示一段
   * **已经不存在的历史**。所以整条流重建一次。
   *
   * <p>用 key 重建（和左边那棵树换项目、换主干是同一招），不给它加一个"重置"信号：
   * 信号要一路传到那个 hook 里，而中间每一层都得记得转发它。
   */
  const [streamEpoch, setStreamEpoch] = useState(0)

  // 冲突清单：进页面探一次，之后每次同步/合并之后再探。
  // **它没有冲突时是空信封而不是 404** —— 所以"没有冲突"是一个正常结果，不是错误
  const pending = useAsync(
    () => (mySession ? merge.conflicts(mySession) : Promise.resolve(NO_CONFLICTS)),
    [mySession],
  )

  async function signOut() {
    await logout()
    navigate('/login', { replace: true })
  }

  /**
   * 磁盘变过之后，把"看着磁盘的那两样"一起重来：左边那棵树，和冲突清单。
   *
   * <p>它们本来就是同一份事实的两面（这棵树里现在有什么 / 其中哪几个撞上了），
   * 一起走过的路径（同步、合并、裁决）分头重来就一定会漏掉一边 ——
   * 而漏掉的那一边不会报错，它只是**停在旧样子上**。
   */
  function rereadWorkspace() {
    pending.reload()
    refreshWorkspace()
  }

  /**
   * 同步或合并。两者的失败方式一样（409），所以收在一处处理。
   *
   * <p>409 在这里有**三种**含义，而且处置方式完全不同 —— 那正是后端把错误原因
   * 写进响应体的原因（见 `ApiExceptionHandler` 的类注释）：
   * <ul>
   *   <li><b>有冲突</b> → 去中栏裁决（重新拉一次冲突清单，界面自己会切过去）</li>
   *   <li><b>落后于主干</b> → 告诉人"先同步"，并给出那个按钮</li>
   *   <li><b>会话正在跑</b> → 等它跑完</li>
   * </ul>
   */
  async function act(what: () => Promise<unknown>, done: (status: string) => Notice) {
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
          setNotice({ tone: 'plain', text: `${conflicting.length} 个文件冲突了，在中间裁决` })
          // 撞在冲突上时，磁盘也已经变了 —— `git merge --no-commit` 把冲突标记
          // 写进了文件里。中栏之所以不跟着变，是因为它只认"人选定了一份内容"；
          // 而树是"这里有什么"，它必须跟上
          rereadWorkspace()
        } else if (typeof behind === 'number') {
          setNotice({ tone: 'plain', text: `你落后主干 ${behind} 个提交，先同步一下` })
        } else {
          setNotice({ tone: 'plain', text: e.message })
        }
      } else {
        setNotice({ tone: 'bad', text: e instanceof ApiError ? e.message : '连不上服务器' })
      }
    } finally {
      setBusy(false)
    }
  }

  const members = project.data?.members ?? []

  /**
   * 按 id 查一个人叫什么。事件流里带的都是 **用户 id**（用户名会变，唯一只说"此刻没重名"），
   * 名字在这一层查 —— 改过名之后，历史那几行显示的也跟着变。
   *
   * <p>**它必须是个稳定的函数**：这个值一路传到 {@code memo} 过的条目上，
   * 每次渲染换一个新的，等于让每一条都重渲染 —— 而条目里有 markdown 和 diff，
   * 流式输出时那是每帧一次。所以把它挂在成员表上（成员表从 useAsync 来，是稳定的）。
   */
  const nameOf = useMemo(() => {
    const byId = new Map(members.map((member) => [member.id, member.displayName]))
    return (id: string) => byId.get(id) ?? null
  }, [members])
  const mine = all.data?.filter((s) => s.ownerId === me?.id) ?? []
  const teammate = members.find((m) => m.id !== me?.id)
  const theirs = all.data?.filter((s) => s.ownerId === teammate?.id) ?? []

  // 默认选中哪条：**记住上次看的那条**，记不住就退回列表第一条。
  //
  // 为什么用 localStorage 而不是"最近创建的那条"：会话表里**没有创建时间**
  //（那张表只管对话和模型配置）。为一个"默认选谁"去加一列，代价大于收益；
  // 而 localStorage 丢了（换浏览器、清缓存）只是退回第一条 —— 那是合理的退化。
  /**
   * 用户刚点过"开一条新对话"。
   *
   * <p>**必须有这样一个标记**：下面那个自动选中依赖的 {@code mine} 是每次渲染新建的数组，
   * 所以它其实每渲染都跑一遍 —— 光把 {@code mySession} 置空，它下一次就替你把
   * {@code mine[0]} 选回来了。{@link #choose} 会把它清掉（选了一条就不再算"要新的"）。
   */
  const wantsFresh = useRef(false)

  useEffect(() => {
    if (mine.length === 0 || wantsFresh.current) return
    setMySession((current) => current ?? remembered(projectId) ?? mine[0].id)
  }, [mine, projectId])

  useEffect(() => {
    if (theirs.length > 0) {
      setTheirSession((current) => current ?? theirs[0].id)
    }
  }, [theirs])

  if (project.loading) return <Centered>正在打开…</Centered>
  if (project.error) return <Centered tone="error">{project.error}</Centered>
  if (!project.data) return null

  const data = project.data
  const slots = slotsOf(data.members.map((m) => m.id))
  const mySlot: PersonSlot = slots.get(me?.id ?? '') ?? 'a'
  const theirSlot: PersonSlot = slots.get(teammate?.id ?? '') ?? 'b'

  function choose(sessionId: string) {
    // 选了一条（或者刚由第一句话建出来一条）就不再算"要开新的"
    wantsFresh.current = false
    setMySession(sessionId)
    localStorage.setItem(rememberKey(projectId), sessionId)
  }

  /**
   * 开一条**新对话**：把当前这条放掉，右栏回到"还没有会话"，下一句话就会开一条新的。
   *
   * <p>**不在这里建会话** —— 会话要等右栏真发出第一句话才产生（见类注释）。先建一条空的
   * 会留下"建了又没用过"的垃圾，还会把"记住上次看的那条"顶掉。
   */
  function startNewConversation() {
    wantsFresh.current = true
    setMySession(null)
    // 连记住的那条一起清掉：不清的话刷新之后它又被捡回来（见上面那个 effect）
    try {
      localStorage.removeItem(rememberKey(projectId))
    } catch {
      // 隐私模式下 localStorage 会抛。那不是错误，只是"记不住"而已（同 remembered）
    }
  }

  return (
    // overflow-hidden 不是装饰：这个 div 是**占满视口**的，它自己绝不该滚。
    // 少了它，任何一处横向溢出都会带来一个横向滚动条，而 **100vh 是包含滚动条高度的** ——
    // 于是 `h-screen` 比可用高度高出一条滚动条，整页跟着能纵向滚，
    // 滚下去三栏全被推出去，底下只剩背景色。滚动该发生的地方是里面那几个
    // `overflow-auto`，不是这里
    <div className="flex h-screen flex-col overflow-hidden">
      {/* 顶栏。**这是全站唯一一条真的在分隔东西的线** —— 它下面是三栏各自的滚动区，
          （AppShell 那条"没有滚动内容要分"的注释在这里不成立） */}
      <header className="relative flex h-12 shrink-0 items-center gap-3 border-b border-border px-4">
        <Link to="/" className="text-sm text-muted-foreground hover:text-foreground">
          codeloom
        </Link>
        <span className="text-muted-foreground/40">/</span>
        {/* 项目名是这一页的主语，所以它比周围重一档 —— 顶栏里**只有这一处**加重 */}
        <h1 className="truncate text-sm font-medium">{data.name}</h1>

        {/* 「看哪棵树」的切换不在这儿 —— 它在左边那棵树的头部，和刷新、折叠排在一起。
            那里是它影响的东西所在，而且它答的问题（"我在哪条线上"）跟着那棵树一起看
            才顺；摆在顶栏会让人以为它管的是整个页面 */}

        {/* 反馈落在**顶栏正中间那块空地方**。
            放进右边那排按钮里的话，它一出现就会把同步/合并/协作/设置/登出整体挤开 ——
            一句话让一整排控件跳一下，是这套界面里最不该有的动静。
            它同时是 pointer-events-none：万一窄屏上和按钮叠上了，也不该挡住人家 */}
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

        {/* 顶栏只放"偶尔要做一次"的事。**不放任何常用的东西** ——
            常用的东西都在三栏里，顶栏一旦变热闹，三栏就不安静了。
            同步和合并正好属于这一档：甲方做完一段、说一句"合一下"，那才是它们的时机 */}
        <div className="ml-auto flex items-center gap-0.5">
          {mySession && (
            <>
              <Button
                variant="ghost"
                size="sm"
                disabled={busy}
                className="text-muted-foreground"
                onClick={() =>
                  void act(() => merge.sync(mySession), (status) => ({
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
                disabled={busy}
                onClick={() =>
                  void act(() => merge.intoMain(mySession), (status) => ({
                    tone: 'plain',
                    text: status === 'FAST_FORWARD' ? '合进主干了' : '合进主干了（有一笔合并提交）',
                  }))
                }
              >
                合并
              </Button>
            </>
          )}

          <CollaboratorsDialog project={data} />
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

      {/* 三栏这一行。左右两栏的宽度是**拖出来的**（见 usePaneWidths），
          所以它们的宽度走 inline style，而中间那一片靠 flex-1 吃掉剩下的 */}
      <div ref={row} className="flex min-h-0 flex-1">
        {/* 左：树。它是一条导航，宽度由人拖出来 —— 默认那 224px 只是起点。
            这一栏的**头和身子整块归 FileTree**：头上有两个作用在整棵树上的动作
            （刷新、全部折叠），把状态留在树里比从这一层往下传干净 */}
        <aside className="flex min-h-0 shrink-0 flex-col" style={{ width: panes.left }}>
          {/* key 挂在「项目 + 看哪棵树」上：换项目、或者切到主干，都整棵树重建。
              展开的是哪些路径、每个目录拉回来的是什么，全是**另一棵树**上的 ——
              只重拉根目录的话，展开过的那些层级会留着上一个项目的旧内容，
              而两边的路径长得一模一样，看不出来。

              「切到主干」和「换项目」在这里是同一件事，所以用同一个机制（key），
              而不是再写一套"trunk 变了要清哪些 state" —— 那种清理迟早会漏掉一处 */}
          <FileTree
            key={`${projectId}:${trunk}`}
            projectId={projectId}
            rootName={data.rootName}
            selected={selectedFile}
            onSelect={setSelectedFile}
            trunk={trunk}
            onTrunk={setTrunk}

            // agent 动过文件、或者刚同步/合并完，这个数就涨一次
            reload={workspaceReload}
          />
        </aside>

        <Splitter
          label="拖动调整项目栏宽度"
          onResize={(delta) => panes.resize('left', delta)}
          onReset={panes.reset}
        />

        {/* 中：代码。左右两条分界线现在由两个把手画（它们同时是那条线，见 Splitter 的注释）。
            有冲突要裁决时它切成两份并排 —— 那正是它够宽的理由。

            **`min-h-0` 是这一条里最要紧的那个类**：这是一栏内容最高的地方（一份长 README 能有
            好几屏），少了它，flex 子项的自动最小高度会把这一栏**撑到内容那么高** ——
            然后一路把三栏和 `h-screen` 顶破，整页跟着能滚，滚下去整个工作区都被推出去。
            有了它，高度才停在视口内，多出来的那部分由里面的 `overflow-auto` 接管
            （也就是"只有这一栏自己滚"） */}
        <main className="flex min-h-0 min-w-0 flex-1 flex-col">
          {mySession && (pending.data?.conflicts.length ?? 0) > 0 ? (
            <ConflictResolver
              sessionId={mySession}
              conflicts={pending.data as Conflicts}
              onResolved={(outcome) => {
                // 裁决就是把冲突标记从文件里抹掉并落盘 —— 磁盘变了
                rereadWorkspace()
                const left = outcome.remainingConflicts.length
                if (left > 0) {
                  setNotice({ tone: 'plain', text: `还有 ${left} 个文件要裁决` })
                  return
                }
                // ★ 裁决完了**不等于合完了**。撞在同步那一步的冲突，收尾的只是同步 ——
                //   主干还停在原处，要再点一次合并才落地。这句话必须说，
                //   否则用户会以为"冲突没了"就结束了
                setNotice(
                  pending.data?.direction === 'INTO_SESSION'
                    ? { tone: 'plain', text: '冲突处理完了。主干还没动，再点一次合并' }
                    : { tone: 'good', text: '冲突处理完了，已经合进主干' },
                )
              }}
              onAbort={() =>
                void act(() => merge.abort(mySession), () => ({
                  tone: 'plain',
                  text: '放弃这次合并了，工作区回到合并前',
                }))
              }
            />
          ) : (
            <CodeView
              projectId={projectId}
              path={selectedFile}
              // 和左边那棵树吃的是**同一个**版本号：磁盘变了这件事只有一个来源，
              // 分开接的话迟早有一根会忘掉 —— 而漏掉的那一边只是停在旧内容上，不报错
              reload={workspaceReload}
              // 和左边那棵树看**同一棵** —— 见 trunk 那段
              trunk={trunk}
            />
          )}
        </main>

        <Splitter
          label="拖动调整会话栏宽度"
          // 往右拖 = 会话栏变窄，所以这里把符号翻过来 —— 面板那边只知道"往右是正数"
          onResize={(delta) => panes.resize('right', -delta)}
          onReset={panes.reset}
        />

        {/* 右：会话 */}
        <section className="flex shrink-0 flex-col" style={{ width: panes.right }}>
          <header className="pane-head gap-1 px-3">
            <TabButton active={tab === 'session'} onClick={() => setTab('session')}>
              {/* 名字前那个点是"谁"—— 全站那两个人的颜色，每一处都在回答同一个问题 */}
              <span className={`mr-1.5 inline-block size-1.5 rounded-full align-middle ${personDot[mySlot]}`} />
              会话
            </TabButton>
            <TabButton active={tab === 'chat'} onClick={() => setTab('chat')}>
              聊天
            </TabButton>
            {/* **观战始终在**，哪怕项目里现在只有你一个。
                让它忽隐忽现（只有存在队友时才出现）看起来像"这个功能坏了"，
                而它恰恰是这个产品的卖点 —— 它该一直看得见，点进去说清"还没有人可看"。 */}
            <TabButton active={tab === 'watch'} onClick={() => setTab('watch')}>
              <span
                className={`mr-1.5 inline-block size-1.5 rounded-full align-middle ${
                  teammate ? personDot[theirSlot] : 'bg-loom-faint'
                }`}
              />
              观战
            </TabButton>

            {tab === 'session' && mine.length > 0 && (
              // 「开新对话」在历史左边，两个一起贴右：**ml-auto 落在这个包裹层上**，
              // 不然两个按钮各带一个 ml-auto，flex 会把空档摊到它们俩之间
              <div className="ml-auto flex items-center gap-1">
                <Button
                  variant="ghost"
                  size="icon-sm"
                  title="开一条新对话"
                  aria-label="开一条新对话"
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
                  // 列表只在打开项目时拉过一次（见上面那个 useAsync）—— 标题是服务端算的，
                  // 所以要打开时补一次，否则刚说的第一句话要刷新页面才看得见
                  onOpen={() => all.reload()}
                />
              </div>
            )}
            {tab === 'watch' && theirs.length > 0 && (
              <div className="ml-auto flex items-center gap-1">
                <SessionHistoryDialog
                  title={`${teammate?.displayName} 的会话`}
                  sessions={theirs}
                  slots={slots}
                  selectedId={theirSession}
                  onSelect={setTheirSession}
                  // 同上：观战那一栏读的是同一份列表
                  onOpen={() => all.reload()}
                />
              </div>
            )}
          </header>

          <div className="min-h-0 flex-1">
            {tab === 'chat' && (
              <ChatPanel
                project={data}
                meId={me?.id}





              />
            )}
            {tab === 'session' && (
              // 输入框走 footer 交给流本身，**不再当它的兄弟节点** ——
              // 两个兄弟各自撑高度的话，加起来必然超过容器，输入框就被顶出去看不见了
              // key 里带上 epoch：回滚之后整条流重建（对话被截断了，旧条目还在手上
              // 的话界面会显示一段**已经不存在的历史**）。和左边那棵树用 key 重建是同一招
              <SessionStreamView
                key={`${mySession}:${streamEpoch}`}
                sessionId={mySession}
                speaker="你"
                nameOf={nameOf}
                // 只有会话所有者能批挂起的调用（后端 requireDriver）—— 观战那处不传
                canApprove
                // 流里提到的文件点一下就在中栏打开它 —— 这一栏和中栏本来就是
                // "它说它动了哪个文件"和"那个文件里写了什么"的关系
                onOpenFile={setSelectedFile}
                // ★ 接上的那根线：agent 往后端写文件，和左边那棵树之间本来什么都没有。
                //   观战那处**刻意不接** —— 对方的 agent 改的是他的树
                onWorkspaceChanged={refreshWorkspace}
                // 把折出来的条目交给底部：回滚面板要的就是这条流（见它自己的注释）——
                // 面板和上面那段对话读同一份，不可能对不上
                footer={(items) =>
                  rewinding && mySession !== null ? (
                    <RewindPicker
                      sessionId={mySession}
                      items={items}
                      onClose={() => setRewinding(false)}
                      onRewound={(_session, said) => {
                        setRewinding(false)
                        // 退掉的那句话回到输入框里 —— 它正是"刚才那句"，而不是它之前的
                        // （见 Entry.said）。取不到时留空，不编一句出来
                        setDraft(said ?? '')
                        // 两件事都要做：流重建（对话被截断了）、树重拉（代码退回去了）。
                        // 只做一件的话，屏幕上会留下一半旧的东西
                        setStreamEpoch((n) => n + 1)
                        rereadWorkspace()
                      }}
                    />
                  ) : (
                  <Composer
                    project={data}
                    sessionId={mySession}
                    // 这条会话现在用哪个模型 —— 从**已经拉回来的**会话列表里取，
                    // 不为它多发一个请求（`SessionView` 里本来就带着）
                    currentModel={mine.find((s) => s.id === mySession)?.model ?? null}
                    onCreated={(id) => {
                      choose(id)
                      all.reload()
                    }}
                    // 换完模型重新拉一遍列表：不拉的话别处（会话历史那一列）还挂着旧的
                    onModelChanged={() => all.reload()}
                    // 输入框为空时连按两下 Esc —— 回滚界面**替掉**输入框那一条。
                    // 顺手清掉草稿：那一开一关会让输入框重新挂载，而初始值只在挂载时读一次
                    onRewind={() => {
                      setDraft('')
                      setRewinding(true)
                    }}
                    // 上一步回滚退回来的那句话 —— 输回框里让它能改两个字再发
                    initialText={draft}
                  />
                  )
                }
              />
            )}
            {/* 观战时说话的是**他**，所以那行前缀得是他的名字 —— 写死成"你"的话，
                你会看见几句自己从没说过的话挂在自己名下 */}
            {tab === 'watch' && (
              <SessionStreamView
                sessionId={theirSession}
                speaker={teammate?.displayName ?? '对方'}
                nameOf={nameOf}
                onOpenFile={setSelectedFile}
                // 观战这栏**不给引用入口**：引用是"把我自己 agent 的某一步交接出去"，
                // 而这一栏看到的是**对方的** agent。要在这一栏引用的话，
                // 语义变成"引用别人的东西再推给别人的 agent" —— 那是另一回事
              />
            )}
          </div>
        </section>
      </div>
    </div>
  )
}

function rememberKey(projectId: string) {
  return `codeloom.lastSession.${projectId}`
}

function remembered(projectId: string): string | null {
  try {
    return localStorage.getItem(rememberKey(projectId))
  } catch {
    // 隐私模式下 localStorage 会抛。那不是错误，只是"记不住"而已
    return null
  }
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
      // 标签的选中态只有文字颜色的差别 —— 不加底、不加下划线、不加粗。
      // 三栏里每一处加粗和描边都在和内容抢注意力
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
