import { useCallback, useEffect, useRef } from 'react'

/**
 * 两次按键之间最多隔多久算连按两下。
 *
 * 800ms 比人的反应时间（约 250ms）长得多，比"想一下再按"（一两秒）短，能把
 * "连按"和"又按了一次"分开，而不需要界面提示"再按一次"。
 */
const DOUBLE_PRESS_MS = 800

/**
 * 连按两下才触发的那件事。
 *
 * 唯一用法是连按两下 Esc 打开回滚。单独一下什么都不做：第一下只进入待定，800ms
 * 内没有第二下就作废。
 *
 * @param onDoublePress 连按两下时执行
 */
export function useDoublePress(onDoublePress: () => void) {
  const lastPressAt = useRef(0)
  const waiting = useRef<number | undefined>(undefined)

  const clear = useCallback(() => {
    if (waiting.current !== undefined) {
      window.clearTimeout(waiting.current)
      waiting.current = undefined
    }
  }, [])

  // 组件卸载时清掉定时器，否则它会在已不在的组件上跑回调。
  useEffect(() => clear, [clear])

  return useCallback(() => {
    const now = Date.now()
    // 两个条件都要：间隔够短，而且真的还在待定里。只看间隔会把中间已被别的事
    // 复位过的按键算成连按。
    const isDouble = now - lastPressAt.current <= DOUBLE_PRESS_MS && waiting.current !== undefined

    if (isDouble) {
      clear()
      onDoublePress()
    } else {
      clear()
      waiting.current = window.setTimeout(() => {
        waiting.current = undefined
      }, DOUBLE_PRESS_MS)
    }
    lastPressAt.current = now
  }, [onDoublePress, clear])
}
