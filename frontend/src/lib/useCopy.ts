import { useEffect, useRef, useState } from 'react'

/**
 * "复制一段文字，成功后亮一下再收回"。
 *
 * <h2>为什么要抽出来</h2>
 * 复制这件事出现在两处（代码块、邀请链接）。各写一份已经分叉了：一处 1.2 秒后
 * 自己复位，另一处永远停在"已复制"—— 同一个动作，一个像"完成"，一个像"卡住"。
 *
 * <p>失败时**不假装成功**：没有剪贴板权限（非 https、或用户拒绝）就保持未复制。
 * 弹一个红字反而更吵，而人再点一次就能确认。
 *
 * @param resetMs 反馈停留多久。默认 1.2 秒：够看见，又不至于一直挂着
 */
export function useCopy(resetMs = 1200): [boolean, (text: string) => Promise<void>] {
  const [copied, setCopied] = useState(false)
  const timer = useRef<number | undefined>(undefined)

  // 组件没了就把定时器带走 —— 留着的话它会在一个已经卸载的组件上 setState
  useEffect(() => () => window.clearTimeout(timer.current), [])

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      setCopied(false)
      return
    }
    setCopied(true)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setCopied(false), resetMs)
  }

  return [copied, copy]
}
