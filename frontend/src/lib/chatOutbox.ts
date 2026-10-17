/**
 * 聊天室里"还没发出去"的那几句话。
 *
 * <h2>为什么落盘</h2>
 * WebSocket 还没 OPEN 时发的话会排进 outbox，连上再补发。只在内存里的话，
 * 刷新一次就没了 —— 而刷新恰恰是"连不上"时人最常做的动作。用 `sessionStorage`
 * 而不是 `localStorage`：它是**这个标签页**的草稿，不该跑到另一个标签页/另一个账号去。
 *
 * <h2>为什么按项目分键</h2>
 * 同一对人在不同项目里的聊天是两条线；把 A 项目没发出去的话补发到 B 项目里，
 * 比丢掉更糟。
 *
 * <h2>为什么读坏了就当空</h2>
 * 这份数据是本应用自己写的；读不出来只可能是被别的版本/手工改过。那种时候
 * 丢掉一条草稿，比让聊天室因为一个坏键而打不开好。
 */
export interface KeyValueStore {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

const PREFIX = 'codeloom.chat.outbox.'

export function chatOutboxKey(projectId: string): string {
  return `${PREFIX}${projectId}`
}

export function readChatOutbox(store: KeyValueStore, projectId: string): string[] {
  try {
    const raw = store.getItem(chatOutboxKey(projectId))
    if (raw === null) {
      return []
    }
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) {
      return []
    }
    // 只收非空字符串：空串发出去只是一条空气泡，类型不对的那几条也不能拿去做 payload
    return parsed.filter((item): item is string => typeof item === 'string' && item !== '')
  } catch {
    return []
  }
}

export function writeChatOutbox(
  store: KeyValueStore,
  projectId: string,
  messages: readonly string[],
): void {
  try {
    if (messages.length === 0) {
      store.removeItem(chatOutboxKey(projectId))
    } else {
      store.setItem(chatOutboxKey(projectId), JSON.stringify(messages))
    }
  } catch {
    // 存不了（隐私模式/配额满）就退回"只在内存里" —— 不能因为草稿让发送本身失败
  }
}
