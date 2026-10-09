import { useSyncExternalStore } from 'react'

/**
 * 我刚发出去、**还没在事件流里看见**的那些话。
 *
 * <h2>为什么需要它</h2>
 * 发一句话的那个请求从前是**阻塞的** —— 它一直等到整轮跑完才返回（可能几十秒）。
 * 界面因此犯了两件事：输入框**整轮锁着**（第二句根本发不出去），框里那句话也要等回执
 * 才消失 —— 看起来像"发出去了但没清空"。
 *
 * <p>改成不等它之后，代价是**那一瞬间屏幕上什么都没有**：排队那句可能要等到这一轮收尾
 * 才落库。所以这里先替它占个位 —— 当成一条"还没轮到你"的消息画在末尾。
 * Claude Code 也是这么做的（"Sent and queued messages show in gray until Claude starts
 * responding to them"）。
 *
 * <h2>它是本地状态，不上服务器</h2>
 * 代价说清楚：**对面那个人现在看不到它**，要等真落库那一刻才出现在对方屏幕上。
 * 这是"乐观显示"的固有代价，不是漏了一步。
 *
 * <h2>怎么知道该收回了：由**流的下一次到达**收走</h2>
 * 每看到流里多出一条用户消息，就从队头收走一条。这是这个模块里最容易写错的地方 ——
 * 一开始我想的是"记下打开时的条数当基线，拿总数减"，那是错的：
 * 一条老会话打开时流里本来就有几十条，而**那些都在我之前**，一条都不该拿来收我的回声。
 */
interface Echo {
  /** 发出去还没到位的，先发先出 */
  pending: string[]
  /** 上一次看到的、流里的用户消息条数 */
  lastObserved: number
  /** 收到过至少一次了吗 —— 第一次只记基线，不收任何东西 */
  observed: boolean
}

const echoes = new Map<string, Echo>()
const listeners = new Set<() => void>()

function notify() {
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
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

/**
 * 我按下发送了。**在请求发出去之前调**。
 *
 * <p>顺序反过来的话，请求快得离谱时流里那条会先到，而这里随后又补一条 ——
 * 屏幕上会闪出两条一样的。
 */
export function noteSent(sessionId: string, text: string) {
  const echo = of(sessionId)
  echo.pending = [...echo.pending, text]
  notify()
}

/** 这一句**没发出去**（请求失败了）—— 把回声撤掉。 */
export function dropSent(sessionId: string, text: string) {
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
}

/**
 * 事件流里现在一共 {@code total} 条用户消息。
 *
 * <p>第一次收到时**只记基线**：打开一条老会话时流里本来就有几十条，
 * 而那些都在我之前，一条都不该拿来收我的回声。
 */
export function observeStream(sessionId: string, total: number) {
  const echo = of(sessionId)
  if (!echo.observed) {
    echo.observed = true
    echo.lastObserved = total
    return
  }
  if (total < echo.lastObserved) {
    // **条数变少只有一种原因：回滚把几轮对话截掉了。**
    //
    // 基线得跟着落下来。不落的话它会永远停在回滚前那个大数上，于是后面新到的那句话
    // 把总数顶回原值时，差值正好是 0 —— 一条回声都收不走，那条灰着的话就永久挂在
    // 屏幕末尾（真踩过：回滚之后再发一句，同一句话在屏幕上出现了两次，
    // 一次是真的、一次是灰的）
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
}

/** 还没到位的那几句，按发出的先后。**空数组是常态。** */
export function usePendingEcho(sessionId: string | null): string[] {
  return useSyncExternalStore(
    subscribe,
    () => (sessionId === null ? EMPTY : of(sessionId).pending),
    () => EMPTY,
  )
}

/**
 * 空数组要用**同一个引用**：`useSyncExternalStore` 要求 getSnapshot 稳定，
 * 每次新造一个的话它会认为状态一直在变，然后无限重渲。
 */
const EMPTY: string[] = []
