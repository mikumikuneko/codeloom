import { useEffect } from 'react'
import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'

import { AppShell } from '@/components/AppShell'
import { InvitePage } from '@/pages/InvitePage'
import { LoginPage } from '@/pages/LoginPage'
import { WorkspacePage } from '@/pages/WorkspacePage'
import { ProjectsPage } from '@/pages/ProjectsPage'
import { ProvidersPage } from '@/pages/ProvidersPage'
import { useAuth } from '@/stores/auth'

/**
 * 路由 + 登录关卡。
 *
 * 关卡在这一层而不是每个页面自己判断："没登录"的处理只有一个（去登录页并记住本来
 * 要去哪儿）。各写一遍迟早在某页写漏，而漏掉的那页会安静地渲染出空界面，让人以为
 * 功能还没做。
 */
export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        {/* 邀请页刻意在登录关卡外面：点开链接的人多半还没注册，扔到登录页只会看到
            "请登录"，不知道自己被邀请去哪。先看预览、再决定注册 */}
        <Route path="/invite/:token" element={<InvitePage />} />

        {/* 工作区也在登录关卡里，但在外壳外面：它自己占满整个视口（三栏必须满屏，
            嵌在居中的 6xl 容器里就全错了）。它自己的顶栏负责设置和登出，所以不需要
            AppShell */}
        <Route element={<RequireAuth />}>
          <Route path="/projects/:projectId" element={<WorkspacePage />} />
        </Route>

        <Route element={<RequireAuth />}>
          {/* 外壳单独一层：它里面的 Outlet 才是具体页面。这样顶栏不会在每次切页时
              重新挂载（导航高亮、滚动位置靠它稳住） */}
          <Route element={<AppShell />}>
            <Route path="/" element={<ProjectsPage />} />
            <Route path="/settings" element={<ProvidersPage />} />
          </Route>
        </Route>

        {/* 认不出来的路径一律回首页：客户端路由没有服务端 404 页面，不兜就是白屏 */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

function RequireAuth() {
  const status = useAuth((s) => s.status)
  const load = useAuth((s) => s.load)
  const location = useLocation()

  useEffect(() => {
    // 只问一次。放进依赖里的话，每次状态变化都会再问一遍，而 load() 自己会改
    // 状态，那就是自我驱动的循环。
    if (status === 'unknown') void load()
  }, [status, load])

  // unknown 是"还不知道"，不是"没登录"。必须等一下，否则每次刷新都会先闪一下
  // 登录页再跳回来。
  if (status === 'unknown') {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background">
        <p className="text-sm text-muted-foreground">正在读取登录状态…</p>
      </div>
    )
  }

  if (status === 'anonymous') {
    // 把"本来要去哪儿"记在 state 里，登录后回到那儿。放 URL 查询串也可以，但会
    // 多出一条能被分享的地址，而它描述的是一次失败的访问。
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <Outlet />
}
