import {
  ChevronRight,
  ChevronsDownUp,
  Download,
  File as FileIcon,
  Folder,
  FolderTree,
  GitBranch,
  RotateCw,
} from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { ContextMenu, type MenuAction } from '@/components/workspace/ContextMenu'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError, files, projectArchiveUrl, type FileEntry } from '@/lib/api'

/**
 * 工作区左栏：项目树。
 *
 * <h2>一层一层拉，不递归</h2>
 * 后端只列**一层**（`GET /files?path=`），所以这里也是点开一层拉一层。
 * 一次性把整棵树拉下来在一个大仓库上要几秒，而**没点开的那些层永远是零成本** ——
 * 更要紧的是，你不会为了看一眼 `src/` 而读进 `node_modules/`。
 *
 * <h2>展开状态放在这一层，不放在每个节点上</h2>
 * 一开始它是每个节点自己记的，理由是"展开就是拉、折叠就是留着，没有状态要推理"。
 * 那个理由在**没有整树动作**的时候成立 —— 而头上那两个按钮（刷新、全部折叠）
 * 恰恰是整树动作：它们要一次作用到所有已经展开的目录上，节点自己记的话够不着。
 *
 * <h2>右键改的是**你自己那棵树**</h2>
 * 后端的写接口上刻意没有 `owner`（读的有，观战要看得见对方的代码）。
 * 所以这里也不传 —— 对方的树是唯一一块只有他能改的地方。
 *
 * <h2>刷新有两个来源</h2>
 * 一个是**这台组件自己**的：右键新建、改名、删除之后（那些动作就发生在这里，
 * 它当然知道自己刚改了什么）。另一个是**外面推进来的** {@link FileTree} 的 `reload`：
 * agent 在会话里改了工作区，而那件事发生在后端和磁盘上，这棵树看不见。
 * 两个来源合成一个数往下传（见下面 `reload` 那两行）。
 */
export function FileTree({
  projectId,
  rootName,
  selected,
  onSelect,
  owner,
  trunk = false,
  onTrunk,

  reload: externalReload = 0,
}: {
  projectId: string
  /** 看**主干**而不是自己的树。中栏跟着它走 —— 两处必须看同一棵树 */
  onTrunk: (trunk: boolean) => void
  trunk?: boolean

  /** 项目目录叫什么 —— 树的第一行。见 {@link PROJECT_ROOT} */
  rootName: string
  /** 传 null 是"选中的那个文件没了"（刚被删掉），中栏要清空 */
  onSelect: (path: string | null) => void
  selected: string | null
  owner?: string
  /**
   * 外面推来的"重新拉一遍"。**每变一次拉一次**（数本身没有含义）。
   *
   * <p>只增不减，而且是外部状态 —— 这里**不把它复制进自己的 state**：
   * 复制就得写一个"prop 变了同步过去"的 effect，而那种 effect 一次同步晚了
   * 就会让这次刷新凭空消失。
   */
  reload?: number
}) {
  // 项目那一行**默认展开**：它是"这是项目"，不是"这里有个能收起来的东西"
  const [open, setOpen] = useState<ReadonlySet<string>>(() => new Set([PROJECT_ROOT]))
  /** 这台组件自己触发的刷新次数。见上面"刷新有两个来源" */
  const [ownReload, setOwnReload] = useState(0)
  const [menu, setMenu] = useState<MenuTarget | null>(null)
  const [naming, setNaming] = useState<NameTask | null>(null)
  const [removing, setRemoving] = useState<string | null>(null)

  // 两个来源**加起来**，而不是取较大者：两者各自只增不减，而每一层只在乎
  // "这个数变了没有" —— 取较大者会把其中一个来源的连续两次刷新压成一次。
  // 数本身没有含义，加法的结果也没有
  const reload = ownReload + externalReload
  const refresh = () => setOwnReload((n) => n + 1)

  function toggle(path: string) {
    setOpen((current) => {
      const next = new Set(current)
      if (next.has(path)) {
        next.delete(path)
      } else {
        next.add(path)
      }
      return next
    })
  }

  /** 一个路径没了（删掉或改名）：它自己、它的子孙，都从展开集合里划掉 */
  function forget(path: string) {
    setOpen((current) =>
      new Set([...current].filter((each) => each !== path && !each.startsWith(`${path}/`))),
    )
  }

  /**
   * 右键点上该给哪几项。
   *
   * <p>**右键空白处只给"新建"**：那儿没有东西可以改名，也没有东西可以删。
   * 给一个点了没反应的"重命名"比不给更糟。
   */
  function actionsFor(target: MenuTarget): MenuAction[] {
    const inDirectory = target.path === '' || target.directory
    const parent = inDirectory ? target.path : parentOf(target.path)

    const actions: MenuAction[] = []
    if (inDirectory) {
      actions.push(
        {
          label: '新建文件',
          onSelect: () => setNaming({ parent, directory: false, from: null, initial: '' }),
        },
        {
          label: '新建目录',
          onSelect: () => setNaming({ parent, directory: true, from: null, initial: '' }),
        },
      )
    }
    if (target.path !== '') {
      actions.push(
        {
          label: '重命名',
          divided: actions.length > 0,
          onSelect: () =>
            setNaming({
              parent,
              directory: target.directory,
              from: target.path,
              initial: nameOf(target.path),
            }),
        },
        { label: '删除', danger: true, onSelect: () => setRemoving(target.path) },
      )
    }
    return actions
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="pane-head px-3">
        <h2 className="text-xs text-muted-foreground">项目</h2>

        {/* 三个动作都放在这里，是因为它们作用在**整棵树**上，而没有别的地方放得下：
            一条导航上没有"当前项"可以挂右键菜单 */}
        <div className="ml-auto flex items-center gap-0.5">
          {/*
            看**哪棵树**：我的（自己改到哪儿了）还是主干（那条共享的线）。

            <p>图标本身分两态（文件夹 / 分支），所以**当前在哪一棵扫一眼就知道** ——
            这一点不能省：两边路径长得一模一样，看错一眼，你以为在看自己的改动，
            其实在看主干。而 hover 才知道的状态在这件事上是不够的
          */}
          <IconAction
            label={trunk ? '现在看的是主干，点一下切回我的' : '现在看的是我的，点一下看主干'}
            onClick={() => onTrunk(!trunk)}
          >
            {trunk ? <GitBranch /> : <FolderTree />}
          </IconAction>
          <IconAction label="刷新" onClick={refresh}>
            <RotateCw />
          </IconAction>
          <IconAction label="全部折叠" onClick={() => setOpen(new Set())}>
            <ChevronsDownUp />
          </IconAction>
          {/*
            下载放最后：前面三个都是"看这棵树"的开关，而它作用在**整个项目**上。
            它下的是**主干**那个版本（不是你现在看的这棵树）—— 这一点必须写进 label：
            按钮长在树的头部，不说清就会被当成"下载我正在看的这份"
          */}
          <IconAction
            label="下载这个项目（主干，zip）"
            onClick={() => {
              // 顶层导航触发下载：响应带 attachment，所以页面不会跳走
              window.location.href = projectArchiveUrl(projectId)
            }}
          >
            <Download />
          </IconAction>
        </div>
      </div>

      <div
        className="min-h-0 flex-1 overflow-auto pb-4"
        // 空白处右键 = 对着工作区根目录右键（target 是容器自己，不是某一行）
        onContextMenu={(event) => {
          if (event.target !== event.currentTarget) {
            return
          }
          event.preventDefault()
          setMenu({ x: event.clientX, y: event.clientY, path: '', directory: true })
        }}
      >
        {/* 下面那一大片是"点得动的空白"，让根目录的右键有个地方落 */}
        <div className="min-h-full py-2">
          <ProjectRow
            name={rootName}
            open={open.has(PROJECT_ROOT)}
            onToggle={() => toggle(PROJECT_ROOT)}
            onMenu={setMenu}
          />
          {open.has(PROJECT_ROOT) && (
            <Level
              projectId={projectId}
              path=""
              // 缩进从 1 起：**项目是第 0 层**，文件在它里面
              depth={1}
              selected={selected}
              onSelect={onSelect}
              owner={owner}
              trunk={trunk}
              open={open}
              onToggle={toggle}
              reload={reload}
              onMenu={setMenu}
            />
          )}
        </div>
      </div>

      {menu && (
        <ContextMenu
          x={menu.x}
          y={menu.y}
          label="文件操作"
          actions={actionsFor(menu)}
          onClose={() => setMenu(null)}
        />
      )}

      {naming && (
        <NameDialog
          task={naming}
          onClose={() => setNaming(null)}
          onSubmit={async (name) => {
            const to = naming.parent ? `${naming.parent}/${name}` : name
            try {
              if (naming.from !== null) {
                // 名字没改就别发请求：后端会说"那里已经有一个同名的了"（那个同名的就是它自己）
                if (to !== naming.from) {
                  await files.move(projectId, naming.from, to)
                  forget(naming.from)
                }
              } else {
                await files.create(projectId, to, naming.directory)
              }
              setNaming(null)
              refresh()
              return null
            } catch (e) {
              return e instanceof ApiError ? e.message : '连不上服务器'
            }
          }}
        />
      )}

      {removing !== null && (
        // 删一个文件也要问一句，因为工作区里**没有回收站**：已经提交过的还能靠回滚找回来，
        // 而还没提交的那些 —— 手工刚建的、agent 这一轮刚写的 —— 删掉就真的没有了。
        // 目录这里刻意不说"里面有 N 个文件"：那要额外问一次后端，
        // 而且它会让人把注意力放在"几个"上，而不是"要不要"
        <ConfirmDialog
          title={`删除「${nameOf(removing)}」`}
          description="目录的话，里面的东西会一起没。还没提交过的部分找不回来。"
          confirmLabel="删除"
          onClose={() => setRemoving(null)}
          onConfirm={async () => {
            try {
              await files.remove(projectId, removing)
              // 选中和展开的东西可能就在被删的那一棵里 —— 不清的话中栏会停在一个
              // 已经不存在的文件上，而它显示的是"读不到这个文件"
              if (selected === removing || selected?.startsWith(`${removing}/`)) {
                onSelect(null)
              }
              forget(removing)
              setRemoving(null)
              refresh()
              return null
            } catch (e) {
              return e instanceof ApiError ? e.message : '连不上服务器'
            }
          }}
        />
      )}
    </div>
  )
}

/** 缩进一格多少像素。**和下面 paddingLeft 用的是同一个数** —— 参考线要对齐它。 */
const INDENT = 12
/** 一行的内容从哪儿开始（也就是缩进 0 那一层的位置）。 */
const ROW_PAD = 8

/**
 * 项目那一行在 {@code open} 里用的键。
 *
 * <p>它同时是**项目根的路径**（{@code ""}）—— 而根不是任何一条 {@code FileEntry}，
 * 所以这个键不会和某个目录撞上。展开状态复用同一套机制，不另起一份。
 */
const PROJECT_ROOT = ''

/**
 * 树的第一行：**项目本身**。
 *
 * <h2>为什么文件上面非得有这一行</h2>
 * 因为**先有项目，才有文件**。一棵从文件开始的树，看的人不知道这些文件属于哪儿 ——
 * 而"属于哪儿"在两个人协作的时候是要紧的：下一次打开，你未必记得这个工作区
 * 是哪个项目。这一条是照 IDEA 学的，它的项目树第一行永远是项目节点。
 *
 * <h2>它长得和目录行一模一样</h2>
 * 因为它**就是**一个目录，而且是这一层唯一的目录。给它加粗、加底色、
 * 换个颜色，都是在说"这个和别的目录不一样" —— 而它唯一的特别之处是"它在最上面"，
 * 而那件事位置已经说清楚了。
 *
 * <p>它的右键菜单就是根目录的菜单（新建文件 / 新建目录）——
 * 和点在下面那片空白上是同一个目标。
 */
function ProjectRow({
  name,
  open,
  onToggle,
  onMenu,
}: {
  name: string
  open: boolean
  onToggle: () => void
  onMenu: (target: MenuTarget) => void
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      onContextMenu={(event) => {
        event.preventDefault()
        onMenu({ x: event.clientX, y: event.clientY, path: PROJECT_ROOT, directory: true })
      }}
      className="relative flex w-full items-center gap-1.5 py-1 pr-2 text-left text-sm text-foreground hover:bg-accent"
      style={{ paddingLeft: ROW_PAD }}
    >
      <ChevronRight
        className={`size-3.5 shrink-0 text-loom-faint transition-transform ${open ? 'rotate-90' : ''}`}
      />
      <Folder className="size-3.5 shrink-0 text-loom-folder" />
      <span className="truncate">{name}</span>
    </button>
  )
}

/** 右键点在哪儿。 */
interface MenuTarget {
  x: number
  y: number
  /** 空字符串 = 工作区根目录 */
  path: string
  directory: boolean
}

/** 正在命名的那一件事：新建，或者改名。 */
interface NameTask {
  /** 放进哪个目录。改名时不看它 */
  parent: string
  /** 建的是不是目录。改名时不看它 */
  directory: boolean
  /** 改的是哪一个；新建时是 null */
  from: string | null
  initial: string
}

function parentOf(path: string): string {
  const cut = path.lastIndexOf('/')
  return cut < 0 ? '' : path.slice(0, cut)
}

function nameOf(path: string): string {
  return path.slice(path.lastIndexOf('/') + 1)
}

/** 一层的加载状态：**加载中、出错、空**是三件不同的事，不能都渲染成空白。 */
function Level({
  projectId,
  path,
  depth,
  selected,
  onSelect,
  owner,
  trunk,
  open,
  onToggle,
  reload,
  onMenu,
}: {
  projectId: string
  path: string
  depth: number
  selected: string | null
  onSelect: (path: string | null) => void
  owner?: string
  /** 看主干 —— 和 owner 互斥 */
  trunk?: boolean
  open: ReadonlySet<string>
  onToggle: (path: string) => void
  reload: number
  onMenu: (target: MenuTarget) => void
}) {
  const [entries, setEntries] = useState<FileEntry[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    // **不在这里把 entries 清成 null**：刷新时清掉的话整棵树会闪一下"正在读取"，
    // 而拉回来的东西多半和原来一样。让旧内容留到新内容到位为止
    files
      .list(projectId, path, owner, trunk)
      .then((list) => {
        if (!cancelled) {
          setEntries(list)
          setError(null)
        }
      })
      .catch(() => {
        if (!cancelled) setError('读不到这个目录')
      })
    return () => {
      cancelled = true
    }
  }, [projectId, path, owner, trunk, reload])

  if (error) {
    return (
      <Plain depth={depth} muted>
        {error}
      </Plain>
    )
  }
  if (entries === null) {
    return (
      <Plain depth={depth} muted>
        正在读取…
      </Plain>
    )
  }
  if (entries.length === 0) {
    // 空目录在树里显示出来而不是藏掉：藏掉的话，一个刚建的空项目
    // 看起来和"加载失败"一模一样
    return (
      <Plain depth={depth} muted>
        （空）
      </Plain>
    )
  }

  return (
    <>
      {entries.map((entry) => (
        <Node
          key={entry.path}
          projectId={projectId}
          entry={entry}
          depth={depth}
          selected={selected}
          onSelect={onSelect}
          owner={owner}
              trunk={trunk}
          open={open}
          onToggle={onToggle}
          reload={reload}
          onMenu={onMenu}
        />
      ))}
    </>
  )
}

function Node({
  projectId,
  entry,
  depth,
  selected,
  onSelect,
  owner,
  trunk,
  open,
  onToggle,
  reload,
  onMenu,
}: {
  projectId: string
  entry: FileEntry
  depth: number
  selected: string | null
  onSelect: (path: string | null) => void
  owner?: string
  /** 看主干 —— 和 owner 互斥 */
  trunk?: boolean
  open: ReadonlySet<string>
  onToggle: (path: string) => void
  reload: number
  onMenu: (target: MenuTarget) => void
}) {
  const isSelected = entry.path === selected

  /** 右键：停在哪儿，就在哪儿开菜单。**不选中它** —— 右键是"要动它"，不是"要看它" */
  function openMenu(event: React.MouseEvent) {
    event.preventDefault()
    onMenu({
      x: event.clientX,
      y: event.clientY,
      path: entry.path,
      directory: entry.directory,
    })
  }

  if (entry.directory) {
    const expanded = open.has(entry.path)
    return (
      <>
        <button
          type="button"
          onClick={() => onToggle(entry.path)}
          onContextMenu={openMenu}
          // **文字是白的，颜色只给图标**（见 index.css 里那两条 loom-folder / loom-code）。
          // 白字 + 彩色小图标，满屏撒着一点点颜色 —— 那是 IDEA 的树好看的真正原因，
          // 而"给文字也上色"正好是它的反面：同一屏上会出现两组有颜色的字
          className="relative flex w-full items-center gap-1.5 py-1 pr-2 text-left text-sm text-foreground hover:bg-accent"
          style={{ paddingLeft: depth * INDENT + ROW_PAD }}
        >
          <Guides depth={depth} />
          {/* 箭头保持灰：它是"这一行能展开"的记号，不是一个标题 */}
          <ChevronRight
            className={`size-3.5 shrink-0 text-loom-faint transition-transform ${expanded ? 'rotate-90' : ''}`}
          />
          <Folder className="size-3.5 shrink-0 text-loom-folder" />
          <span className="truncate">{entry.name}</span>
        </button>

        {expanded && (
          <Level
            projectId={projectId}
            path={entry.path}
            depth={depth + 1}
            selected={selected}
            onSelect={onSelect}
            owner={owner}
              trunk={trunk}
            open={open}
            onToggle={onToggle}
            reload={reload}
            onMenu={onMenu}
          />
        )}
      </>
    )
  }

  return (
    <button
      type="button"
      onClick={() => onSelect(entry.path)}
      onContextMenu={openMenu}
      // 和目录同一个亮度、同一个悬停 —— 两行的区别全在图标上（形状 + 颜色）。
      // **"这一行是不是选中的"由那块填充回答**，不再靠"从暗变亮"回答第二次：
      // 一个信号只回答一个问题
      className={`relative flex w-full items-center gap-1.5 py-1 pr-2 text-left text-sm text-foreground hover:bg-accent ${
        isSelected ? 'bg-selected' : ''
      }`}
      style={{ paddingLeft: depth * INDENT + ROW_PAD + 12 }}
    >
      <Guides depth={depth} />
      {/* 代码是暖色、其余保持中性 —— 三种状态里有一种"没有颜色"，
          这样"有颜色"才是一句有用的话，而不是到处都在喊 */}
      <FileIcon
        className={`size-3.5 shrink-0 ${codeish(entry.name) ? 'text-loom-code' : 'text-loom-faint'}`}
      />
      <span className="truncate">{entry.name}</span>
    </button>
  )
}

/**
 * 缩进参考线：**每一层祖先在这一行里留下一根竖线**。
 *
 * <h2>为什么它值得画</h2>
 * 深目录里没有它基本读不了。这个仓库自己也够深（后端那串包名叠起来就是这个量级）——
 * 那一层的每一行左边有八根一模一样的缩进，而**数缩进是数不准的**：
 * 你能看出"它比上面深"，看不出"它属于谁"。一根线就把那个问题解决了。
 *
 * <p>线画在行**内部**（绝对定位、不占布局）：每行各画自己那几根，
 * 相邻两行首尾相接，看上去就是一整条贯通的线。
 */
function Guides({ depth }: { depth: number }) {
  return (
    <>
      {Array.from({ length: depth }, (_, level) => (
        <span
          key={level}
          aria-hidden
          className="absolute inset-y-0 w-px bg-border"
          style={{ left: level * INDENT + ROW_PAD }}
        />
      ))}
    </>
  )
}

/** 树里那些不是节点的行（读取中 / 读不到 / 空目录）。它们同样带参考线。 */
function Plain({
  depth,
  muted,
  children,
}: {
  depth: number
  muted?: boolean
  children: React.ReactNode
}) {
  return (
    <p
      className={`relative py-1 pr-2 text-sm ${muted ? 'text-loom-faint' : ''}`}
      style={{ paddingLeft: depth * INDENT + ROW_PAD + 12 }}
    >
      <Guides depth={depth} />
      {children}
    </p>
  )
}

/**
 * 命名：新建时的名字，或者改名后的名字。
 *
 * <p>用弹窗而不是树里就地编辑（IDEA 是就地编辑）：就地编辑要处理输入框和行
 * 抢焦点、要处理"编到一半点到别处"、要处理缩进对齐 —— 那是一整套交互，
 * 而它换来的只是"少一次弹窗"。等这一栏值得那么讲究了再说。
 *
 * <p>{@code onSubmit} **返回一句话就是失败**：那句话显示在输入框下面，弹窗不关。
 * 失败时把弹窗关掉的话，用户刚打的字就没了，而他正要做的是改一下再试。
 */
function NameDialog({
  task,
  onSubmit,
  onClose,
}: {
  task: NameTask
  onSubmit: (name: string) => Promise<string | null>
  onClose: () => void
}) {
  const [name, setName] = useState(task.initial)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const title = task.from !== null ? '重命名' : task.directory ? '新建目录' : '新建文件'

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    const problem = await onSubmit(name.trim())
    if (problem) {
      setError(problem)
    }
    setBusy(false)
  }

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>
              {task.from !== null
                ? '名字写全，包括扩展名'
                : task.directory
                  ? `会建在${task.parent === '' ? '工作区根目录' : `「${task.parent}」`}下面`
                  : `会建在${task.parent === '' ? '工作区根目录' : `「${task.parent}」`}下面，内容先是空的`}
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-2 py-4">
            <Label htmlFor="file-name">名字</Label>
            <Input
              id="file-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoFocus
              required
            />
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}
          </div>

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose} disabled={busy}>
              算了
            </Button>
            <Button type="submit" disabled={busy || name.trim() === ''}>
              {busy ? '正在做…' : title}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** 表头上那种只有图标的小按钮。名字进 title 和 aria-label，屏幕上不占地方。 */
function IconAction({
  label,
  onClick,
  children,
}: {
  label: string
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={onClick}
      className="rounded p-1 text-loom-faint transition-colors hover:text-foreground [&_svg]:size-3.5"
    >
      {children}
    </button>
  )
}

/**
 * 这个文件名看着像不像代码。
 *
 * <p>只用来决定图标上不上色。判据就是一张扩展名清单，**不假装能认全**：
 * 认不出来的一律当"不是代码"，于是它保持中性。
 *
 * <p>宁可偏保守：把一张图片标成橙色没人会做错事，但把配置标成代码会让
 * "颜色 = 代码"这句话变得不可信 —— 而一个不可信的信号不如没有信号。
 */
const CODE_EXTENSIONS = new Set([
  'java', 'kt', 'ts', 'tsx', 'js', 'jsx', 'css', 'scss', 'vue', 'svelte',
  'py', 'go', 'rs', 'rb', 'php', 'c', 'h', 'cc', 'cpp', 'hpp', 'cs', 'swift',
  'sh', 'bash', 'sql', 'proto', 'graphql',
])

function codeish(name: string): boolean {
  const dot = name.lastIndexOf('.')
  // dot === 0 是 .gitignore 这类点开头的文件 —— 它们不是"点扩展名"
  return dot > 0 && CODE_EXTENSIONS.has(name.slice(dot + 1).toLowerCase())
}
