import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Folder } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { NameInputDialog } from '@/components/NameInputDialog'
import { errorText, projects, type Project } from '@/lib/api'
import { useAsync } from '@/lib/useAsync'
import { useAuth } from '@/stores/auth'

/**
 * 我的项目。
 *
 * 一行一张卡片，每行左边有一个看得见的锚点：文件夹图标和文件树里同一个（钢蓝色、
 * 只有图标上色），名字在右边，成员降成第二行小字。只有两段字靠间距排开读起来像
 * 一张没做完的表。
 *
 * 材质是"独立内容卡片"：圆角 + 卡片底色 + 发丝描边，和账号、供应商那些卡片同一套。
 *
 * 还是列而不是网格：每行只说两件事——它叫什么、有谁在；一格一格的方块会让"一共
 * 有几个项目"都难数。
 */
export function ProjectsPage() {
  const { data: mine, error, loading, reload } = useAsync(() => projects.mine(), [])
  const [open, setOpen] = useState(false)

  /**
   * 这一页也安静地自己刷：成员名是"谁在这个项目里"，对方刚通过邀请链接加入时，
   * 卡片也该跟上。理由见
   * `docs/decisions/feature/2026-10-18-live-lists-poll-while-visible.md`。
   *
   * 用 quiet 重拉：这一页的 loading 会在列表上面挂"正在读取…"，每 5 秒闪一次比
   * 不刷新更糟。
   */
  const liveReload = useRef(reload)
  useEffect(() => {
    liveReload.current = reload
  })
  useEffect(() => {
    const tick = () => {
      if (document.visibilityState === 'visible') {
        liveReload.current(true)
      }
    }
    const timer = window.setInterval(tick, 5000)
    window.addEventListener('focus', tick)
    document.addEventListener('visibilitychange', tick)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', tick)
      document.removeEventListener('visibilitychange', tick)
    }
  }, [])

  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between">
        <div className="space-y-1">
          <h1 className="text-xl font-medium">我的项目</h1>
          {/* 一句话说清这一页是什么：没有它，顶部只有四个字加一个按钮，下面一大片空 */}
          <p className="text-sm text-muted-foreground">
            每个项目两个人，各自一个 agent，共用同一棵代码树。
          </p>
        </div>
        {/* 一个页面只挂一个弹窗：两个入口、两个实例的话，空项目时两份遮罩和两份
            表单会同时出现（它们共享同一个 open） */}
        <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
          新建项目
        </Button>
      </div>

      {loading && <Note>正在读取…</Note>}
      {error && <Note tone="error">{error}</Note>}

      {mine && mine.length === 0 && (
        // 空状态是一次邀请，不是一句"暂无数据"：它该告诉人"从这里开始会怎样"，
        // 而且落在视线中间——还没有项目时这一页只有这一件事，按在左上角会让整屏
        // 空白变成"页面坏了/还没做完"。下面得有一个按钮。
        <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4">
          <p className="max-w-xs text-center text-sm leading-6 text-muted-foreground">
            还没有项目。建一个 —— 建完把链接发给对方，他点开就能进来。
          </p>
          <Button onClick={() => setOpen(true)}>新建项目</Button>
        </div>
      )}

      {mine && mine.length > 0 && (
        <ul className="flex flex-col gap-2">
          {mine.map((project) => (
            // 「退出」放在链接外面、和它并排：它是这一行上的另一个动作，不是"进
            // 项目"的一部分。放进链接里，点哪一半取决于鼠标落在哪个像素上。
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
                  {/* 成员降成第二行小字，代替"每个项目最多两个人"那句说明：
                      看得见的两个名字比一句数量规则有用 */}
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

      {/* 弹窗只挂这一个：页头和空状态各放触发按钮，但表单、遮罩和输入状态只有一份 */}
      <CreateProjectDialog open={open} onOpenChange={setOpen} onCreated={reload} />
    </div>
  )
}

function CreateProjectDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: () => void
}) {
  // 关掉就卸载：名字和错误跟着清掉。表单与文件树的命名框共用 NameInputDialog。
  if (!open) {
    return null
  }
  return (
    <NameInputDialog
      title="新建项目"
      description="建好之后名字就不能改了。"
      label="项目名"
      submitLabel="创建"
      busyLabel="创建中…"
      onSubmit={async (name) => {
        try {
          await projects.create(name)
          onOpenChange(false)
          onCreated()
          return null
        } catch (e) {
          return errorText(e)
        }
      }}
      onClose={() => onOpenChange(false)}
    />
  )
}

/**
 * 退出项目。
 *
 * 同一句"退出"对不同的人意味着不同的事，所以文案必须说清是哪一种：还是房主且项目
 * 里还有别人 → 房主转给对方，项目继续；最后一个成员 → 项目本身没了（仓库、聊天
 * 记录、对话）；普通成员 → 只带走自己的那份，对方和聊天记录不受影响。
 *
 * 这三句话由服务端的 `ownerId` 推出来，真正干活的权限判断也全在服务端；这里只是
 * 说出来。没有"删除项目"：一个动作不该把对方还在用的项目一起删掉——要彻底消掉，
 * 得两个人各自退出。见
 * `docs/decisions/architecture/2026-10-20-leaving-takes-your-tree-with-you.md`。
 */
function LeaveDialog({ project, onLeft }: { project: Project; onLeft: () => void }) {
  const me = useAuth((s) => s.user)
  const [open, setOpen] = useState(false)

  const others = project.members.filter((m) => m.id !== me?.id)
  const alone = others.length === 0
  const heir = others[0]

  return (
    <>
      {/* 平时只是极淡的两个字，指上去才变红：破坏性动作不该比"进哪个项目"更响。
          用素按钮而不是 Button，列表里不需要带悬停底色的方框 */}
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="ml-2 shrink-0 rounded px-2 py-1 text-xs text-loom-faint transition-colors hover:text-destructive"
      >
        退出
      </button>

      {/* 危险操作的确认框只有一份实现（ConfirmDialog）：状态、错误、按钮语义
          都在那儿，这里只提供文案和要做的事 */}
      {open && (
        <ConfirmDialog
          title={`退出「${project.name}」`}
          description={
            alone
              ? '你是这里最后一个人。退出之后这个项目就没了 —— 仓库、聊天记录、你的对话，一起删掉'
              : project.ownerId === me?.id
                ? `你是房主。退出之后房主会变成${heir?.displayName ?? '对方'}，你在这里的对话和代码一起删掉`
                : '你在这里的对话和代码一起删掉。对方和聊天记录不受影响'
          }
          confirmLabel="退出"
          onConfirm={async () => {
            try {
              await projects.leave(project.id)
              setOpen(false)
              onLeft()
              return null
            } catch (e) {
              return errorText(e)
            }
          }}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  )
}

/** 一句提示。没有框、没有底色：它只是一句话，不该比它描述的东西更响。 */
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
