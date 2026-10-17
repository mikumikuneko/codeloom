/**
 * 行级 diff。**纯函数，不依赖任何库。**
 *
 * <h2>它为什么可以不要后端</h2>
 * {@code edit_file} 的参数里就有 {@code old_string} 和 {@code new_string} ——
 * 逐字符给出被替换的那一段。所以"改了什么"这件事，前端手里本来就有，
 * 不必等工具结果里回一段 patch。
 *
 * <h2>为什么不用整棵树比</h2>
 * 比的是**模型给的这一段**，不是整个文件：{@code old_string} 已经是它挑出来的
 * 上下文，前后各带上几行。整棵树比要先拿到改动前后的两份全文 —— 那要麻烦后端，
 * 而且这里也读不出更多东西。
 *
 * <h2>为什么自己写而不是引一个包</h2>
 * 输入是两个通常只有几行到几十行的小块。这种情况下一个最朴素的
 * 最长公共子序列就够了 —— 引一个通用 diff 库，换来的是一个我们这边几乎不会走到的
 * 代码路径（大文件、二进制、词级合并）和一份管不着的依赖。
 * 规模一旦超出预期（长块），下面直接退化成"整段替换"：**不做对齐，但一行不丢**。
 */

/**
 * diff 里的一行。`gap` 是"这里省略了 N 行" —— 它没有文字内容。
 *
 * <p>`line` 是**显示用的那个行号**：删除的行用旧文件的行号（它在新文件里已经没有了），
 * 其余用新文件的。来自统一 diff 的行自带它（{@code parseUnifiedDiff} 手上有 `@@`）；
 * 会话里那段改动不走统一 diff，改由后端给的起始行号（{@code DiffView} 的 `startLine`）现场数。
 */
export type DiffRow =
  | { kind: 'context'; text: string; line?: number }
  | { kind: 'added'; text: string; line?: number }
  | { kind: 'removed'; text: string; line?: number }
  | { kind: 'gap'; hidden: number }

/**
 * 每个改动块前后保留几行上下文。**3 行**是读 diff 的人刚好能认出
 * "这段在文件的什么位置"的量 —— 再多就只是把没改的部分也铺出来。
 */
const CONTEXT = 3

/**
 * 子序列搜索的规模上限（旧行数 × 新行数）。
 *
 * <p>超过它就不再找对齐，直接"全删 + 全加"。这一步是**故意**的：
 * 两个各一千行的块，光是那张表就要一百万格，而人看那份 diff 的收益并不会跟着涨。
 * 退化之后结果仍然是对的，只是不再标出哪些行其实没变。
 */
const MAX_CELLS = 250_000

/** 切行。**末尾那个换行不算一行**，否则每个以换行结尾的文件都会多出一个空行。 */
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
 * @param context 每个改动块前后保留几行。传 {@code Infinity} 表示不裁
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

  // dp[i][j] = a[i..] 和 b[j..] 的最长公共子序列长度。从右下往左上填
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
 * <p>模型给的 {@code old_string} 有时会带上一大段没动的上下文（它要保证唯一性，
 * 而那是对的）。原样铺出来的话，真正改的那两行会淹在三十行灰字里 ——
 * diff 就白给了。
 */
function withContext(rows: DiffRow[], context: number): DiffRow[] {
  // 没有改动时**一行都不裁**：裁的话整段会变成一条"省略了 N 行"，
  // 而人看到的就只剩下"什么都没显示"。两边一样本来就不该走到这里
  //（edit_file 会拒绝 old_string 和 new_string 相同的调用），但那是别人的保证，
  // 这一层不该依赖它
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
 * 一行里的一小段。{@code changed} 的那些段是**这一对行之间真的换了**的部分。
 *
 * <p>只在一对「删掉的行 / 加上的行」之间成立：一行整个是新增或整个是删除时
 * 没有"哪里变了"可谈，那种行不会有分段。
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
 * <p>它比行级那个上限小得多：一行里出现几千个词只可能是紧凑格式的压缩产物，
 * 那种行整行标出来反而好读。超过就退化成"整行都算换了"（返回 null）。
 */
const WORD_MAX_CELLS = 20_000

/**
 * 改动超过这个比例就不做词级标记。
 *
 * <p>两行几乎全不一样时，逐词标出来等于把整行都涂深 —— 那样和"整行换了"
 * 说的是同一件事，却多出几十个切换点，读起来更碎。Claude Code 用的也是这个阈值。
 */
const WORD_CHANGE_LIMIT = 0.4

/**
 * 切成"词 / 空白 / 单个标点"。
 *
 * <p>把空白单独成词是必要的：不然 `a = 1` 和 `a=1` 的差别会粘在 `a` 那个词里，
 * 标出来的是半个词。单个标点各自成词，则让 `>` 和 `>=`、`(` 和 `[` 这类
 * 一位差别也能单独标出来。
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
      // 代理对算一个词：切成两半的话，标出来的颜色会落在半个字上
      const code = text.codePointAt(at)!
      const length = code > 0xffff ? 2 : 1
      tokens.push(text.slice(at, at + length))
      at += length
    }
  }
  return tokens
}

/**
 * 两行之间哪几个词换了。**没有可标的就返回 null** —— 调用方退回普通渲染。
 *
 * <p>算法和行级那个一样（最短公共子序列），只是单位从"行"换成了"词"：
 * 对得上的词保持原样，对不上的两边都标成 changed，相邻的同类再并成一段。
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
 * 给整段 diff 配词级差异。返回的数组和 {@code rows} **等长**，没配上的位置是 null。
 *
 * <p>配对规则照 Claude Code：一段连续的删行后面紧跟一段连续的加行时，第 k 行对第 k 行；
 * 多出来的行没有对象，保持整行标注。中间隔着上下文的不配对 —— 那说明它们不是同一处改动。
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
