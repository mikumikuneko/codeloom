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
 * 历史走 REST、新消息走 WebSocket：打开页面要的是"之前聊过什么"，一次 HTTP 最
 * 简单；之后要的是"现在有人说了一句"，那才是长连接的事。用一条通道解决两件事，
 * 会得到"连上之后再补历史"这种要处理时序的写法。
 *
 * 按 id 去重：拉历史和建连接之间有一段窗口，那时别人说的话既在历史里、也会从
 * 广播来一次，不去重会偶发显示两遍。
 *
 * 自己发的消息不本地追加：服务端会把广播发回给发送者，前端只有一条渲染路径。
 * 本地再追加会依赖去重兜住，而去重是为了兜住上面的时间窗口。两条通道与 cookie
 * 认证的取舍见
 * `docs/decisions/architecture/2026-09-24-two-realtime-channels-and-cookie-auth.md`。
 */
interface ChatConnection {
  messages: ChatMessage[]
  /** 连接断了。正常时不显示任何状态：常绿的"已连接"只是占地方 */
  disconnected: boolean
  /**
   * 这个项目的第一批历史是否到了。
   *
   * 未读计数靠它：历史那一批不该算新消息。等它到了再开始数，否则切换项目或重新
   * 挂载时那一屏旧消息会凭空变成未读点。
   */
  loaded: boolean
  send: (text: string) => void
}

/**
 * 第一次重连等多久，以及最长等多久。
 *
 * 需要退避并封顶：固定间隔、永不放弃会变成日志风暴。后端没起、项目被删、认证
 * 过期时，浏览器每秒试一次，永远不会成功也永远不会停；代理每失败一次打一整
 * 段堆栈，几秒就把控制台刷满，真正要看的那条错误反而被淹掉。退避到 30 秒后，
 * 同样故障在控制台上一分钟两条。
 *
 * 封顶而不是试几次就放弃：浏览器不告诉我们握手为什么失败（`onclose` 只给 1006，
 * 分不出 401 还是 404），客户端分不出该不该重试。后端重启是常态，放弃意味着
 * 用户要手动刷新，所以只能慢下来。
 */
const RECONNECT_MIN_MS = 1000
const RECONNECT_MAX_MS = 30_000

export function useProjectChat(projectId: string): ChatConnection {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [disconnected, setDisconnected] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const socket = useRef<WebSocket | null>(null)
  const delay = useRef(RECONNECT_MIN_MS)
  /** `sessionStorage` 的句柄；拿不到时 outbox 退回只在内存里（见 `safeSessionStore`） */
  const store = safeSessionStore()
  /**
   * 连接还没 OPEN 时发的话。
   *
   * 这里不能静默 return：那会丢消息（输入框清空、界面不增加、历史也没有）。换
   * 项目、刷新页面都会让 ChatPanel 重新挂载，"刚打开聊天就打字"不是边角情况。
   *
   * 排队到 onopen 再发，晚几百毫秒比没到好。队列按项目写进 `sessionStorage`
   * （见 chatOutbox）：刷新不该抹掉刚打的话，换项目读的是这个项目自己那份。
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
    // 换项目：上一个项目的消息不属于这里；没发出去的话按项目分别存着，
    // 切回来时会重新读出来（见 chatOutbox）。
    setMessages([])
    setLoaded(false)
    outbox.current = store ? readChatOutbox(store, projectId) : []
    chat
      .history(projectId)
      .then((history) => {
        if (!cancelled) setMessages(history)
      })
      .catch(() => {
        // 历史拉不到不应挡住实时通道：连接照建，只是前面那段看不到。这里不设
        // error，否则会把"聊天室不可用"和"看不到旧消息"混成一件事。
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
    // 切到别的项目时重新从最短间隔开始：上一个项目的故障不该让这个项目等 30 秒。
    delay.current = RECONNECT_MIN_MS

    function connect() {
      // 同源：dev 代理已把 /ws/project-chat 转过去，cookie 由浏览器自动带上。
      // WebSocket 握手带不了自定义头，这正是用会话 cookie 而不是 Bearer 的原因。
      const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(
        `${scheme}://${window.location.host}/ws/project-chat?projectId=${encodeURIComponent(projectId)}`,
      )
      socket.current = ws

      ws.onopen = () => {
        setDisconnected(false)
        // 连上就把退避重置回最短，否则一次偶发断线会让之后每次重连都等 30 秒。
        delay.current = RECONNECT_MIN_MS
        // 补发连上之前打的那些话（见 outbox 的注释）。发完才清掉落盘的那一份：
        // 没发出去就刷新的话，下次打开还会再试。
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
          // 一条读不出来的帧不该掐掉连接，也不该冒错：它多半是服务端回的
          // {"error": "..."}（收不了的消息），忽略即可。
        }
      }
      ws.onclose = () => {
        if (closed) return
        setDisconnected(true)
        // 自己重连：服务端不会替我们重连，断了之后一直不说话是用户看得见的坏。
        // 间隔逐次翻倍、封顶 30 秒，见上面那段注释。
        const wait = delay.current
        delay.current = Math.min(wait * 2, RECONNECT_MAX_MS)
        timer = setTimeout(connect, wait)
      }
      // onerror 之后 onclose 一定会来，所以重连只挂在 onclose 上；
      // 两处都挂会连出两条连接。
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
    // 锚点字段照旧发 null：后端的列和接口都还在（引用界面撤了，数据层留着），
    // 普通消息本来就没有锚点。
    const ws = socket.current
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ text, anchorEventSeq: null, anchorText: null }))
      return
    }
    // 还没连上：排着，onopen 时补发。不要在这里静默 return。
    outbox.current.push(text)
    // 同时落到这个项目的草稿键里：刷新后还能补发（见 chatOutbox）。
    if (store) {
      writeChatOutbox(store, projectId, outbox.current)
    }
  }, [projectId, store])

  return { messages, disconnected, loaded, send }
}
