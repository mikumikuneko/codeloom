import { useCallback, useEffect, useState } from 'react'

import { ApiError } from '@/lib/api'

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
export interface AsyncState<T> {
  data: T | null
  error: string | null
  loading: boolean
  reload: () => void
}

export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): AsyncState<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [nonce, setNonce] = useState(0)

  // load 每次渲染都是新的函数，所以依赖里不能放它 —— 那样会无限循环。
  // 调用方通过 deps 声明"什么时候该重新拉"
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    run()
      .then((value) => {
        if (!cancelled) setData(value)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : '连不上服务器')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [run, nonce])

  return { data, error, loading, reload: () => setNonce((n) => n + 1) }
}
