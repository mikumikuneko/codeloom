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
import { useEffect, useState } from 'react'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { NameInputDialog } from '@/components/NameInputDialog'
import { ContextMenu, type MenuAction } from '@/components/workspace/ContextMenu'
import { baseName, parentOf } from '@/lib/path'
import { errorText, files, projectArchiveUrl, type FileEntry } from '@/lib/api'
import { isCodeFile } from '@/lib/highlight'

/**
 * 工作区左栏：项目树。
 *
 * 一层一层拉，不递归：后端只列一层（`GET /files?path=`），点开一层拉一层。整树
 * 一次拉在大仓库上要几秒，没点开的层零成本；更要紧的是不会为了看 `src/` 而读进
 * `node_modules/`。
 *
 * 展开状态放在这一层，不放在每个节点上：头上的刷新、全部折叠是整树动作，要一次
 * 作用到所有已展开目录，节点自己记够不着。
 *
 * 它从不显示对方的工作树：后端写接口没有 `owner`，读接口虽支持但界面不传；对方的
 * 树是唯一一块只有他能改的地方。
 *
 * 刷新有两个来源：组件自己的（右键新建、改名、删除）与外面推进来的 `reload`
 * （agent 改了工作区，发生在后端和磁盘上，这里看不见）。两者合成一个数往下传。
 */
export function FileTree({
  projectId,
  rootName,
  selected,
  onSelect,
  onMoved,
  trunk = false,
  onTrunk,

  reload: externalReload = 0,
}: {
  projectId: string
  /** 看主干而不是自己的树。中栏跟着它走，两处必须看同一棵树 */
  onTrunk: (trunk: boolean) => void
  trunk?: boolean

  /** 项目目录名，即树的第一行。见 {@link PROJECT_ROOT} */
  rootName: string
  /** 传 null 表示选中的文件没了（刚被删），中栏要清空 */
  onSelect: (path: string | null) => void
  /** 改名或移动之后，把打开着的标签指到新路径 */
  onMoved: (from: string, to: string) => void
  selected: string | null
  /**
   * 外面推来的"重新拉一遍"。每变一次拉一次，数本身没有含义。
   *
   * 只增不减，是外部状态，这里不把它复制进自己的 state：复制就要写"prop 变了同步
   * 过去"的 effect，而那种 effect 晚一次同步就会让这次刷新凭空消失。
   */
  reload?: number
}) {
  // 项目那一行默认展开：它是"这是项目"，不是"这里有个能收起来的东西"。
  const [open, setOpen] = useState<ReadonlySet<string>>(() => new Set([PROJECT_ROOT]))
  /** 本组件自己触发的刷新次数，见上面"刷新有两个来源" */
  const [ownReload, setOwnReload] = useState(0)
  const [menu, setMenu] = useState<MenuTarget | null>(null)
  const [naming, setNaming] = useState<NameTask | null>(null)
  const [removing, setRemoving] = useState<MenuTarget | null>(null)

  // 两个来源相加而不是取较大者：两者各自只增不减，而每一层只在乎"这个数变了没有"；
  // 取较大者会把其中一个来源的连续两次刷新压成一次。
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

  /** 一个路径没了（删除或改名）：它自己与子孙都从展开集合里划掉。 */
  function forget(path: string) {
    setOpen((current) =>
      new Set([...current].filter((each) => each !== path && !each.startsWith(`${path}/`))),
    )
  }

  /**
   * 右键点上该给哪几项。
   *
   * 右键空白处只给"新建"：那里没有东西可改名、可删除；给一个点了没反应的
   * "重命名"比不给更糟。
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
              initial: baseName(target.path),
            }),
        },
        { label: '删除', danger: true, onSelect: () => setRemoving(target) },
      )
    }
    return actions
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="pane-head px-3">
        <h2 className="text-xs text-muted-foreground">项目</h2>

        {/* 三个动作放这里，因为它们作用在整棵树上；一条导航上没有"当前项"可挂右键菜单 */}
        <div className="ml-auto flex items-center gap-0.5">
          {/*
            看哪棵树：我的（自己改到哪儿了）还是主干（那条共享的线）。

            图标本身分两态（文件夹 / 分支），当前在哪一棵扫一眼就知道。这一点不能省：
            两边路径长得一模一样，看错一眼就以为在看自己的改动；hover 才显示不够
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
            下载放最后：前面三个是"看这棵树"的开关，它作用在整个项目上。它下的是
            主干版本，不是你现在看的这棵树；这一点必须写进 label，否则会被当成
            "下载我正在看的这份"
          */}
          <IconAction
            label="下载这个项目（主干，zip）"
            onClick={() => {
              // 顶层导航触发下载：响应带 attachment，页面不会跳走。
              window.location.href = projectArchiveUrl(projectId)
            }}
          >
            <Download />
          </IconAction>
        </div>
      </div>

      <div
        className="min-h-0 flex-1 overflow-auto pb-4"
        // 空白处右键 = 对着项目根目录右键（target 是容器自己，不是某一行）。
        onContextMenu={(event) => {
          if (event.target !== event.currentTarget) {
            return
          }
          event.preventDefault()
          setMenu({ x: event.clientX, y: event.clientY, path: '', directory: true })
        }}
      >
        {/* 下面一大片是点得动的空白，让根目录的右键有地方落 */}
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
              // 缩进从 1 起：项目是第 0 层，文件在它里面。
              depth={1}
              selected={selected}
              onSelect={onSelect}
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
                // 名字没改就别发请求：后端会说"那里已经有一个同名的了"（就是它自己）。
                if (to !== naming.from) {
                  await files.move(projectId, naming.from, to)
                  forget(naming.from)
                  onMoved(naming.from, to)
                }
              } else {
                await files.create(projectId, to, naming.directory)
              }
              setNaming(null)
              refresh()
              // 新建文件直接打开：建完还要去树里再找一次，等于把"新建"拆成两步
              if (naming.from === null && !naming.directory) {
                onSelect(to)
              }
              return null
            } catch (e) {
              return errorText(e)
            }
          }}
        />
      )}

      {removing !== null && (
        // 删一个文件也要问，因为工作区里没有回收站：提交过的还能靠回滚找回，还没
        // 提交的（手工刚建的、agent 这一轮刚写的）删掉就真没了。目录刻意不说
        // "里面有 N 个文件"：那要额外问后端，也会让人把注意力放在"几个"上。
        <ConfirmDialog
          title={`删除「${baseName(removing.path)}」`}
          description={
            removing.directory
              ? '目录里的东西会一起没。还没提交过的部分找不回来。'
              : '删掉就找不回来了；提交过的还能靠回滚找回。'
          }
          confirmLabel="删除"
          onClose={() => setRemoving(null)}
          onConfirm={async () => {
            try {
              await files.remove(projectId, removing.path)
              // 选中和展开的东西可能就在被删的那一棵里；不清的话中栏会停在
              // 一个已不存在的文件上，显示"读不到这个文件"。
              if (
                selected === removing.path ||
                selected?.startsWith(`${removing.path}/`)
              ) {
                onSelect(null)
              }
              forget(removing.path)
              setRemoving(null)
              refresh()
              return null
            } catch (e) {
              return errorText(e)
            }
          }}
        />
      )}
    </div>
  )
}

/**
 * 缩进一格多少像素，与下面的 paddingLeft 用同一个数。
 *
 * 不画缩进参考线：层级靠缩进本身表达。浅目录里那条线只是噪音，项目根那根尤其
 * 没有信息量（每行都有，只说明"这些都在项目里"）。要加回来先想清楚它解决哪个
 * 具体问题：深目录里"看不出属于谁"才值得画线，而那不是现在这棵树的样子。
 */
const INDENT = 12
/** 一行内容从哪儿开始（也就是缩进 0 那一层的位置）。 */
const ROW_PAD = 8

/**
 * 项目那一行在 `open` 里用的键。
 *
 * 它同时是项目根的路径（`""`），而根不是任何一条 `FileEntry`，所以不会和某个目录
 * 撞上。展开状态复用同一套机制，不另起一份。
 */
const PROJECT_ROOT = ''

/**
 * 树的第一行：项目本身。
 *
 * 文件上面非得有这一行：先有项目才有文件，一棵从文件开始的树让人不知道这些文件
 * 属于哪儿，而协作时这点要紧。它长得和目录行一模一样，因为它就是一个目录；位置
 * 已经说清了"它在最上面"，加粗或换色只会说"它和别人不一样"。
 *
 * 它的右键菜单就是根目录的菜单（新建文件 / 新建目录），与点在下面空白处是同一个目标。
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
      className="flex w-full items-center gap-1.5 py-1 pr-2 text-left text-sm text-foreground hover:bg-accent"
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
  /** 空字符串表示项目根目录 */
  path: string
  directory: boolean
}

/** 正在命名的那件事：新建或改名。 */
interface NameTask {
  /** 放进哪个目录；改名时不看它 */
  parent: string
  /** 建的是否目录；改名时不看它 */
  directory: boolean
  /** 改的是哪一个；新建时为 null */
  from: string | null
  initial: string
}

/** 一层的加载状态：加载中、出错、空是三件不同的事，不能都渲染成空白。 */
function Level({
  projectId,
  path,
  depth,
  selected,
  onSelect,
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
  /** 看主干，而不是自己那棵树 */
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
    // 不在这里把 entries 清成 null：刷新时清掉会让整棵树闪一下"正在读取"，
    // 而拉回来的东西多半一样。旧内容留到新内容到位为止。
    files
      .list(projectId, path, trunk)
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
  }, [projectId, path, trunk, reload])

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
    // 空目录在树里显示而不是藏掉：藏掉的话，刚建的空项目看起来和"加载失败"一模一样。
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
  /** 看主干，而不是自己那棵树 */
  trunk?: boolean
  open: ReadonlySet<string>
  onToggle: (path: string) => void
  reload: number
  onMenu: (target: MenuTarget) => void
}) {
  const isSelected = entry.path === selected

  /** 右键：停在哪儿就在哪儿开菜单。不选中它：右键是"要动它"，不是"要看它" */
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
          // 文字是白的，颜色只给图标（见 index.css 的 loom-folder / loom-code）：
          // 白字加彩色小图标，满屏只撒一点颜色；给文字也上色会让同一屏出现两组
          // 有颜色的字。
          className="flex w-full items-center gap-1.5 py-1 pr-2 text-left text-sm text-foreground hover:bg-accent"
          style={{ paddingLeft: depth * INDENT + ROW_PAD }}
        >
          {/* 箭头保持灰：它是"这一行能展开"的记号，不是标题 */}
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
      // 和目录同一亮度、同一悬停，两行区别全在图标上（形状 + 颜色）。
      // "是否选中"由那块填充回答，不再用"从暗变亮"回答第二次。
      className={`flex w-full items-center gap-1.5 py-1 pr-2 text-left text-sm text-foreground hover:bg-accent ${
        isSelected ? 'bg-selected' : ''
      }`}
      style={{ paddingLeft: depth * INDENT + ROW_PAD + 12 }}
    >
      {/* 代码是暖色、其余中性：三种状态里有一种"没有颜色"，"有颜色"才是一句
          有用的话，而不是到处都在喊 */}
      <FileIcon
        className={`size-3.5 shrink-0 ${isCodeFile(entry.name) ? 'text-loom-code' : 'text-loom-faint'}`}
      />
      <span className="truncate">{entry.name}</span>
    </button>
  )
}

/** 树里不是节点的行（读取中 / 读不到 / 空目录），缩进和节点行同一套。 */
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
      className={`py-1 pr-2 text-sm ${muted ? 'text-loom-faint' : ''}`}
      style={{ paddingLeft: depth * INDENT + ROW_PAD + 12 }}
    >
      {children}
    </p>
  )
}

/**
 * 命名：新建时的名字，或改名后的名字。
 *
 * 用弹窗而不是树里就地编辑：就地编辑要处理焦点争抢、"编到一半点到别处"、缩进
 * 对齐，换来的只是少一次弹窗。
 *
 * `onSubmit` 返回一句话就是失败：那句话显示在输入框下面，弹窗不关；关掉会让用户
 * 刚打的字消失，而他正要做的是改一下再试。
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
  const title = task.from !== null ? '重命名' : task.directory ? '新建目录' : '新建文件'
  const parent = task.parent === '' ? '项目根目录' : `「${task.parent}」`

  // 表单与新建项目共用 NameInputDialog：一个输入框、一句说明、一句就地错误，
  // 两个地方不该各有一套状态机。
  return (
    <NameInputDialog
      title={title}
      description={
        task.from !== null
          ? '名字写全，包括扩展名'
          : task.directory
            ? `会建在${parent}下面`
            : `会建在${parent}下面`
      }
      initial={task.initial}
      submitLabel={title}
      busyLabel="正在做…"
      onSubmit={onSubmit}
      onClose={onClose}
    />
  )
}

/** 表头上只有图标的小按钮。名字进 title 和 aria-label，屏幕上不占地方。 */
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
