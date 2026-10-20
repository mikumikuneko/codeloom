import { useState, type FormEvent } from 'react'
import { Navigate, useLocation } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { errorText } from '@/lib/api'
import { useAuth } from '@/stores/auth'

/**
 * 登录 / 注册。
 *
 * 同一个页面上的一个开关，因为这两个动作的用户是同一个人：新用户先要有账号，然后
 * 立刻要用它。拆成两个路由意味着他第一次得先猜对"该点哪个"，猜错的表现是
 * "账号或密码不对"——一个把人往错方向带的提示。
 */
export function LoginPage() {
  const status = useAuth((s) => s.status)
  const login = useAuth((s) => s.login)
  const register = useAuth((s) => s.register)
  const location = useLocation()

  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // 已经登录了就别停在登录页上：刷新或手敲 URL 都会走到这儿。
  if (status === 'authenticated') {
    // 回到当初被踢出去的那个页面（见 RequireAuth 里怎么把来源塞进 state）。
    const from = (location.state as { from?: string } | null)?.from
    return <Navigate to={from ?? '/'} replace />
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      if (mode === 'login') {
        await login(username, password)
      } else {
        await register(username, password, displayName)
      }
      // 成功之后不在这里跳转：状态一变，上面的 Navigate 就接手了。两处都跳会有
      // 一次"跳两次"的闪烁，目标也变得说不清。
    } catch (e) {
      // 后端的 detail 是特意写给调用方看的（"这个账号已被占用"/"账号或密码不对"），
      // 直接显示；自己再编一套等于丢掉后端的分类。
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    // 卡面（--card #141418）只比页面底（#0a0a0c）高一级、描边 8% 白，是一层浮起来
    // 的面。这一页没有卡片时是一小坨表单飘在纯黑中央，给一个面它才有落脚的地方。
    // 配方和列表页的卡片一致（rounded-xl border border-border bg-card）：全站
    // "独立内容"只有这一种材质。
    <div className="flex min-h-screen items-center justify-center p-4">
      <form
        onSubmit={submit}
        className="w-full max-w-sm space-y-8 rounded-xl border border-border bg-card p-8"
      >
        <div className="space-y-2">
          <h1 className="text-2xl font-semibold tracking-tight">codeloom</h1>
          <p className="text-sm text-muted-foreground">两个人，一个项目，各自的 agent</p>
        </div>

        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="login-username">账号</Label>
            <Input
              id="login-username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="login-password">密码</Label>
            <Input
              id="login-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              // 注册用 new-password、登录用 current-password：浏览器靠它决定
              // 要不要提示存密码，写错了会让人每次登录都被问一遍。
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              required
            />
          </div>

          {mode === 'register' && (
            <div className="space-y-1.5">
              <Label htmlFor="login-display-name">用户名</Label>
              <Input
                id="login-display-name"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
              />
              <p className="text-xs text-muted-foreground">别人看到的名字。留空就用账号</p>
            </div>
          )}
        </div>

        {error && (
          // role="alert" 让读屏软件立刻念出来：只靠颜色的错误提示对用读屏的人
          // 等于不存在。
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}

        <div className="space-y-3">
          <Button type="submit" disabled={busy} className="w-full">
            {busy ? '稍等…' : mode === 'login' ? '登录' : '注册'}
          </Button>

          <button
            type="button"
            onClick={() => {
              setMode(mode === 'login' ? 'register' : 'login')
              setError(null)
            }}
            className="w-full text-center text-sm text-muted-foreground hover:text-foreground"
          >
            {mode === 'login' ? '还没有账号？注册一个' : '已经有账号？去登录'}
          </button>
        </div>
      </form>
    </div>
  )
}

