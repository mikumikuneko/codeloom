import { useState, type FormEvent } from 'react'
import { Navigate, useLocation } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ApiError } from '@/lib/api'
import { useAuth } from '@/stores/auth'

/**
 * 登录 / 注册。
 *
 * <h2>为什么是同一个页面上的一个开关</h2>
 * 因为这个项目里这两个动作的用户是**同一个人**：一个新用户进来，他先要有一个账号，
 * 然后立刻要用它。拆成两个路由意味着他第一次必须先猜对"我该点哪个"，
 * 而猜错的表现是"账号或密码不对"—— 一个把人往错误方向带的提示。
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

  // 已经登录了就别停在登录页上 —— 刷新或者手敲 URL 都会走到这儿
  if (status === 'authenticated') {
    // 回到当初被踢出去的那个页面（见 RequireAuth 里怎么把来源塞进 state）
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
      // 成功之后**不在这里跳转**：状态一变，上面的 `<Navigate>` 就接手了。
      // 两处都跳的话会有一次"跳两次"的闪烁，而且目标是哪个变得说不清
    } catch (e) {
      // 后端的 detail 是特意写给调用方看的（"这个账号已被占用"/"账号或密码不对"），
      // 直接显示它 —— 自己再编一套文案就等于把后端的分类丢掉
      setError(e instanceof ApiError ? e.message : '连不上服务器，稍后再试')
    } finally {
      setBusy(false)
    }
  }

  return (
    // 这里改过一次，理由记在这儿：从前**刻意不给卡片**，因为"盒子比内容重，
    // 那是最典型的模板感来源"。
    //
    // 那条判断在**换令牌之后不成立了**：现在的卡面（`--card` #141418）只比页面底
    // （#0a0a0c）高一级、描边是 8% 白 —— 它是一层"浮起来的面"，不是当时那个
    // "带边框的圆角盒子"。而这一页没有卡片时的样子是：一小坨表单飘在一片纯黑中央
    //（前任走查里点过这条："标题没有品牌存在感"）。给一个面，它才有个落脚的地方。
    //
    // 配方跟列表页的卡片**一个字不差**（`rounded-xl border border-border bg-card`）——
    // 全站的"独立内容"就这一种材质
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
          <Field label="账号">
            <Input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </Field>

          <Field label="密码">
            <Input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              // 注册用 new-password、登录用 current-password：
              // 浏览器靠它决定"要不要提示存密码"，写错了会让人每次登录都被问一遍
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              required
            />
          </Field>

          {mode === 'register' && (
            <Field label="用户名" hint="别人看到的名字。留空就用账号">
              <Input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
            </Field>
          )}
        </div>

        {error && (
          // role="alert" 让读屏软件立刻念出来 —— 一个只靠颜色的错误提示
          // 对用读屏的人等于不存在
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

function Field({
  label,
  hint,
  children,
}: {
  label: string
  hint?: string
  children: React.ReactNode
}) {
  return (
    <label className="block space-y-1.5">
      <span className="text-sm text-foreground">{label}</span>
      {children}
      {hint && <span className="block text-xs text-muted-foreground">{hint}</span>}
    </label>
  )
}
