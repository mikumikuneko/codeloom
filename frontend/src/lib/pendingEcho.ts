import { useSyncExternalStore } from 'react'

/**
 * 我刚发出、还没在事件流里看见的那些话：一份普通的状态机。
 *
 * 做成可注入的实例而不是模块级的表，是因为唯一容易写错的是回收规则（见
 * `{@link PendingEcho.observeStream}`），实例可以直接喂数据测试，不需要 React 或 DOM。
 */
export interface PendingEcho {
  /** 记下我按下发送。必须在请求发出之前调用，见实现里的顺序说明。 */
  noteSent(sessionId: string, text: string): void

  /** 这一句没发出去（请求失败），把回声撤掉。 */
  dropSent(sessionId: string, text: string): void

  /** 事件流里现在一共有几条用户消息。 */
  observeStream(sessionId: string, total: number): void

  /** 还没到位的那几句，按发出先后。空时返回同一个引用，见下面的 `EMPTY`。 */
  pending(sessionId: string): string[]

  /** 给 `useSyncExternalStore` 用。 */
  subscribe(listener: () => void): () => void
}

/**
 * 空数组必须用同一个引用：`useSyncExternalStore` 要求 getSnapshot 稳定，
 * 每次新造会让它认为状态一直在变，然后无限重渲。
 */
const EMPTY: string[] = []

interface Echo {
  /** 发出去还没到位的，先发先出 */
  pending: string[]
  /** 上一次看到的、流里的用户消息条数 */
  lastObserved: number
  /** 是否收到过至少一次：第一次只记基线，不收任何东西 */
  observed: boolean
}

export function createPendingEcho(): PendingEcho {
  const echoes = new Map<string, Echo>()
  const listeners = new Set<() => void>()

  function notify() {
    listeners.forEach((listener) => listener())
  }

  function of(sessionId: string): Echo {
    const existing = echoes.get(sessionId)
    if (existing !== undefined) {
      return existing
    }
    const fresh: Echo = { pending: EMPTY, lastObserved: 0, observed: false }
    echoes.set(sessionId, fresh)
    return fresh
  }

  return {
    noteSent(sessionId, text) {
      // 顺序：必须在请求发出去之前。反过来，请求过快时流里那条会先到，这里随后
      // 又补一条，屏幕上会闪出两条一样的。
      const echo = of(sessionId)
      echo.pending = [...echo.pending, text]
      notify()
    },

    dropSent(sessionId, text) {
      const echo = echoes.get(sessionId)
      if (echo === undefined) {
        return
      }
      // 从尾巴往回找第一条对得上的：失败的是最近发的那句。找最后一条而不是删
      // 最后一条：失败回来时用户可能已又发了一句，那句还排着，不该被误伤。
      const at = echo.pending.lastIndexOf(text)
      if (at < 0) {
        return
      }
      echo.pending = [...echo.pending.slice(0, at), ...echo.pending.slice(at + 1)]
      notify()
    },

    observeStream(sessionId, total) {
      const echo = of(sessionId)
      // ① 第一次收到只记基线：打开老会话时流里本来就有几十条，那些都在我之前，
      // 一条都不该拿来收我的回声。
      if (!echo.observed) {
        echo.observed = true
        echo.lastObserved = total
        return
      }
      // ② 条数变少只可能是回滚截掉了几轮对话，基线要跟着落下来。不落的话它会
      // 停在回滚前那个数上，后面新到一句把总数顶回原值时差值为 0，一条回声都收
      // 不走，灰色那句永久挂在屏幕末尾。
      if (total < echo.lastObserved) {
        echo.lastObserved = total
        return
      }
      const arrived = total - echo.lastObserved
      if (arrived === 0) {
        return
      }
      echo.lastObserved = total
      echo.pending = echo.pending.slice(arrived)
      notify()
    },

    pending(sessionId) {
      return of(sessionId).pending
    },

    subscribe(listener) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
  }
}

/**
 * 全应用那一份。
 *
 * 写它的是输入框（`Composer`），收它的是会话流（`SessionStreamView`），两个不同的
 * 组件。"发送还在飞时切走会话、稍后再切回"要求回声还在，挂在任一组件上都会随
 * 切走丢失，而那条消息其实还在路上。
 *
 * 它是本地状态，不上服务器：对方要等真落库那一刻才看到。这是乐观显示的固有代价。
 */
const appEcho = createPendingEcho()

/** 见 {@link PendingEcho.noteSent} 的契约（调用方在 `Composer` 里）。 */
export function noteSent(sessionId: string, text: string) {
  appEcho.noteSent(sessionId, text)
}

export function dropSent(sessionId: string, text: string) {
  appEcho.dropSent(sessionId, text)
}

export function observeStream(sessionId: string, total: number) {
  appEcho.observeStream(sessionId, total)
}

/** 还没到位的那几句，按发出先后。空数组是常态。 */
export function usePendingEcho(sessionId: string | null): string[] {
  return useSyncExternalStore(
    (listener) => appEcho.subscribe(listener),
    () => (sessionId === null ? EMPTY : appEcho.pending(sessionId)),
    () => EMPTY,
  )
}
