import { useCallback, useEffect, useRef, useState } from 'react'

import { errorText } from '@/lib/api'

/**
 * 「拉一次数据，管好加载中 / 出错 / 重来」—— 每个页面都要的那三件事。
 *
 * <h2>为什么要抽出来</h2>
 * 不抽的话每个页面都要写三个 `useState` 加一个 `useEffect`，而它们总有一处会写漏：
 * 漏掉 loading 就是一片空白、漏掉 error 就是静默失败、漏掉取消就是切页面之后
 * 对着已经卸载的组件 setState。
 *
 * <h2>为什么带 cancelled 标志</h2>
 * 用户切走之后请求才回来，是这类页面最常见的竞态。少了它 React 会警告、
 * 更糟的是**后回来的旧数据会盖掉新数据**（快速在两个项目之间来回点时就会出现）。
 */
interface AsyncState<T> {
  data: T | null
  error: string | null
  loading: boolean
  /**
   * 再拉一次。
   *
   * <p>{@code quiet} 是给**轮询**用的：不把 {@code loading} 置真、失败也不写
   * {@code error}。少了这个开关，工作区每 5 秒就会闪一次"正在打开…"，
   * 一次网络抖动还会把整页换成一句报错 —— 而手上那份旧数据本来还能用。
   */
  reload: (quiet?: boolean) => void
}

export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): AsyncState<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [nonce, setNonce] = useState(0)
  // 下一次运行是不是安静的那一种。用 ref 而不是 state：它不该触发渲染
  const quiet = useRef(false)

  // load 每次渲染都是新的函数，所以依赖里不能放它 —— 那样会无限循环。
  // 调用方通过 deps 声明"什么时候该重新拉"
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps)

  useEffect(() => {
    let cancelled = false
    const silent = quiet.current
    quiet.current = false
    if (!silent) {
      setLoading(true)
      setError(null)
    }
    run()
      .then((value) => {
        if (cancelled) return
        // 安静的那一次成功了，说明刚才的读取问题是暂时的：把旧错误收掉
        if (silent) setError(null)
        // **内容没变就不要换引用。** 轮询每 5 秒回来一份新对象，换引用的话
        // `nameOf` 那类缓存会跟着重建，整条会话流于是每 5 秒重渲染一遍 ——
        // 而屏幕上其实一个字都没变。
        setData((current) =>
          current !== null && JSON.stringify(current) === JSON.stringify(value)
            ? current
            : value,
        )
      })
      .catch((e: unknown) => {
        // 安静的那一次失败了就当没发生：旧数据还在，下一次轮询会再试
        if (!cancelled && !silent) setError(errorText(e))
      })
      .finally(() => {
        if (!cancelled && !silent) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [run, nonce])

  return {
    data,
    error,
    loading,
    // `=== true` 而不是直接赋值：这个函数可能被当成事件处理器传下去，
    // 那时第一个参数是一个 MouseEvent —— 而它是真值，会把一次正常重拉变成"安静"的
    reload: (quietReload = false) => {
      quiet.current = quietReload === true
      setNonce((n) => n + 1)
    },
  }
}
