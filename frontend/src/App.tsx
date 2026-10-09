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
 * 路由 + 那道登录关卡。
 *
 * <h2>为什么关卡在这一层，而不是每个页面自己判断</h2>
 * 因为"没登录"的处理方式只有一个（去登录页，并记住本来要去哪儿）。
 * 让每个页面各写一遍，迟早在某一页上写漏 —— 而漏掉的那一页会安静地渲染出一个
 * 空的界面，让人以为"这个功能还没做"。
 */
export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        {/* 邀请页**刻意在登录关卡外面**：点开链接的人多半还没注册，
            把他直接扔到登录页只会让他看到"请登录"，而不知道自己被邀请去哪。
            真实的顺序是先看预览、再决定要不要注册 —— 见 InvitePage 的类注释 */}
        <Route path="/invite/:token" element={<InvitePage />} />

        {/* 工作区**也**在登录关卡里，但在外壳**外面**：它自己去占满整个视口
            （三栏必须是满屏的，嵌在一个居中的 6xl 容器里就全错了）。
            它自己的顶栏负责设置和登出 —— 所以不需要 AppShell */}
        <Route element={<RequireAuth />}>
          <Route path="/projects/:projectId" element={<WorkspacePage />} />
        </Route>

        <Route element={<RequireAuth />}>
          {/* 外壳单独一层：它里面的 Outlet 才是具体页面。
              这样顶栏不会在每次切页面时重新挂载（导航高亮、滚动位置都靠它稳住） */}
          <Route element={<AppShell />}>
            <Route path="/" element={<ProjectsPage />} />
            <Route path="/settings" element={<ProvidersPage />} />
          </Route>
        </Route>

        {/* 认不出来的路径一律回首页，而不是留一个白屏 ——
            客户端路由没有服务端的 404 页面，不兜的话就是一整片空白 */}
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
    // 只问一次。放进依赖里的话，每次状态变化都会再问一遍 ——
    // 而 load() 自己会改状态，那就是一个自我驱动的循环
    if (status === 'unknown') void load()
  }, [status, load])

  // `unknown` 是"还不知道"，不是"没登录"。这里必须等一下，
  // 否则每次刷新都会先闪一下登录页再跳回来
  if (status === 'unknown') {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background">
        <p className="text-sm text-muted-foreground">正在读取登录状态…</p>
      </div>
    )
  }

  if (status === 'anonymous') {
    // 把"本来要去哪儿"记在 state 里，登录之后回到那儿。
    // 放在 URL 的查询串里也可以，但那会多出一条能被分享出去的地址 ——
    // 而它描述的是一次失败的访问，没有分享的意义
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <Outlet />
}
