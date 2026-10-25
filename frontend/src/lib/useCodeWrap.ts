import { useSyncExternalStore } from 'react'

import { readStorage, writeStorage } from '@/lib/storage'

/**
 * 代码块是否自动换行。
 *
 * 状态是全局的：一段对话可能有十几个代码块，每块一个开关会逼人逐个拨。偏好记在
 * 本地，刷新后仍生效。
 *
 * 用 `useSyncExternalStore` 而不是 context 或 zustand：它是没有归属组件树的纯外部
 * 状态（同一条会话流或聊天流里散着多个 Markdown 代码块），也不需要"谁提供、谁消费"
 * 那层关系。`CodeLines`（裁决页与截断文件）不走这个开关。
 * 读不出来（隐私模式）只是记不住，退回默认值，不是错误。
 */
const KEY = 'codeloom.codeWrap'

function read(): boolean {
  return readStorage(KEY) === 'true'
}

let wrapped = read()
const listeners = new Set<() => void>()

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * 换行是否开启，以及切换它。
 *
 * 默认不换行：这一栏里多数行短于栏宽，换行会把长行的缩进折成一团，比横着拖更难读。
 * 开关放在手边。
 */
export function useCodeWrap(): [boolean, () => void] {
  const value = useSyncExternalStore(subscribe, () => wrapped)

  function toggle() {
    wrapped = !wrapped
    writeStorage(KEY, String(wrapped))
    for (const listener of listeners) {
      listener()
    }
  }

  return [value, toggle]
}
