import { useEffect, useRef, useState } from 'react'

import { copyText } from '@/lib/clipboard'

/**
 * 复制一段文字，成功后亮一下再收回。
 *
 * 各调用点共用这一份，别让"已复制"的复位时机各处漂移。失败时不假装成功：没有
 * 剪贴板权限（非 https、用户拒绝）就保持未复制，弹红字更吵，而人再点一次就能确认。
 *
 * @param resetMs 反馈停留多久；默认 1.2 秒，够看见又不至于一直挂着
 */
export function useCopy(resetMs = 1200): [boolean, (text: string) => Promise<void>] {
  const [copied, setCopied] = useState(false)
  const timer = useRef<number | undefined>(undefined)

  // 组件卸载时清掉定时器，否则它会在已卸载的组件上 setState。
  useEffect(() => () => window.clearTimeout(timer.current), [])

  async function copy(text: string) {
    if (!(await copyText(text))) {
      setCopied(false)
      return
    }
    setCopied(true)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setCopied(false), resetMs)
  }

  return [copied, copy]
}
