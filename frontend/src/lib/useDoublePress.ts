import { useCallback, useEffect, useRef } from 'react'

/**
 * 两次按键之间最多隔多久算"连按两下"。
 *
 * <p>800ms 是从 Claude Code 抄的（它那个 hook 里就是这个值），而它经得起推敲：
 * 比人的反应时间（约 250ms）长得多，比"想一下再按"（一两秒）短。
 * 所以它能把"连按"和"我又按了一次"分开，而不用让界面弹一个"再按一次"的提示。
 */
export const DOUBLE_PRESS_MS = 800

/**
 * 连按两下才触发的那件事。
 *
 * <h2>第一次是立刻生效的，不是等着的</h2>
 * 这是这个 hook 最要紧的性质：**第一次按键马上跑 {@code onFirstPress}**，
 * 不推迟到超时之后再决定。因为用它的地方（打断一轮、清空输入框）都要求"按了就生效" ——
 * 要是等 800ms 才动，那 800ms 里界面是没反应的，而人只会以为这个键坏了。
 *
 * <p>Claude Code 也是这个形状（第一次执行 + 进"待定"，超时自己失效，待定期间再来一次就是连按）。
 *
 * @param onDoublePress 连按两下时跑它
 * @param onFirstPress  第一下就跑它（可选 —— Claude Code 在"打开回滚"那条路上传的是空函数，
 *                      因为没在跑的时候按 Esc 本来就不该发生任何事）
 */
export function useDoublePress(onDoublePress: () => void, onFirstPress?: () => void) {
  const lastPressAt = useRef(0)
  const waiting = useRef<number | undefined>(undefined)

  const clear = useCallback(() => {
    if (waiting.current !== undefined) {
      window.clearTimeout(waiting.current)
      waiting.current = undefined
    }
  }, [])

  // 组件没了就把定时器带走 —— 留着的话它会在一个已经不在的组件上跑回调
  useEffect(() => clear, [clear])

  return useCallback(() => {
    const now = Date.now()
    // 判据里 **两个条件都要**：间隔够短，而且真的还在"待定"里。
    // 只看间隔的话，两次相距 790ms 但中间已经被别的事复位过的按键也会被算成连按
    const isDouble = now - lastPressAt.current <= DOUBLE_PRESS_MS && waiting.current !== undefined

    if (isDouble) {
      clear()
      onDoublePress()
    } else {
      onFirstPress?.()
      clear()
      waiting.current = window.setTimeout(() => {
        waiting.current = undefined
      }, DOUBLE_PRESS_MS)
    }
    lastPressAt.current = now
  }, [onDoublePress, onFirstPress, clear])
}
