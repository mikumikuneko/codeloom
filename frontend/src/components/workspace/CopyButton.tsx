import { Check, Copy } from 'lucide-react'
import type { RefObject } from 'react'

import { useCopy } from '@/lib/useCopy'

/**
 * 「把这块代码复制走」。
 *
 * <h2>它读的是**屏幕上那段文字**，不是传进来的字符串</h2>
 * 传字符串的话，调用方得把源码再准备一份 —— 而那份和屏幕上显示的迟早会不一致
 * （高亮把 token 拆成了 span、行号是单独一列、长文件只显示前 3000 行）。
 * 从容器里取 `textContent`，复制到的就**一定是人看见的那份** ——
 * 而那正是他要的：屏幕上选不中、或者选中了会带行号，才需要这个按钮。
 *
 * <h2>为什么点了要变一下字</h2>
 * 复制**没有可见的后果**。不吭一声的话，人不知道是复制成功了还是按钮没点上，
 * 只能去别处粘贴一次来验证。说一句「已复制」，一秒后自己变回来 ——
 * 不用弹层，也不用谁去关它。
 */
export function CopyButton({
  contentRef,
  label = '复制',
  className = '',
}: {
  /** 要看的那块内容。复制的是它的 `textContent` */
  contentRef: RefObject<HTMLElement | null>
  label?: string
  className?: string
}) {
  // 复制的反馈时机和邀请链接那一处**共用一份**（见 useCopy）
  const [copied, copy] = useCopy()

  async function copyContent() {
    const text = contentRef.current?.textContent ?? ''
    if (text !== '') {
      await copy(text)
    }
  }

  return (
    <button
      type="button"
      onClick={() => void copyContent()}
      // `opacity-0` 而不是 `hidden`：键盘仍然能 Tab 到它，聚焦时它自己会显形
      className={`flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-loom-faint opacity-0 transition-opacity hover:text-foreground focus-visible:opacity-100 group-hover/code:opacity-100 ${className}`}
    >
      {copied ? <Check className="size-3" /> : <Copy className="size-3" />}
      {copied ? '已复制' : label}
    </button>
  )
}
