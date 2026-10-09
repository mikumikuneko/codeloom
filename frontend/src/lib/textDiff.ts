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

/** diff 里的一行。`gap` 是"这里省略了 N 行" —— 它没有文字内容。 */
export type DiffRow =
  | { kind: 'context'; text: string }
  | { kind: 'added'; text: string }
  | { kind: 'removed'; text: string }
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

/** 加了几行、删了几行 —— 就是工具行右侧那对数字。 */
export function diffTotals(rows: DiffRow[]): { added: number; removed: number } {
  let added = 0
  let removed = 0
  for (const row of rows) {
    if (row.kind === 'added') added += 1
    if (row.kind === 'removed') removed += 1
  }
  return { added, removed }
}
