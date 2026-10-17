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

  /**
   * 这一页也**安静地**自己刷：成员名是"谁在这个项目里"，对方刚通过邀请链接加入时，
   * 这里的卡片也应该跟上（和 {@code WorkspacePage} 的轮询是同一个理由）。
   *
   * <p>用 `quiet` 重拉：这一页的 loading 会在列表上面挂一句"正在读取…" ——
   * 每 5 秒闪一次比不刷新更糟。
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
          {/* 一句话说清这一页是什么。**没有它，顶部就只有四个字加一个按钮，
              下面一大片空** —— 那句话是拿来把"空"接住的 */}
          <p className="text-sm text-muted-foreground">
            每个项目两个人，各自一个 agent，共用同一棵代码树。
          </p>
        </div>
        {/* 一个页面只挂**一个**弹窗。两个入口、两个实例的话，空项目时
            两份遮罩和两份表单会同时出现（它们共享同一个 open） */}
        <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
          新建项目
        </Button>
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
          <Button onClick={() => setOpen(true)}>新建项目</Button>
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

      {/* 弹窗**只挂这一个**：页头和空状态各放一个触发按钮，但表单、遮罩、
          以及那些输入状态都只有一份（见上面那段注释） */}
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
  // 关掉就卸载：名字和错误跟着一起清掉。表单本身**和文件树的命名框共用一份**
  // （见 NameInputDialog）—— 一个输入框加一句就地错误，不该有两套状态机
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

  const others = project.members.filter((m) => m.id !== me?.id)
  const alone = others.length === 0
  const heir = others[0]

  return (
    <>
      {/* 平时只是极淡的两个字，指上去才变红 —— 它是个破坏性动作，
          但不该比"进哪个项目"更响。用素按钮而不是 Button：列表里
          不需要一个带悬停底色的方框 */}
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="ml-2 shrink-0 rounded px-2 py-1 text-xs text-loom-faint transition-colors hover:text-destructive"
      >
        退出
      </button>

      {/* 危险操作的确认框**只有一份实现**（见 ConfirmDialog）：
          状态、错误、两个按钮的语义都在那儿，这里只提供文案和那件要做的事 */}
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
