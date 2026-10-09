import { FolderTree, LogOut, Server } from 'lucide-react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { useAuth } from '@/stores/auth'

/**
 * 登录之后这些页面共用的外壳：**左边一条栏，右边是内容**。
 *
 * <h2>为什么改成了左边一条栏</h2>
 * 从前它是一条居中的窄带子，飘在一整屏深色底上 —— 在 1100px 的窗口里看着还行，
 * 铺到 2560px 就变成"中间一条、四周全是空"，读起来像没做完。
 *
 * <p>deepseek-harness 的外壳是三栏 AppFrame（左栏 264–420、主内容、右栏），**页面内容活在
 * 中间那一栏里**，所以窗口多大它都不会飘。我们只有两个去处，不需要三栏 ——
 * 但"内容得挂在一个框里"这件事是一样的。于是：一条 240px 的左栏，
 * 右边是内容区，内容区里再收一列（`max-w-5xl`）。
 *
 * <h2>左栏放什么</h2>
 * 上：应用名（点它回项目列表）；中：两个去处（项目、供应商）；
 * 下：**我是谁 + 登出**。把"我是谁"从顶栏挪到底部，是因为它和导航不是一类东西 ——
 * 它在每页都一样，而且很少动。
 *
 * <p>（从前设置收在右上角的一个齿轮里，理由是"它只是偶尔去一次"。那条理由只对一半：
 * 偶尔去不等于找不到 —— 一个总在同一个位置的入口，比一个藏在图标后面的入口好找。）
 *
 * <h2>为什么外壳定高、只有内容区滚动</h2>
 * 左栏是**框架**，不该跟着内容滚走。`h-screen` + 内容区自己 `overflow-y-auto`
 * 是这件事的直接写法；靠 sticky 去凑会让底部的"我是谁"在长列表下也一起飘。
 */
export function AppShell() {
  const user = useAuth((s) => s.user)
  const logout = useAuth((s) => s.logout)
  const navigate = useNavigate()

  async function signOut() {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="flex h-screen overflow-hidden bg-background text-foreground">
      <aside className="flex w-60 shrink-0 flex-col border-r border-border bg-raise">
        <Link
          to="/"
          className="flex h-12 items-center px-4 text-sm font-semibold tracking-tight"
        >
          codeloom
        </Link>

        <nav className="flex flex-col gap-0.5 px-2">
          <NavItem to="/" icon={<FolderTree className="size-4" />} label="项目" />
          <NavItem to="/settings" icon={<Server className="size-4" />} label="供应商" />
        </nav>

        {/* 贴到底部：它每一页都一样，也不参与导航 */}
        <div className="mt-auto flex items-center gap-1 border-t border-border px-3 py-2.5">
          <span className="min-w-0 flex-1 truncate text-sm text-muted-foreground">
            {user?.displayName}
          </span>
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
      </aside>

      <main className="min-w-0 flex-1 overflow-y-auto">
        {/* 内容区里再收一列：铺满 2300px 的话，一行字的行宽会长到眼睛左右跑。
            三个页面共用同一列，切页的时候标题和卡片不会左右跳。
            max-w-3xl（768）：项目行、供应商行都是"名字 + 一个动作"的窄内容，
            再宽中间就只剩空 */}
        <div className="mx-auto w-full max-w-3xl px-8 py-10">
          <Outlet />
        </div>
      </main>
    </div>
  )
}

/**
 * 左栏里的一项。
 *
 * <p>选中态用**底色**（`bg-selected`），和会话列表、文件树是同一个记号 ——
 * 全站"我选的是这一个"只有一种画法。
 */
function NavItem({ to, icon, label }: { to: string; icon: React.ReactNode; label: string }) {
  return (
    <NavLink
      to={to}
      end
      className={({ isActive }) =>
        `flex items-center gap-2.5 rounded-md px-2.5 py-1.5 text-sm transition-colors ${
          isActive
            ? 'bg-selected text-foreground'
            : 'text-muted-foreground hover:bg-accent hover:text-foreground'
        }`
      }
    >
      {icon}
      {label}
    </NavLink>
  )
}
