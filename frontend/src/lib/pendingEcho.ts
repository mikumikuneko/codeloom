import { useSyncExternalStore } from 'react'

/**
 * 我刚发出去、**还没在事件流里看见**的那些话 —— 一份**普通的状态机**。
 *
 * <p>它是个普通对象而不是模块级的那几张表：这里唯一容易写错的是**回收规则**
 * （见 {@link PendingEcho.observeStream} 的两条），而把它做成可注入的实例之后，
 * 那条规则能直接喂数据测 —— 不需要 React、不需要 DOM。
 */
export interface PendingEcho {
  /** 我按下发送了。**在请求发出去之前调**，理由见实现里那句。 */
  noteSent(sessionId: string, text: string): void

  /** 这一句**没发出去**（请求失败了）—— 把回声撤掉。 */
  dropSent(sessionId: string, text: string): void

  /** 事件流里现在一共几条用户消息。 */
  observeStream(sessionId: string, total: number): void

  /** 还没到位的那几句，按发出的先后。**空的时候返回的是同一个引用**，见下面 `EMPTY`。 */
  pending(sessionId: string): string[]

  /** 给 `useSyncExternalStore` 用。 */
  subscribe(listener: () => void): () => void
}

/**
 * 空数组要用**同一个引用**：`useSyncExternalStore` 要求 getSnapshot 稳定，
 * 每次新造一个的话它会认为状态一直在变，然后无限重渲。
 */
const EMPTY: string[] = []

interface Echo {
  /** 发出去还没到位的，先发先出 */
  pending: string[]
  /** 上一次看到的、流里的用户消息条数 */
  lastObserved: number
  /** 收到过至少一次了吗 —— 第一次只记基线，不收任何东西 */
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
      // **顺序**：这一步要在请求发出去**之前**。反过来的话，请求快得离谱时流里那条会先到，
      // 而这里随后又补一条 —— 屏幕上会闪出两条一样的
      const echo = of(sessionId)
      echo.pending = [...echo.pending, text]
      notify()
    },

    dropSent(sessionId, text) {
      const echo = echoes.get(sessionId)
      if (echo === undefined) {
        return
      }
      // 从**尾巴**往回找第一条对得上的：失败的那句总是最近发的。
      // 找最后一条而不是删最后一条 —— 失败回来的时候用户可能已经又发了一句，
      // 那句还好好地排着，不该被误伤
      const at = echo.pending.lastIndexOf(text)
      if (at < 0) {
        return
      }
      echo.pending = [...echo.pending.slice(0, at), ...echo.pending.slice(at + 1)]
      notify()
    },

    observeStream(sessionId, total) {
      const echo = of(sessionId)
      // ① 第一次收到**只记基线**：打开一条老会话时流里本来就有几十条，
      // 而那些都在我之前，一条都不该拿来收我的回声
      if (!echo.observed) {
        echo.observed = true
        echo.lastObserved = total
        return
      }
      // ② 条数变少只有一种原因：**回滚把几轮对话截掉了**。基线得跟着落下来。
      //
      // 不落的话它会永远停在回滚前那个大数上，于是后面新到的那句话把总数顶回原值时，
      // 差值正好是 0 —— 一条回声都收不走，那条灰着的话就永久挂在屏幕末尾。
      // （真踩过：回滚之后再发一句，同一句话在屏幕上出现了两次，一次真的、一次灰的）
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
 * <h2>为什么必须是全局一份，而不是放进某个组件里</h2>
 * 写它的是**输入框**（{@code Composer}），收它的是**会话流**（{@code SessionStreamView}）——
 * 两个不同的组件。而"发送还在飞的时候切走会话、待会儿再切回来"要求那几句回声**还在**：
 * 挂在其中任何一个组件上的话，切走就没了，而那条消息其实还在路上。
 *
 * <h2>它是本地状态，不上服务器</h2>
 * 代价说清楚：**对面那个人现在看不到它**，要等真落库那一刻才出现在对方屏幕上。
 * 这是"乐观显示"的固有代价，不是漏了一步。
 */
const appEcho = createPendingEcho()

/** 见 {@link PendingEcho.noteSent} 的契约（调用方在 {@code Composer} 里）。 */
export function noteSent(sessionId: string, text: string) {
  appEcho.noteSent(sessionId, text)
}

export function dropSent(sessionId: string, text: string) {
  appEcho.dropSent(sessionId, text)
}

export function observeStream(sessionId: string, total: number) {
  appEcho.observeStream(sessionId, total)
}

/** 还没到位的那几句，按发出的先后。**空数组是常态。** */
export function usePendingEcho(sessionId: string | null): string[] {
  return useSyncExternalStore(
    (listener) => appEcho.subscribe(listener),
    () => (sessionId === null ? EMPTY : appEcho.pending(sessionId)),
    () => EMPTY,
  )
}
