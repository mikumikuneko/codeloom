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
 * 确认框：一件**没有回收站**的事，或者会动到**共享的那份**的事，先问一句再动手。
 *
 * <p>默认的红色确认键是给前者的；后者（比如"合进主干"）传
 * {@code confirmVariant="default"} —— 它没有把东西删掉，只是会让对方也看到。
 *
 * <h2>为什么用它，而不是浏览器的 confirm</h2>
 * 那个弹窗是浏览器自己的：样式和这个界面无关、位置贴在窗口最上面、而且会把整个页面冻住。
 * 一个界面上只有它长成那样，看的人第一反应是"这里没做完"。
 *
 * <h2>它只问"要不要"，不解释"为什么"</h2>
 * 所以文案就三样：动的是哪件东西（标题）、动了会怎样（一句后果）、两个按钮。
 * 刻意不往里塞细节 —— 比如"目录里有 N 个文件"，那要多问一次后端，
 * 而且会把注意力引到数字上，而这里要回答的只有"要不要"。
 *
 * <h2>成功之后关不关，由调用方决定</h2>
 * {@code onConfirm} 返回一句错误（就停在框里显示起来），或者 {@code null} 表示成功。
 * 有些调用方成功之后还有别的事要做（清掉选中、刷新列表），把关框写在它们那儿更顺 ——
 * 见 FileTree、ProvidersPage、SessionHistoryDialog 三处用法。
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
   * 确认键的样子。默认红色 —— 这个框大多数时候问的是"删了就没有了"。
   * 普通确认（比如"合进主干"）传 {@code default}：它会动到共享的那份，但不是不可逆。
   */
  confirmVariant?: 'default' | 'destructive'
  /** 返回一句给人看的错误，或者 null 表示成功。成功之后要不要关，由调用方决定。 */
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
          {/* 两个按钮都写明 type="button"：这个框可能被渲染在别处的 form 里，
              而对话框是 portal 出去的，靠"它不在 form 的 DOM 子树里"太隐晦了 */}
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
