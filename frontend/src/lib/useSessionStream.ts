import { useEffect, useState } from 'react'

import {
  EMPTY_STREAMING,
  fold,
  foldStreaming,
  type Frame,
  type StreamItem,
  type Streaming,
} from '@/lib/sessionStream'

/**
 * 一条会话的事件流。
 *
 * 只用 SSE，不另拉历史：服务端通道会先补历史再接实时（`SessionStreamService`
 * 的 `replay-batch`），一个连接就够；再走 REST 还要按 seq 去重。断线由
 * `EventSource` 自己重连并带上 `Last-Event-ID`，浏览器已经做了。这也是选会话
 * cookie 而不是 Bearer 的另一面：`EventSource` 连自定义请求头都发不了。
 *
 * 一帧不等于一次渲染：模型可能一秒吐几百帧，每帧 setState 会让渲染次数等于 token
 * 数。帧先进队列，每个显示帧最多渲染一次；流式正文每次都要过 markdown 解析器，
 * 减少渲染也压平了那条增长曲线。
 *
 * 用 `requestAnimationFrame` 而不是定时器：它跟着显示器刷新节奏走，后台标签页会
 * 停掉它，切回来把积压的帧一次处理完。
 *
 * 切换会话要重置：换了会话就是换了一整个事实流，旧条目一条都不该留。
 */
export interface SessionStream {
  items: StreamItem[]
  streaming: Streaming
  /** 断了。正常时不显示任何状态：常绿的"已连接"只是占地方 */
  disconnected: boolean
  /** 这条会话一条事件都还没有（新会话的正常状态，不是错误） */
  empty: boolean
}

export function useSessionStream(sessionId: string | null): SessionStream {
  const [items, setItems] = useState<StreamItem[]>([])
  const [streaming, setStreaming] = useState<Streaming>(EMPTY_STREAMING)
  const [disconnected, setDisconnected] = useState(false)
  const [empty, setEmpty] = useState(true)

  useEffect(() => {
    if (!sessionId) {
      setItems([])
      setStreaming(EMPTY_STREAMING)
      setEmpty(true)
      return
    }

    setItems([])
    setStreaming(EMPTY_STREAMING)
    setEmpty(true)
    setDisconnected(false)

    /** 还没折进状态的帧。必须是这个 effect 的局部变量：换会话时要跟着作废 */
    let pending: Frame[] = []
    let scheduled: number | undefined

    function render() {
      scheduled = undefined
      const batch = pending
      pending = []
      if (batch.length === 0) {
        return
      }
      setEmpty(false)
      // 一帧一帧折，但只触发一次渲染：reduce 把整批算完再交出去。
      setStreaming((current) => batch.reduce(foldStreaming, current))
      setItems((current) => batch.reduce(fold, current))
    }

    const source = new EventSource(`/api/sessions/${sessionId}/stream`)

    source.onmessage = (event) => {
      let frame: Frame
      try {
        frame = JSON.parse(event.data as string) as Frame
      } catch {
        // 一帧读不出来不该掐掉整个流：后面还有几千帧要收。
        return
      }
      pending.push(frame)
      // 已经排过就不再排：这一行是"一帧一次渲染"的全部实现。
      scheduled ??= requestAnimationFrame(render)
    }

    source.onerror = () => {
      // EventSource 会自己重连，这里只告诉用户现在断了；不手动重建连接，
      // 否则要自己管 Last-Event-ID。
      setDisconnected(true)
    }
    source.onopen = () => setDisconnected(false)

    return () => {
      source.close()
      if (scheduled !== undefined) {
        cancelAnimationFrame(scheduled)
      }
    }
  }, [sessionId])

  return { items, streaming, disconnected, empty }
}
