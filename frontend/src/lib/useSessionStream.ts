import { useEffect, useRef, useState } from 'react'

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
 * <h2>只用 SSE，**不再单独拉一次历史**</h2>
 * 服务端那条通道自己就会"先补历史、再接上实时"（见 `SessionStreamService`，
 * `replay-batch` 那条配置就是为它存在的）。所以这里一个连接就够 ——
 * 再走一次 REST 拉历史的话，两批数据要在前端按 seq 去重，而那是白花的力气。
 *
 * <p>断线更省事：`EventSource` 会自己重连，并带上浏览器记的 `Last-Event-ID`，
 * 服务端从那儿接着推。**这两件事都是浏览器做的，我们一行都不用写** ——
 * 这正是这个项目选会话 cookie 而不是 Bearer 的那个理由的另一面
 *（`EventSource` 连自定义请求头都发不了，更别说自己管游标）。
 *
 * <h2>一帧不等于一次渲染</h2>
 * 模型吐字是一条一条来的：一秒钟可能有几百帧。**每一帧都 setState 的话，
 * 渲染次数就等于 token 数** —— 而人眼一秒只能看六十个画面，
 * 多出来的那些渲染全是白做的（还要把几十条消息重新排一遍版）。
 *
 * <p>所以帧先进一个队列，**每个显示帧最多渲染一次**。这样做的效果不只是省 CPU：
 * 流式正文每渲染一次就要过一遍 markdown 解析器，而它在文字长得越长时越贵 ——
 * 减少渲染次数同时也把那条增长曲线压平了。
 *
 * <p>用 `requestAnimationFrame` 而不是定时器：它跟着**显示器的刷新节奏**走。
 * 120Hz 的屏上它给 120 次，省电模式下降下来的屏上也跟着降 —— 不需要我们猜一个周期。
 * 标签页被切到后台时浏览器会停掉它，那时也没人在看；切回来会把积压的帧一次处理完。
 *
 * <h2>切换会话要重置</h2>
 * 换了会话就是换了一整个事实流，旧的条目一条都不该留着。
 */
export interface SessionStream {
  items: StreamItem[]
  streaming: Streaming
  /** 断了。**正常时不显示任何状态** —— 一个常绿的"已连接"只是在占地方 */
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

    /** 还没折进状态的帧。**必须是这个 effect 的局部变量** —— 换会话时它得跟着作废 */
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
      // 一帧一帧折，但只触发**一次**渲染：reduce 把整批算完再交出去
      setStreaming((current) => batch.reduce(foldStreaming, current))
      setItems((current) => batch.reduce(fold, current))
    }

    const source = new EventSource(`/api/sessions/${sessionId}/stream`)

    source.onmessage = (event) => {
      let frame: Frame
      try {
        frame = JSON.parse(event.data as string) as Frame
      } catch {
        // 一帧读不出来不该把整个流掐了：后面还有几千帧要收
        return
      }
      pending.push(frame)
      // 已经排过了就不再排 —— 这一行是"一帧一次渲染"的全部实现
      scheduled ??= requestAnimationFrame(render)
    }

    source.onerror = () => {
      // EventSource 会自己重连，这里只负责**告诉用户现在断了**。
      // 不用手动重建连接 —— 那样反而要自己管 Last-Event-ID
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

/** 供组件判断"这一条是不是正在长出来"用的 —— 只是给一个稳定的引用，避免每次新建对象。 */
export function useStreamingRef(streaming: Streaming) {
  const ref = useRef(streaming)
  ref.current = streaming
  return ref
}
