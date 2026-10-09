import { useSyncExternalStore } from 'react'

/**
 * 代码块要不要**自动换行**。
 *
 * <h2>为什么是全局的，不是每个代码块一个</h2>
 * 一段对话里可能有十几个代码块。每个各带一个开关的话，人要为每一个拨一次 ——
 * 而那件事他其实只想说一遍："我这一栏太窄，长行别让我横着拖。"
 *
 * <p>它同时**记在本地**：刷新一次就忘掉的偏好，等于每次都重新拨一遍。
 *
 * <h2>为什么用 {@code useSyncExternalStore} 而不是 context 或 zustand</h2>
 * 它是一个**纯外部状态**：没有归属的组件树（这些代码块散在消息、diff、中栏、
 * 裁决页里），也不需要"谁提供、谁消费"那层关系。{@code useSyncExternalStore} 就是
 * 为这种形状准备的：订阅、取值、改值，三个函数。
 *
 * <p>和 {@code usePaneWidths} 一样，读不出来（隐私模式）只是"记不住" ——
 * 退回默认值，不是错误。
 */
const KEY = 'codeloom.codeWrap'

function read(): boolean {
  try {
    const stored = localStorage.getItem(KEY)
    return stored === null ? false : stored === 'true'
  } catch {
    return false
  }
}

let wrapped = read()
const listeners = new Set<() => void>()

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * 换行开没开，以及切换它。
 *
 * <p>默认**不换行**：这一栏里 90% 的行都短于栏宽，而换行会把"缩进"这件事
 * 在长行上折成一团 —— 那比横着拖一下更难读。这是和 deepseek-harness 不一样的一处：
 * 它默认开着换行。我们两种都试不了，就先按"代码保持形状"来，开关放在手边。
 */
export function useCodeWrap(): [boolean, () => void] {
  const value = useSyncExternalStore(subscribe, () => wrapped)

  function toggle() {
    wrapped = !wrapped
    try {
      localStorage.setItem(KEY, String(wrapped))
    } catch {
      // 存不下只是"下次打开记不住"，这一次照常生效
    }
    for (const listener of listeners) {
      listener()
    }
  }

  return [value, toggle]
}
