import { FolderTree, LogOut, Server } from 'lucide-react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { useAuth } from '@/stores/auth'

/**
 * 登录后这些页面共用的外壳：左边一条栏，右边是内容。
 *
 * 左栏放应用名（回项目列表）、两个去处（项目、供应商）和底部的身份与登出。身份
 * 每页都一样且很少动，所以贴底而不占顶栏。
 *
 * 外壳定高，只有内容区滚动：左栏是框架，不该跟着内容滚走；靠 sticky 会让底部的
 * 身份在长列表下也一起漂。内容区再收一列（`max-w-3xl`），项目列表与供应商两个页面
 * 共用，切页时标题和卡片不会左右跳。工作区自己有整页的三栏布局，不套这层外壳。
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

        {/* 贴到底部：每页都一样，也不参与导航 */}
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
        {/* 内容区再收一列：铺满时一行字会长到眼睛左右跑。项目列表与供应商两个页面
            共用同一列，切页时标题和卡片不会左右跳。max-w-3xl：项目行、供应商行都是
            "名字 + 一个动作"的窄内容，再宽中间只剩空。 */}
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
 * 选中态用底色（`bg-selected`），与会话列表、文件树一致：全站"我选的是这一个"
 * 只有一种画法。
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
