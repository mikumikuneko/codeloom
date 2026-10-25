import { useCallback, useEffect, useRef, useState } from 'react'

import { errorText } from '@/lib/api'

/**
 * 拉一次数据，并管好加载中、出错和重来。
 *
 * 抽出来是因为每个页面都要这三个状态；各写一遍总有一处写漏：漏掉 loading 是空白，
 * 漏掉 error 是静默失败，漏掉取消是切页面后对已卸载的组件 setState。
 *
 * 带取消标志：切走之后请求才回来是常见竞态，后回来的旧数据会盖掉新数据。
 */
interface AsyncState<T> {
  data: T | null
  error: string | null
  loading: boolean
  /**
   * 再拉一次。
   *
   * `quiet` 给轮询用：不置 loading、失败也不写 error。否则工作区每 5 秒闪一次
   * "正在打开…"，一次网络抖动还会把整页换成报错，而旧数据本来还能用。
   */
  reload: (quiet?: boolean) => void
}

export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): AsyncState<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [nonce, setNonce] = useState(0)
  // 下一次运行是否为安静方式。用 ref 而不是 state：它不该触发渲染。
  const quiet = useRef(false)
  /**
   * 有没有一次"会管 loading"的读取还没结束。
   *
   * 安静重拉要据此让路：它自己不碰 loading，而它一插进来就会把在飞的那次取消掉，
   * 被取消的那次 finally 不跑，loading 就永远停在 true —— 整页卡在"正在打开…"，
   * 只有刷新才能恢复。切回浏览器标签页时 focus 和 visibilitychange 都会触发一次
   * 安静重拉，正好撞上打开项目的那次请求，就是这个样子。
   */
  const busy = useRef(true)

  // load 每次渲染都是新函数，依赖里不能放它，否则无限循环。
  // 调用方用 deps 声明"什么时候该重新拉"。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps)

  useEffect(() => {
    let cancelled = false
    const silent = quiet.current
    quiet.current = false
    if (!silent) {
      busy.current = true
      setLoading(true)
      setError(null)
    }
    run()
      .then((value) => {
        if (cancelled) return
        // 安静的那次成功，说明刚才的读取问题是暂时的：收掉旧错误。
        if (silent) setError(null)
        // 内容没变就不换引用：轮询每 5 秒回来一份新对象，换引用会让 `nameOf`
        // 那类缓存重建，整条会话流每 5 秒重渲染一遍，而屏幕上什么都没变。
        setData((current) =>
          current !== null && JSON.stringify(current) === JSON.stringify(value)
            ? current
            : value,
        )
      })
      .catch((e: unknown) => {
        // 安静的那次失败当没发生：旧数据还在，下次轮询再试。
        if (!cancelled && !silent) setError(errorText(e))
      })
      .finally(() => {
        if (!cancelled && !silent) {
          busy.current = false
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [run, nonce])

  return {
    data,
    error,
    loading,
    // `=== true` 而不是直接赋值：这个函数可能被当事件处理器传下去，第一个参数
    // 是 MouseEvent（真值），会把一次正常重拉变成安静方式。
    reload: (quietReload = false) => {
      const silent = quietReload === true
      // 在飞的那次本来就会带回新数据，也由它负责收 loading；安静重拉插进去只会
      // 把它取消掉，然后留下一个永远收不掉的加载态。跳过；等它落地，下一次轮询
      // 自然会再来。
      if (silent && busy.current) return
      quiet.current = silent
      setNonce((n) => n + 1)
    },
  }
}
