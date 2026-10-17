import { useCallback, useEffect, useRef, useState } from 'react'

import { chat, type ChatMessage } from '@/lib/api'
import { readChatOutbox, writeChatOutbox, type KeyValueStore } from '@/lib/chatOutbox'

/** `sessionStorage` 在隐私模式/被禁用时可能连读取都抛 —— 拿不到就退回"只在内存里"。 */
function safeSessionStore(): KeyValueStore | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage
  } catch {
    return null
  }
}

/**
 * 项目聊天室的连接。
 *
 * <h2>历史走 REST，新消息走 WebSocket</h2>
 * 这是两条不同的通道，因为它们回答的是不同的问题：
 * <ul>
 *   <li><b>打开页面时</b>要的是"之前聊过什么" —— 一次 HTTP 拉回来最简单</li>
 *   <li><b>之后</b>要的是"现在有人说了一句" —— 那才是长连接该干的事</li>
 * </ul>
 * 试图用一条通道解决两件事，就会得到"连上之后再补历史"这种要处理时序的写法。
 *
 * <h2>为什么要按 id 去重</h2>
 * 拉历史和建连接之间有一段窗口：那时候别人说的话，**既在历史里、也会从广播里来一次**。
 * 不去重的话它会显示两遍 —— 而且是偶发的，只在"刚好有人在那几十毫秒里说话"时出现。
 *
 * <h2>为什么自己发的消息不本地追加</h2>
 * 因为服务端**把广播发回给发送者自己**（见 `ChatWebSocketHandler` 的类注释：前端只有
 * 一条渲染路径）。本地也追加一遍的话，就会依赖"去重"来兜住 —— 而那是本末倒置：
 * 去重是为了兜住上面那个时间窗口，不是为了兜住我们自己。
 */
interface ChatConnection {
  messages: ChatMessage[]
  /** 连接断了。正常时**不显示任何状态** —— 一个常绿的"已连接"只是在占地方 */
  disconnected: boolean
  /**
   * 这个项目的**第一批历史**到了没有。
   *
   * <p>未读计数靠它：历史那一批不该算"新消息"。等它到了再开始数，
   * 否则切换项目/重新挂载时那一屏旧消息会凭空变成一个未读点。
   */
  loaded: boolean
  send: (text: string) => void
}

/**
 * 第一次重连等多久，以及最长等多久。
 *
 * <h2>为什么要退避，而且为什么要封顶</h2>
 * 固定间隔、永不放弃的写法看起来无害，实际会变成**日志风暴**：后端没起、项目被删、
 * 认证过期时，浏览器每 1.5 秒试一次，**永远不会成功也永远不会停** ——
 * vite 的代理每失败一次就打一整段堆栈，几秒钟就把控制台刷满了，
 * 而真正要看的那条错误反而被淹掉。
 *
 * <p>退避到 30 秒之后，同样的故障在控制台上是一分钟两条，而不是四十条。
 *
 * <h2>为什么封顶而不是"试几次就放弃"</h2>
 * 因为**浏览器不告诉我们握手为什么失败**：`WebSocket` 的 `onclose` 只给一个 1006，
 * 拿不到 401 还是 404。所以"这个错该不该重试"在客户端**分不出来** ——
 * 那就不能放弃（后端重启是常态，放弃意味着要用户手动刷新），只能慢下来。
 */
const RECONNECT_MIN_MS = 1000
const RECONNECT_MAX_MS = 30_000

export function useProjectChat(projectId: string): ChatConnection {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [disconnected, setDisconnected] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const socket = useRef<WebSocket | null>(null)
  const delay = useRef(RECONNECT_MIN_MS)
  /** `sessionStorage` 的句柄。拿不到时 outbox 退回只在内存里（见 safeSessionStore） */
  const store = safeSessionStore()
  /**
   * 连接还没 OPEN 时发的话。
   *
   * <p>从前面这里直接 `return` —— 而那是一个**静默的丢消息**：输入框清空了、
   * 界面上一条都不多、历史里也没有。换项目、刷新页面都会让 ChatPanel 重新挂载，
   * 所以"刚打开聊天就打字"不是边角情况。
   *
   * <p>排队到 onopen 再发。代价是消息可能晚几百毫秒到；但"晚到"比"没到"好，
   * 而人看不出这几百毫秒。
   *
   * <p>队列**按项目写进 `sessionStorage`**（见 chatOutbox）：刷新一次（人连不上时最常
   * 做的动作）不该把刚打的话抹掉。换项目时读的是这个项目自己那一份，不会串台。
   */
  const outbox = useRef<string[]>([])

  const merge = useCallback((incoming: ChatMessage[]) => {
    setMessages((current) => {
      const seen = new Set(current.map((m) => m.id))
      const fresh = incoming.filter((m) => !seen.has(m.id))
      return fresh.length === 0 ? current : [...current, ...fresh]
    })
  }, [])

  // 历史拉一次就够 —— 依赖里只放 projectId
  useEffect(() => {
    let cancelled = false
    // 换项目：上一个项目的消息不属于这里；**没发出去的话按项目分别存着**，
    // 切回来时会重新读出来（见 chatOutbox）
    setMessages([])
    setLoaded(false)
    outbox.current = store ? readChatOutbox(store, projectId) : []
    chat
      .history(projectId)
      .then((history) => {
        if (!cancelled) setMessages(history)
      })
      .catch(() => {
        // 历史拉不到不该block住实时的那条通道：连接照建，只是前面那段看不到。
        // 所以这里**不设 error 状态** —— 那会把"聊天室不可用"和"看不到旧消息"混成一件事
      })
      .finally(() => {
        if (!cancelled) setLoaded(true)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, store])

  useEffect(() => {
    let closed = false
    let timer: ReturnType<typeof setTimeout> | undefined
    // 切到别的项目时重新从最短间隔开始 —— 上一个项目的故障不该让这个项目也等 30 秒
    delay.current = RECONNECT_MIN_MS

    function connect() {
      // 同源：dev 代理已经把 /ws/project-chat 转过去了，**cookie 由浏览器自动带上** ——
      // 这正是这个项目用会话 cookie 而不是 Bearer 的原因（WebSocket 握手带不了自定义头）
      const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(
        `${scheme}://${window.location.host}/ws/project-chat?projectId=${encodeURIComponent(projectId)}`,
      )
      socket.current = ws

      ws.onopen = () => {
        setDisconnected(false)
        // 连上了就把退避重置回最短 —— 否则一次偶发的断线会让之后每次重连都等 30 秒
        delay.current = RECONNECT_MIN_MS
        // 把连上之前打的那些话补发出去（见 outbox 的注释）。发完才清掉落盘的那一份 ——
        // 没发出去就刷新/关页的话，下一次打开还会再试
        const queued = outbox.current.splice(0)
        for (const text of queued) {
          ws.send(JSON.stringify({ text, anchorEventSeq: null, anchorText: null }))
        }
        if (store) {
          writeChatOutbox(store, projectId, outbox.current)
        }
      }
      ws.onmessage = (event) => {
        try {
          merge([JSON.parse(event.data as string) as ChatMessage])
        } catch {
          // 一条读不出来的帧不该把连接掐了，也不该在界面上冒出一个错 ——
          // 它多半是服务端回的 {"error": "..."}（收不了的消息），忽略即可
        }
      }
      ws.onclose = () => {
        if (closed) return
        setDisconnected(true)
        // 自己重连。服务端**不会**替我们重连，而断了之后一直不说话是用户看得见的坏。
        // 间隔逐次翻倍、封顶 30 秒 —— 见上面那段注释
        const wait = delay.current
        delay.current = Math.min(wait * 2, RECONNECT_MAX_MS)
        timer = setTimeout(connect, wait)
      }
      // onerror 之后 onclose 一定会来，所以重连只挂在 onclose 上 ——
      // 两处都挂会连出两条连接
      ws.onerror = () => ws.close()
    }

    connect()
    return () => {
      closed = true
      if (timer !== undefined) clearTimeout(timer)
      socket.current?.close()
      socket.current = null
    }
  }, [projectId, merge, store])

  const send = useCallback((text: string) => {
    // 锚点那两个字段照旧发 null：后端的列和接口都还在（引用那套界面撤了，
    // 但数据这一层留着），一个普通消息本来就是"没有锚点"
    const ws = socket.current
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ text, anchorEventSeq: null, anchorText: null }))
      return
    }
    // 还没连上 —— 排着，onopen 时补发。**不要在这里静默 return**
    outbox.current.push(text)
    // 同时落到这个项目的草稿键里：刷新后还能补发（见 chatOutbox）
    if (store) {
      writeChatOutbox(store, projectId, outbox.current)
    }
  }, [projectId, store])

  return { messages, disconnected, loaded, send }
}
