/**
 * 聊天室里还没发出去的那几句话。
 *
 * WebSocket 未 OPEN 时消息排进 outbox，连上再补发。落盘是因为刷新是"连不上"时
 * 人最常做的动作，只在内存里会丢。用 `sessionStorage` 而不是 `localStorage`：
 * 它是这个标签页的草稿，不该跑到另一个标签页或另一个账号。按项目分键：同一对人
 * 在不同项目里的聊天是两条线，补发到别的项目比丢掉更糟。
 *
 * 读坏了当空：数据由本应用写，读不出来只可能来自别的版本或手工修改；丢一条
 * 草稿好过让聊天室因为坏键打不开。
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
    // 只收非空字符串：空串发出去只是一条空气泡，类型不对的也不能拿去做 payload。
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
    // 存不了（隐私模式 / 配额满）就退回只在内存里：不能因为草稿让发送本身失败。
  }
}
