import { Check, Copy } from 'lucide-react'
import type { RefObject } from 'react'

import { useCopy } from '@/lib/useCopy'

/**
 * 「把这块代码复制走」。
 *
 * 它读屏幕上那段文字的 `textContent`，不是调用方传进来的字符串：传字符串要再准备
 * 一份源码，而那份与屏幕显示的迟早不一致（高亮把 token 拆成 `span`、行号单独一列、
 * 长文件只显示前 3000 行）。从容器取文本，复制到的一定是人看见的那份。
 *
 * 点了要变一下字：复制没有可见后果，不说一声就分不清成功还是没点上。说一句
 * "已复制"，1.2 秒后自己变回来，不用弹层。
 */
export function CopyButton({
  contentRef,
  label = '复制',
  className = '',
}: {
  /** 要看的那块内容；复制的是它的 `textContent` */
  contentRef: RefObject<HTMLElement | null>
  label?: string
  className?: string
}) {
  // 复制的反馈时机与邀请链接那处共用一份（见 useCopy）。
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
      // opacity-0 而不是 hidden：键盘仍能 Tab 到它，聚焦时自己显形。
      className={`flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-loom-faint opacity-0 transition-opacity hover:text-foreground focus-visible:opacity-100 group-hover/code:opacity-100 ${className}`}
    >
      {copied ? <Check className="size-3" /> : <Copy className="size-3" />}
      {copied ? '已复制' : label}
    </button>
  )
}
