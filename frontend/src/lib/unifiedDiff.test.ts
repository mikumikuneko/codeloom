import { describe, expect, it } from 'vitest'

import { parseUnifiedDiff } from './unifiedDiff'

/**
 * 钉的是"跟位置走，不跟前缀走"这条契约：统一 diff 的正文**有歧义**
 * （正文里本来就有以 `--` 开头的行），所以只有 `@@` 之后的才算正文。
 */

/** 两段 hunk 的 diff，第二个文件的起始行号离得很远 —— 用来验"行号按文件数"。 */
const TWO_FILES = [
  'diff --git a/x.ts b/x.ts',
  'index 111..222 100644',
  '--- a/x.ts',
  '+++ b/x.ts',
  '@@ -1,3 +1,3 @@',
  ' keep',
  '-old',
  '+new',
  ' keep',
  'diff --git a/y.ts b/y.ts',
  'index 333..444 100644',
  '--- a/y.ts',
  '+++ b/y.ts',
  '@@ -10,2 +10,3 @@',
  '+added',
  ' ctx',
  '',
].join('\n')

describe('parseUnifiedDiff', () => {
  it('文件头不是正文：`--- a/x` 不会被当成"删掉了一行 --- a/x"', () => {
    const rows = parseUnifiedDiff(TWO_FILES)

    expect(rows).not.toContainEqual({ kind: 'removed', text: '-- a/x.ts' })
    expect(rows).not.toContainEqual({ kind: 'added', text: '++ b/x.ts' })
  })

  it('一行 `+` / `-` / 空格恰好翻成一条', () => {
    const rows = parseUnifiedDiff(TWO_FILES)

    expect(rows.slice(0, 4)).toEqual([
      { kind: 'context', text: 'keep' },
      { kind: 'removed', text: 'old' },
      { kind: 'added', text: 'new' },
      { kind: 'context', text: 'keep' },
    ])
  })

  it('**行号按文件数**：换了文件就归零，否则第二个文件会带着上一个的行号画一条假的跳过', () => {
    const rows = parseUnifiedDiff(TWO_FILES)

    // 第二个文件从新文件第 10 行开始，前面 9 行没显示
    expect(rows).toContainEqual({ kind: 'gap', hidden: 9 })
    // 第一个文件从第 1 行开始，一行都没跳过
    expect(rows.some((row) => row.kind === 'gap' && row.hidden < 9)).toBe(false)
  })

  it('正文里以 `--` 开头的行，在 hunk 里就是一条删除', () => {
    const rows = parseUnifiedDiff(
      ['@@ -1,2 +1,2 @@', '--- 分隔线', '+-- 分隔线（改了）'].join('\n'),
    )

    expect(rows).toEqual([
      { kind: 'removed', text: '-- 分隔线' },
      { kind: 'added', text: '-- 分隔线（改了）' },
    ])
  })

  it('git 的注记和结尾那个空串不算行 —— 算了会让两侧行号错位', () => {
    const rows = parseUnifiedDiff(
      ['@@ -1,2 +1,3 @@', ' keep', '-old', '+new', '\\ No newline at end of file', ''].join('\n'),
    )

    expect(rows).toEqual([
      { kind: 'context', text: 'keep' },
      { kind: 'removed', text: 'old' },
      { kind: 'added', text: 'new' },
    ])
  })

  it('认不出的 `@@` 头**不画跳过**（行号算不出来，宁可什么都不提），但正文照常显示', () => {
    const rows = parseUnifiedDiff(['@@ 这个头不合法 @@', '+new', '-old'].join('\n'))

    expect(rows.some((row) => row.kind === 'gap')).toBe(false)
    expect(rows).toEqual([
      { kind: 'added', text: 'new' },
      { kind: 'removed', text: 'old' },
    ])
  })
})
