import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Folder } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError, projects, type Project } from '@/lib/api'
import { useAsync } from '@/lib/useAsync'
import { useAuth } from '@/stores/auth'

/**
 * 我的项目。
 *
 * <h2>一行一张卡片，而每一行要有**一个看得见的锚点**</h2>
 * 从前这一行只有两段字（项目名 + 成员名）靠间距排开，于是它读起来像一张没做完的表 ——
 * 左边空落落的，眼睛没有落脚的地方。现在每行左边有一个文件夹图标（**和文件树里同一个**，
 * 钢蓝色、只有图标上色），名字在它右边，成员降成第二行的小字。
 *
 * <p>形状是 deepseek-harness 那份圆角规范里说的"独立内容卡片"档（R20 + 卡片底色 + 发丝描边）——
 * 账号、余额、模型提供商那些卡片用的是同一套材质，所以一整站看下来是同一个东西。
 *
 * <h2>还是一列，不是网格</h2>
 * 一张卡片适合"内容互不相干、形状差不多"的东西；而这里的每一行仍然只说两件事：
 * **它叫什么**、**有谁在**。所以是一列卡片，不是一格一格的方块 —— 那样连同"一共有几个
 * 项目"都变得难数。
 */
export function ProjectsPage() {
  const { data: mine, error, loading, reload } = useAsync(() => projects.mine(), [])
  const [open, setOpen] = useState(false)

  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between">
        <div className="space-y-1">
          <h1 className="text-xl font-medium">我的项目</h1>
          {/* 一句话说清这一页是什么。**没有它，顶部就只有四个字加一个按钮，
              下面一大片空** —— 那句话是拿来把"空"接住的 */}
          <p className="text-sm text-muted-foreground">
            每个项目两个人，各自一个 agent，共用同一棵代码树。
          </p>
        </div>
        <CreateProjectDialog open={open} onOpenChange={setOpen} onCreated={reload} pageHeader />
      </div>

      {loading && <Note>正在读取…</Note>}
      {error && <Note tone="error">{error}</Note>}

      {mine && mine.length === 0 && (
        // 空状态是**一次邀请**，不是一句"暂无数据"：它该告诉人"从这里开始会怎样"。
        //
        // 而且它**落在视线中间**：一个人还没有项目的时候，这一页上只有这一件事 ——
        // 把它按在左上角，剩下整屏空白就变成"这页面坏了/还没做完"。
        // 居中说的是"这里本来就只有一件事要做"，而那句话下面得有个按钮
        <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4">
          <p className="max-w-xs text-center text-sm leading-6 text-muted-foreground">
            还没有项目。建一个 —— 建完把链接发给对方，他点开就能进来。
          </p>
          <CreateProjectDialog open={open} onOpenChange={setOpen} onCreated={reload} />
        </div>
      )}

      {mine && mine.length > 0 && (
        <ul className="flex flex-col gap-2">
          {mine.map((project) => (
            // 「退出」放在链接**外面**、和它并排：它是这一行上的另一个动作，
            // 不是"进项目"的一部分。放进链接里就成了一个嵌在链接里的按钮，
            // 点哪一半取决于鼠标落在哪个像素上
            <li
              key={project.id}
              className="flex items-center rounded-xl border border-border bg-card pr-2 transition-colors hover:border-input"
            >
              <Link
                to={`/projects/${project.id}`}
                className="flex min-w-0 flex-1 items-center gap-3 px-5 py-4"
              >
                <Folder className="size-4 shrink-0 text-loom-folder" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium">{project.name}</span>
                  {/* 成员降成第二行的小字，**代替"每个项目最多两个人"那句说明** ——
                      看得见的两个名字，比一句关于数量的规则有用 */}
                  <span className="block truncate text-xs text-muted-foreground">
                    {project.members.map((m) => m.displayName).join('、')}
                  </span>
                </span>
              </Link>
              <LeaveDialog project={project} onLeft={reload} />
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function CreateProjectDialog({
  open,
  onOpenChange,
  onCreated,
  pageHeader = false,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: () => void
  /** 摆在哪决定它有多响：页头里是一个**看得见的**按钮（那是这一页的主要动作），
   *  空状态里是**主按钮**（此刻它是唯一能做的事） */
  pageHeader?: boolean
}) {
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await projects.create(name)
      setName('')
      onOpenChange(false)
      onCreated()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '连不上服务器')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>
        <Button variant={pageHeader ? 'outline' : 'default'} size={pageHeader ? 'sm' : 'default'}>
          新建项目
        </Button>
      </DialogTrigger>
      <DialogContent>
        <form onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>新建项目</DialogTitle>
            <DialogDescription>建好之后名字就不能改了。</DialogDescription>
          </DialogHeader>

          <div className="space-y-2 py-4">
            <Label htmlFor="project-name">项目名</Label>
            <Input
              id="project-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              autoFocus
            />
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}
          </div>

          <DialogFooter>
            <Button type="submit" disabled={busy}>
              {busy ? '创建中…' : '创建'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * 退出项目。
 *
 * <h2>同一句"退出"，对不同的人意味着不同的事 —— 所以按钮前面那一段必须说清是哪一种</h2>
 * 三种情形，三种后果：
 * <ul>
 *   <li><b>还是房主、项目里还有别人</b> → 房主转给对方，项目继续开着</li>
 *   <li><b>最后一个成员</b> → 项目本身没了：仓库、聊天记录、对话</li>
 *   <li><b>普通成员</b> → 只带走自己的那份，对方和聊天记录不受影响</li>
 * </ul>
 *
 * <p>这三句话**由服务端的 {@code ownerId} 推出来**，不是客户端自己定的规矩；
 * 真正干活的权限判断也全在服务端。这里只是把它说出来。
 *
 * <h2>为什么没有"删除项目"</h2>
 * 因为一个动作**不该把对方脚下的地抽走**。要把项目彻底消掉，得两个人各自退出 ——
 * 所以这里只说"你退出会怎样"，不承诺任何关于对方的事。
 */
function LeaveDialog({ project, onLeft }: { project: Project; onLeft: () => void }) {
  const me = useAuth((s) => s.user)
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const others = project.members.filter((m) => m.id !== me?.id)
  const alone = others.length === 0
  const heir = others[0]

  async function submit() {
    setError(null)
    setBusy(true)
    try {
      await projects.leave(project.id)
      setOpen(false)
      onLeft()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '连不上服务器')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        {/* 平时只是极淡的两个字，指上去才变红 —— 它是个破坏性动作，
            但不该比"进哪个项目"更响。用素按钮而不是 Button：列表里
            不需要一个带悬停底色的方框 */}
        <button
          type="button"
          className="ml-2 shrink-0 rounded px-2 py-1 text-xs text-loom-faint transition-colors hover:text-destructive"
        >
          退出
        </button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>退出「{project.name}」</DialogTitle>
          <DialogDescription>
            {alone
              ? '你是这里最后一个人。退出之后这个项目就没了 —— 仓库、聊天记录、你的对话，一起删掉'
              : project.ownerId === me?.id
                ? `你是房主。退出之后房主会变成${heir?.displayName ?? '对方'}，你在这里的对话和代码一起删掉`
                : '你在这里的对话和代码一起删掉。对方和聊天记录不受影响'}
          </DialogDescription>
        </DialogHeader>

        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}

        <DialogFooter>
          <Button variant="ghost" onClick={() => setOpen(false)} disabled={busy}>
            算了
          </Button>
          <Button variant="destructive" onClick={() => void submit()} disabled={busy}>
            {busy ? '退出中…' : '退出'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** 一句提示。**没有框、没有底色** —— 它只是一句话，不该比它描述的东西更响。 */
function Note({ children, tone }: { children: React.ReactNode; tone?: 'error' }) {
  return (
    <p
      role={tone === 'error' ? 'alert' : undefined}
      className={`text-sm ${tone === 'error' ? 'text-destructive' : 'text-muted-foreground'}`}
    >
      {children}
    </p>
  )
}
