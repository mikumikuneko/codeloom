import { beforeEach, describe, expect, it } from 'vitest'

import { createPendingEcho, type PendingEcho } from './pendingEcho'

/**
 * 钉的是**回收规则**那两条：第一次只记基线；条数变少（回滚）时基线要跟着落下来。
 *
 * <p>第二条是**真踩过的 bug**：回滚之后再发一句，同一句话会在屏幕上出现两次
 * （一次真的、一次灰的）—— 因为基线停在回滚前那个大数上，差值算出来是 0，
 * 一条回声都收不走。
 *
 * <p>它不需要 React、也不需要 DOM：状态机是普通对象。
 */

const SESSION = 's1'
let echo: PendingEcho

beforeEach(() => {
  echo = createPendingEcho()
})

describe('回声什么时候被收走', () => {
  it('流里多出一条用户消息，就从队头收走一条', () => {
    echo.observeStream(SESSION, 0)
    echo.noteSent(SESSION, '我刚说的')

    expect(echo.pending(SESSION)).toEqual(['我刚说的'])

    echo.observeStream(SESSION, 1)

    expect(echo.pending(SESSION)).toEqual([])
  })

  it('**第一次只记基线**：打开一条老会话时那几十条都在我之前，一条都不该拿来收', () => {
    echo.noteSent(SESSION, '我刚说的')

    echo.observeStream(SESSION, 30)

    expect(echo.pending(SESSION)).toEqual(['我刚说的'])
  })

  it('排队的两句先发先出', () => {
    echo.observeStream(SESSION, 0)
    echo.noteSent(SESSION, '第一句')
    echo.noteSent(SESSION, '第二句')

    echo.observeStream(SESSION, 1)
    expect(echo.pending(SESSION)).toEqual(['第二句'])

    echo.observeStream(SESSION, 2)
    expect(echo.pending(SESSION)).toEqual([])
  })

  it('条数没变就什么都不动', () => {
    echo.observeStream(SESSION, 0)
    echo.noteSent(SESSION, '一句')

    echo.observeStream(SESSION, 0)

    expect(echo.pending(SESSION)).toEqual(['一句'])
  })

  it('**回滚之后基线要落下来** —— 不落的话那条灰字会永久挂在屏幕末尾', () => {
    echo.observeStream(SESSION, 3)
    // 回滚：流被截短了
    echo.observeStream(SESSION, 1)
    echo.noteSent(SESSION, '回滚之后说的')

    echo.observeStream(SESSION, 2)

    expect(echo.pending(SESSION)).toEqual([])
  })

  it('回滚之后连发两句也不漏', () => {
    echo.observeStream(SESSION, 3)
    echo.observeStream(SESSION, 1)
    echo.noteSent(SESSION, '第一句')
    echo.noteSent(SESSION, '第二句')

    echo.observeStream(SESSION, 2)
    echo.observeStream(SESSION, 3)

    expect(echo.pending(SESSION)).toEqual([])
  })
})

describe('发送失败', () => {
  it('把那一句撤掉', () => {
    echo.noteSent(SESSION, '没发出去')

    echo.dropSent(SESSION, '没发出去')

    expect(echo.pending(SESSION)).toEqual([])
  })

  it('撤的是**对得上的**那条，不是最后一条 —— 失败回来时用户可能已经又发了一句', () => {
    echo.noteSent(SESSION, 'A')
    echo.noteSent(SESSION, 'B')

    // 先发的 A 失败了 —— B 还好好地排着，不该被误伤
    echo.dropSent(SESSION, 'A')

    expect(echo.pending(SESSION)).toEqual(['B'])
  })
})

describe('几个契约', () => {
  it('空的时候返回的是**同一个引用**（否则 useSyncExternalStore 会无限重渲）', () => {
    expect(echo.pending(SESSION)).toBe(echo.pending(SESSION))
    expect(echo.pending('另一条')).toBe(echo.pending('第三条'))
  })

  it('会话之间互不影响', () => {
    echo.observeStream('a', 0)
    echo.observeStream('b', 5)
    echo.noteSent('a', '给 a 的')
    echo.noteSent('b', '给 b 的')

    echo.observeStream('a', 1)

    expect(echo.pending('a')).toEqual([])
    expect(echo.pending('b')).toEqual(['给 b 的'])
  })

  it('订阅者收到通知，退订之后收不到', () => {
    let heard = 0
    const stop = echo.subscribe(() => (heard += 1))

    echo.noteSent(SESSION, '一句')
    expect(heard).toBe(1)

    stop()
    echo.noteSent(SESSION, '又一句')
    expect(heard).toBe(1)
  })

  it('两份实例互不干扰（"对话不是一个单例"这件事是能测的）', () => {
    const other = createPendingEcho()
    echo.noteSent(SESSION, '这句只进第一份')

    expect(echo.pending(SESSION)).toEqual(['这句只进第一份'])
    expect(other.pending(SESSION)).toEqual([])
  })
})
