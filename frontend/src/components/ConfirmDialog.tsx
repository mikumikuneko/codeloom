import { useState } from 'react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

/**
 * 确认框：一件没有回收站的事，或者会动到共享那份的事，先问一句再动手。
 *
 * 默认红色确认键给前者；后者（如"合进主干"）传 `confirmVariant="default"`：它没有
 * 删除东西，只是会让对方也看到。
 *
 * 不用浏览器 `confirm`：样式与界面无关、位置贴在窗口最上面，而且会冻住整个页面。
 *
 * 只问"要不要"，不解释"为什么"：文案三样——动的是哪件东西（标题）、动了会怎样
 * （一句后果）、两个按钮。不塞细节（如"目录里有 N 个文件"），那要多问一次后端，
 * 也会把注意力引到数字上。
 *
 * `onConfirm` 返回一句错误（停在框里显示）或 null 表示成功；成功之后关不关由调用方
 * 决定，有些调用方成功之后还要清选中、刷新列表。
 */
export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  confirmVariant = 'destructive',
  onConfirm,
  onClose,
}: {
  title: string
  description: string
  confirmLabel: string
  /**
   * 确认键的样子。默认红色：这个框多数时候问的是"删了就没有了"。
   * 普通确认（如"合进主干"）传 `default`：它会动到共享那份，但不是不可逆。
   */
  confirmVariant?: 'default' | 'destructive'
  /** 返回一句给人看的错误，或 null 表示成功。成功之后是否关闭由调用方决定。 */
  onConfirm: () => Promise<string | null>
  onClose: () => void
}) {
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run() {
    setBusy(true)
    setError(null)
    const problem = await onConfirm()
    if (problem) {
      setError(problem)
    }
    setBusy(false)
  }

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>

        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}

        <DialogFooter>
          {/* 两个按钮都写明 type="button"：这个框可能渲染在别处的 form 里，
              而对话框是 portal 出去的，靠"它不在 form 的 DOM 子树里"太隐晦。 */}
          <Button type="button" variant="ghost" onClick={onClose} disabled={busy}>
            算了
          </Button>
          <Button
            type="button"
            variant={confirmVariant}
            onClick={() => void run()}
            disabled={busy}
          >
            {busy ? `正在${confirmLabel}…` : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
