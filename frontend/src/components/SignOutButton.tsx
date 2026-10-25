import { LogOut } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/stores/auth'

/**
 * 登出确认框。按钮和菜单两条入口共用；登出本身可逆（重新输一次账号密码就回来），
 * 确认键不用红色。
 */
export function SignOutConfirm({ onClose }: { onClose: () => void }) {
  const logout = useAuth((state) => state.logout)
  const navigate = useNavigate()

  return (
    <ConfirmDialog
      title="登出"
      description="退出后要重新输入账号和密码。"
      confirmLabel="登出"
      confirmVariant="default"
      onClose={onClose}
      onConfirm={async () => {
        await logout()
        navigate('/login', { replace: true })
        return null
      }}
    />
  )
}

/**
 * 登出键。项目列表和供应商页共用这个外壳；工作区的顶栏换成了账户菜单，只复用
 * {@link SignOutConfirm}。
 */
export function SignOutButton() {
  const [confirming, setConfirming] = useState(false)

  return (
    <>
      <Button
        variant="ghost"
        size="icon-sm"
        title="登出"
        className="text-muted-foreground"
        onClick={() => setConfirming(true)}
      >
        <LogOut className="size-4" />
      </Button>
      {confirming && <SignOutConfirm onClose={() => setConfirming(false)} />}
    </>
  )
}
