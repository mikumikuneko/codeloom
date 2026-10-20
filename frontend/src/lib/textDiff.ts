/**
 * 行级 diff。纯函数，不依赖任何库。
 *
 * 不需要后端：`edit_file` 的参数里就有 `old_string` 和 `new_string`，逐字符给出
 * 被替换的那一段，所以"改了什么"前端手里本来就有。
 *
 * 比的是模型给的这一段，不是整棵树：`old_string` 已是它挑出的上下文，前后各带几行。
 * 整棵树比要先拿到改动前后的两份全文，那要麻烦后端，而且这里也读不出更多东西。
 *
 * 自己写而不引包：输入通常是几行到几十行的小块，最朴素的最长公共子序列就够了。
 * 通用 diff 库换来的是几乎走不到的代码路径（大文件、二进制、词级合并）和一份
 * 管不着的依赖。规模超出预期时直接退化成"整段替换"：不对齐，但一行不丢。
 */

/**
 * diff 里的一行。`gap` 表示这里省略了 N 行，没有文字内容。
 *
 * `line` 是显示用的行号：删除的行用旧文件行号（它在新文件里已经没有了），其余用
 * 新文件的。来自统一 diff 的行自带它（`parseUnifiedDiff` 手上有 `@@`）；会话里
 * 那段改动不走统一 diff，改由后端给的起始行号（`DiffView` 的 `startLine`）现场数。
 */
export type DiffRow =
  | { kind: 'context'; text: string; line?: number }
  | { kind: 'added'; text: string; line?: number }
  | { kind: 'removed'; text: string; line?: number }
  | { kind: 'gap'; hidden: number }

/**
 * 每个改动块前后保留几行上下文。3 行刚好能认出这段在文件里的位置，
 * 再多只是把没改的部分也铺出来。
 */
const CONTEXT = 3

/**
 * 子序列搜索的规模上限（旧行数 × 新行数）。
 *
 * 超过就不再找对齐，直接全删 + 全加。两个各一千行的块光表就要一百万格，而人看
 * 那份 diff 的收益不会跟着涨。退化后结果仍然正确，只是不再标出哪些行没变。
 */
const MAX_CELLS = 250_000

/** 切行。末尾换行不算一行，否则每个以换行结尾的文件都会多出一个空行。 */
function splitLines(text: string): string[] {
  if (text === '') {
    return []
  }
  const lines = text.split('\n')
  if (lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines
}

/**
 * 两个文本之间改了哪几行。
 *
 * @param before 改动前的那一段（`old_string`）
 * @param after 改动后的那一段（`new_string`）
 * @param context 每个改动块前后保留几行；传 `Infinity` 表示不裁
 */
export function diffLines(before: string, after: string, context = CONTEXT): DiffRow[] {
  return withContext(rawDiff(splitLines(before), splitLines(after)), context)
}

/** 逐行比，给出一串 context / removed / added。 */
function rawDiff(a: string[], b: string[]): DiffRow[] {
  const n = a.length
  const m = b.length

  if (n * m > MAX_CELLS) {
    return [
      ...a.map((text): DiffRow => ({ kind: 'removed', text })),
      ...b.map((text): DiffRow => ({ kind: 'added', text })),
    ]
  }

  // dp[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度，从右下往左上填。
  const width = m + 1
  const dp = new Uint32Array((n + 1) * width)
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i * width + j] = a[i] === b[j]
        ? dp[(i + 1) * width + j + 1] + 1
        : Math.max(dp[(i + 1) * width + j], dp[i * width + j + 1])
    }
  }

  const rows: DiffRow[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      rows.push({ kind: 'context', text: a[i] })
      i += 1
      j += 1
    } else if (dp[(i + 1) * width + j] >= dp[i * width + j + 1]) {
      rows.push({ kind: 'removed', text: a[i] })
      i += 1
    } else {
      rows.push({ kind: 'added', text: b[j] })
      j += 1
    }
  }
  while (i < n) {
    rows.push({ kind: 'removed', text: a[i] })
    i += 1
  }
  while (j < m) {
    rows.push({ kind: 'added', text: b[j] })
    j += 1
  }
  return rows
}

/**
 * 把离改动太远的上下文行收成一条 `gap`。
 *
 * 模型给的 `old_string` 有时会带上一大段没动的上下文（为了保证唯一性，那是对的）。
 * 原样铺出来，真正改的那两行会淹在三十行灰字里。
 */
function withContext(rows: DiffRow[], context: number): DiffRow[] {
  // 没有改动时一行都不裁：裁的话整段变成一条"省略了 N 行"，人只看到"什么都没
  // 显示"。两边一样本来不该走到这里（edit_file 会拒绝 old_string 与 new_string
  // 相同的调用），但那是别人的保证，这一层不依赖它。
  if (!Number.isFinite(context) || !rows.some((row) => row.kind !== 'context')) {
    return rows
  }
  const keep = new Array<boolean>(rows.length).fill(false)
  for (let index = 0; index < rows.length; index++) {
    if (rows[index].kind === 'context') {
      continue
    }
    const from = Math.max(0, index - context)
    const to = Math.min(rows.length - 1, index + context)
    for (let at = from; at <= to; at++) {
      keep[at] = true
    }
  }

  const out: DiffRow[] = []
  let hidden = 0
  for (let index = 0; index < rows.length; index++) {
    if (keep[index]) {
      if (hidden > 0) {
        out.push({ kind: 'gap', hidden })
        hidden = 0
      }
      out.push(rows[index])
    } else {
      hidden += 1
    }
  }
  if (hidden > 0) {
    out.push({ kind: 'gap', hidden })
  }
  return out
}

/**
 * 一行里的一小段。`changed` 的段是这一对行之间真的换了的部分。
 *
 * 只在一对「删掉的行 / 加上的行」之间成立：整行新增或整行删除时没有"哪里变了"
 * 可谈，那种行不会有分段。
 */
export interface DiffSegment {
  text: string
  changed: boolean
}

/** 一对删/加行各自的分段。 */
export interface WordDiff {
  removed: DiffSegment[]
  added: DiffSegment[]
}

/**
 * 词级对齐的规模上限（旧词数 × 新词数）。
 *
 * 比行级上限小得多：一行出现几千个词只可能是紧凑格式的压缩产物，整行标出来反而
 * 好读。超过就退化成整行都算换了（返回 null）。
 */
const WORD_MAX_CELLS = 20_000

/**
 * 改动超过这个比例就不做词级标记。
 *
 * 两行几乎全不一样时，逐词标出来等于整行涂深，和"整行换了"说的是同一件事，
 * 却多出几十个切换点，读起来更碎。
 */
const WORD_CHANGE_LIMIT = 0.4

/**
 * 切成"词 / 空白 / 单个标点"。
 *
 * 空白单独成词是必要的：否则 `a = 1` 和 `a=1` 的差别会粘在 `a` 那个词里，标出
 * 半个词。单个标点各自成词，让 `>` 和 `>=`、`(` 和 `[` 这类一位差别也能单独标出。
 */
function tokenize(text: string): string[] {
  const tokens: string[] = []
  let at = 0
  while (at < text.length) {
    const ch = text[at]!
    if (/[\p{L}\p{N}_]/u.test(ch)) {
      let end = at + 1
      while (end < text.length && /[\p{L}\p{N}_]/u.test(text[end]!)) end++
      tokens.push(text.slice(at, end))
      at = end
    } else if (/\s/.test(ch)) {
      let end = at + 1
      while (end < text.length && /\s/.test(text[end]!)) end++
      tokens.push(text.slice(at, end))
      at = end
    } else {
      // 代理对算一个词：切成两半会让标出的颜色落在半个字上。
      const code = text.codePointAt(at)!
      const length = code > 0xffff ? 2 : 1
      tokens.push(text.slice(at, at + length))
      at += length
    }
  }
  return tokens
}

/**
 * 两行之间哪几个词换了。没有可标的返回 null，调用方退回普通渲染。
 *
 * 算法与行级相同（最长公共子序列），单位从行换成词：对得上的保持原样，对不上的
 * 两边都标成 changed，相邻同类并成一段。
 */
export function wordDiff(before: string, after: string): WordDiff | null {
  const a = tokenize(before)
  const b = tokenize(after)
  if (a.length * b.length > WORD_MAX_CELLS) {
    return null
  }

  const width = b.length + 1
  const dp = new Uint32Array((a.length + 1) * width)
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      dp[i * width + j] = a[i] === b[j]
        ? dp[(i + 1) * width + j + 1] + 1
        : Math.max(dp[(i + 1) * width + j], dp[i * width + j + 1])
    }
  }

  const sameA = new Array<boolean>(a.length).fill(false)
  const sameB = new Array<boolean>(b.length).fill(false)
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      sameA[i++] = true
      sameB[j++] = true
    } else if (dp[(i + 1) * width + j] >= dp[i * width + j + 1]) {
      i++
    } else {
      j++
    }
  }

  let changedLength = 0
  for (let at = 0; at < a.length; at++) {
    if (!sameA[at]) changedLength += a[at]!.length
  }
  for (let at = 0; at < b.length; at++) {
    if (!sameB[at]) changedLength += b[at]!.length
  }
  const total = before.length + after.length
  if (changedLength === 0 || (total > 0 && changedLength / total > WORD_CHANGE_LIMIT)) {
    return null
  }

  return { removed: segmentsOf(a, sameA), added: segmentsOf(b, sameB) }
}

/** 把词按"换没换"并成连续的小段。 */
function segmentsOf(tokens: string[], same: boolean[]): DiffSegment[] {
  const segments: DiffSegment[] = []
  tokens.forEach((token, at) => {
    const changed = !same[at]
    const last = segments[segments.length - 1]
    if (last !== undefined && last.changed === changed) {
      last.text += token
    } else {
      segments.push({ text: token, changed })
    }
  })
  return segments
}

/**
 * 给整段 diff 配词级差异。返回数组与 `rows` 等长，没配上的位置是 null。
 *
 * 配对规则：一段连续删行后紧跟一段连续加行时，第 k 行对第 k 行；多出来的行没有
 * 对象，保持整行标注。中间隔着上下文的不配对，那说明它们不是同一处改动。
 */
export function wordDiffs(rows: DiffRow[]): (WordDiff | null)[] {
  const out = new Array<WordDiff | null>(rows.length).fill(null)
  let at = 0
  while (at < rows.length) {
    if (rows[at]!.kind !== 'removed') {
      at++
      continue
    }
    const removedStart = at
    while (at < rows.length && rows[at]!.kind === 'removed') at++
    const addedStart = at
    while (at < rows.length && rows[at]!.kind === 'added') at++

    const removed = rows.slice(removedStart, addedStart)
      .filter((row): row is Extract<DiffRow, { kind: 'removed' }> => row.kind === 'removed')
    const added = rows.slice(addedStart, at)
      .filter((row): row is Extract<DiffRow, { kind: 'added' }> => row.kind === 'added')
    const pairs = Math.min(removed.length, added.length)
    for (let k = 0; k < pairs; k++) {
      const diff = wordDiff(removed[k]!.text, added[k]!.text)
      if (diff !== null) {
        out[removedStart + k] = diff
        out[addedStart + k] = diff
      }
    }
  }
  return out
}
