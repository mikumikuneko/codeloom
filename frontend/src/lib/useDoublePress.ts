import { useCallback, useEffect, useRef } from 'react'

/**
 * 两次按键之间最多隔多久算"连按两下"。
 *
 * <p>800ms 是从 Claude Code 抄的（它那个 hook 里就是这个值），而它经得起推敲：
 * 比人的反应时间（约 250ms）长得多，比"想一下再按"（一两秒）短。
 * 所以它能把"连按"和"我又按了一次"分开，而不用让界面弹一个"再按一次"的提示。
 */
const DOUBLE_PRESS_MS = 800

/**
 * 连按两下才触发的那件事。
 *
 * <h2>单独一下什么都不做</h2>
 * 唯一用法是"连按两下 Esc 打开回滚"。第一下只进入"待定"，800ms 内没有第二下就作废 ——
 * 因为单独按一下 Esc 本来就不该发生任何事。从前这里还有一个可选的
 * {@code onFirstPress}，但没有任何调用方传它，等于一条永远不会走到的分支。
 *
 * <p>Claude Code 的形状是"第一次执行 + 进待定"，差别在它的第一次有动作；
 * 我们这里没有，所以不保留那个参数。
 *
 * @param onDoublePress 连按两下时跑它
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
      clear()
      waiting.current = window.setTimeout(() => {
        waiting.current = undefined
      }, DOUBLE_PRESS_MS)
    }
    lastPressAt.current = now
  }, [onDoublePress, clear])
}
