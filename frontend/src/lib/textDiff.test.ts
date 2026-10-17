import { describe, expect, it } from 'vitest'

import { diffLines, wordDiff, wordDiffs } from './textDiff'

/**
 * 钉的是这几条：**末尾换行不算一行**、改动之外的上下文会被收成一条 `gap`、
 * **没有改动时一行都不裁**、以及超规模时退化成"全删全加但一行不丢"。
 */

const lines = (...text: string[]) => text.join('\n')

describe('diffLines', () => {
  it('末尾那个换行不算一行 —— 否则每个以换行结尾的文件都多一个空行', () => {
    expect(diffLines('a\n', 'a\n')).toEqual([{ kind: 'context', text: 'a' }])
  })

  it('空串是零行', () => {
    expect(diffLines('', '')).toEqual([])
  })

  it('新增、删除、替换各是各的', () => {
    expect(diffLines(lines('a', 'b', 'c'), lines('a', 'x', 'c'))).toEqual([
      { kind: 'context', text: 'a' },
      { kind: 'removed', text: 'b' },
      { kind: 'added', text: 'x' },
      { kind: 'context', text: 'c' },
    ])
  })

  it('纯插入 / 纯删除', () => {
    expect(diffLines('a\nc', 'a\nb\nc')).toEqual([
      { kind: 'context', text: 'a' },
      { kind: 'added', text: 'b' },
      { kind: 'context', text: 'c' },
    ])
    expect(diffLines('a\nb\nc', 'a\nc')).toEqual([
      { kind: 'context', text: 'a' },
      { kind: 'removed', text: 'b' },
      { kind: 'context', text: 'c' },
    ])
  })

  it('离改动太远的上下文收成一条 gap（模型给的 old_string 常常带一大段没动的）', () => {
    const many = Array.from({ length: 20 }, (_, i) => `line ${i}`)
    const rows = diffLines(lines(...many, 'tail'), lines(...many, 'changed'))

    // 前面那些没动的行被收掉了，只剩末尾那几行上下文 + 改动
    expect(rows[0]).toEqual({ kind: 'gap', hidden: expect.any(Number) })
    expect(rows.some((row) => row.kind === 'removed' && row.text === 'tail')).toBe(true)
    expect(rows.some((row) => row.kind === 'added' && row.text === 'changed')).toBe(true)
  })

  it('改动前后各留 3 行上下文', () => {
    const rows = diffLines('1\n2\n3\n4\n5\n6\n7\n8\n9', '1\n2\n3\n4\nX\n6\n7\n8\n9')
    const keptBefore = rows.slice(0, rows.findIndex((row) => row.kind === 'removed'))

    expect(keptBefore.filter((row) => row.kind === 'context')).toHaveLength(3)
  })

  it('**没有改动时一行都不裁** —— 裁了整段会变成一条"省略了 N 行"，屏幕上就什么都没了', () => {
    const many = Array.from({ length: 40 }, (_, i) => `line ${i}`).join('\n')

    const rows = diffLines(many, many)

    expect(rows.every((row) => row.kind === 'context')).toBe(true)
    expect(rows).toHaveLength(40)
  })

  it('传 Infinity 表示不裁', () => {
    const many = Array.from({ length: 20 }, (_, i) => `line ${i}`)
    const rows = diffLines(lines(...many, 'tail'), lines(...many, 'changed'), Infinity)

    expect(rows.some((row) => row.kind === 'gap')).toBe(false)
  })

  it('规模超了就直接"全删 + 全加"：不做对齐，但**一行不丢**', () => {
    // 两个 600 行的块：600 × 600 = 360,000 已经超过 250,000 那张表的上限，
    // 而且两边内容全不一样（真去找对齐也找不到）
    const before = Array.from({ length: 600 }, (_, i) => `old ${i}`).join('\n')
    const after = Array.from({ length: 600 }, (_, i) => `new ${i}`).join('\n')

    const rows = diffLines(before, after)

    expect(rows.filter((row) => row.kind === 'removed')).toHaveLength(600)
    expect(rows.filter((row) => row.kind === 'added')).toHaveLength(600)
    expect(rows.some((row) => row.kind === 'gap')).toBe(false)
  })
})

describe('wordDiff', () => {
  it('只标真正换了的那个词，两边各自的其余部分并成整段', () => {
    expect(wordDiff('const a = 1;', 'const a = 2;')).toEqual({
      removed: [
        { text: 'const a = ', changed: false },
        { text: '1', changed: true },
        { text: ';', changed: false },
      ],
      added: [
        { text: 'const a = ', changed: false },
        { text: '2', changed: true },
        { text: ';', changed: false },
      ],
    })
  })

  it('一行几乎全换了就不标词 —— 整行涂深和"整行换了"是同一句话', () => {
    expect(wordDiff('aaaa bbbb cccc dddd', 'eeee ffff gggg hhhh')).toBeNull()
  })

  it('两行一样时没有可标的', () => {
    expect(wordDiff('same line', 'same line')).toBeNull()
  })

  it('空白和标点各自成词，差别不会被粘进旁边的词里', () => {
    const diff = wordDiff('a>=b', 'a> b')

    expect(diff?.removed.some((segment) => segment.changed && segment.text === '=')).toBe(true)
  })
})

describe('wordDiffs', () => {
  it('删行和紧跟的加行按顺序配对；配上的两边拿到同一份结果', () => {
    const rows = diffLines('x = 1', 'x = 2')
    const words = wordDiffs(rows)
    const removedAt = rows.findIndex((row) => row.kind === 'removed')
    const addedAt = rows.findIndex((row) => row.kind === 'added')

    expect(words[removedAt]).not.toBeNull()
    expect(words[removedAt]).toBe(words[addedAt])
    expect(words[removedAt]!.removed.some((segment) => segment.changed && segment.text === '1')).toBe(true)
  })

  it('中间隔着没动的行就不配对 —— 那说明它们不是同一处改动', () => {
    // 删掉的是 x、加上的是 y：中间那句 b 在两边都对得上，所以它们不是一对
    const rows = diffLines('a\nx\nb', 'a\nb\ny')
    const words = wordDiffs(rows)

    expect(rows.map((row) => row.kind)).toEqual(['context', 'removed', 'context', 'added'])
    expect(words.every((entry) => entry === null)).toBe(true)
  })
})
