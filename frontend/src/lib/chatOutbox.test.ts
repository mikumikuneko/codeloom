import { describe, expect, it } from 'vitest'

import { chatOutboxKey, readChatOutbox, writeChatOutbox, type KeyValueStore } from './chatOutbox'

class MemoryStore implements KeyValueStore {
  private readonly values = new Map<string, string>()

  getItem(key: string): string | null {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value)
  }

  removeItem(key: string): void {
    this.values.delete(key)
  }
}

describe('chatOutbox', () => {
  it('按项目分键，互不串台', () => {
    const store = new MemoryStore()
    writeChatOutbox(store, 'p1', ['给项目一的'])
    writeChatOutbox(store, 'p2', ['给项目二的'])
    expect(readChatOutbox(store, 'p1')).toEqual(['给项目一的'])
    expect(readChatOutbox(store, 'p2')).toEqual(['给项目二的'])
    expect(chatOutboxKey('p1')).not.toBe(chatOutboxKey('p2'))
  })

  it('写进去再读出来是同一批', () => {
    const store = new MemoryStore()
    writeChatOutbox(store, 'p', ['第一句', '第二句'])
    expect(readChatOutbox(store, 'p')).toEqual(['第一句', '第二句'])
  })

  it('空数组等于删掉这个键', () => {
    const store = new MemoryStore()
    writeChatOutbox(store, 'p', ['一句话'])
    writeChatOutbox(store, 'p', [])
    expect(readChatOutbox(store, 'p')).toEqual([])
  })

  it('坏 JSON、不是数组、不是字符串的条目都跳过', () => {
    const store = new MemoryStore()
    store.setItem(chatOutboxKey('broken'), '{')
    store.setItem(chatOutboxKey('object'), '{"text":"x"}')
    store.setItem(chatOutboxKey('mixed'), JSON.stringify(['好的', '', 3, null, '也算好']))
    expect(readChatOutbox(store, 'broken')).toEqual([])
    expect(readChatOutbox(store, 'object')).toEqual([])
    expect(readChatOutbox(store, 'mixed')).toEqual(['好的', '也算好'])
  })

  it('存储本身抛错时读成空、写也不抛', () => {
    const broken: KeyValueStore = {
      getItem: () => { throw new Error('denied') },
      setItem: () => { throw new Error('denied') },
      removeItem: () => { throw new Error('denied') },
    }
    expect(readChatOutbox(broken, 'p')).toEqual([])
    expect(() => writeChatOutbox(broken, 'p', ['一句话'])).not.toThrow()
  })
})
