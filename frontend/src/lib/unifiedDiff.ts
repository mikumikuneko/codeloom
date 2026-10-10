import type { DiffRow } from '@/lib/textDiff'

/**
 * 把一段**统一的 diff 正文**（`git diff` 那个形状）翻成渲染用的行。
 *
 * <h2>为什么不是"看见 `+` 就当新增"</h2>
 * 那个格式**有歧义**：正文里本来就有以 `--` 开头的一行，它被删掉之后是 `--- …`，
 * 长文件和文件头 `--- a/x` 一模一样。所以这里跟**位置**走，不跟前缀走：
 * 只有 `@@` 之后的那些行才算正文，`@@` 之前的一律是文件头。
 *
 * <h2>`@@` 到上一段之间那段没显示的行，翻成一条"跳过了几行"</h2>
 * git 每个 hunk 只带几行上下文，两段之间隔着的东西它不写出来。那条 `@@ -a,b +c,d @@`
 * 里有新文件的起始行号，够算出跳过了多少行 —— 于是渲染那一层不用知道 diff 的格式。
 */
export function parseUnifiedDiff(text: string): DiffRow[] {
  const rows: DiffRow[] = []
  /** 已经走到新文件的第几行。算"跳过了几行"要用它 */
  let newLine = 0
  /** `@@` 之后才算正文 —— 见类注释里那个歧义 */
  let inHunk = false

  for (const line of text.split('\n')) {
    if (line.startsWith('diff --git ')) {
      inHunk = false
      // 行号是**按文件**数的。一段 diff 里可以有几个文件（删一个、加一个），
      // 不归零的话下一个文件会带着上一个文件的行号算，于是画出一条假的"跳过了几行"
      newLine = 0
      continue
    }

    if (line.startsWith('@@')) {
      inHunk = true
      const start = hunkStart(line)
      if (start !== null) {
        const hidden = start - newLine - 1
        if (hidden > 0) {
          rows.push({ kind: 'gap', hidden })
        }
        newLine = start - 1
      }
      continue
    }

    if (!inHunk) {
      // 文件头那几行（`--- a/x`、`+++ b/x`、`index …`、`new file mode …`）不是正文
      continue
    }

    if (line.startsWith('+')) {
      rows.push({ kind: 'added', text: line.slice(1) })
      newLine++
    } else if (line.startsWith('-')) {
      rows.push({ kind: 'removed', text: line.slice(1) })
    } else if (line.startsWith(' ')) {
      rows.push({ kind: 'context', text: line.slice(1) })
      newLine++
    }
    // 剩下的是 `\ No newline at end of file` 这种 git 的注记，以及结尾那个空串：
    // 它们不是文件里的行，跟着正文一起算会让两侧的行号错位
  }

  return rows
}

/**
 * 把 `@@ -1,2 +3,4 @@` 里的**新文件起始行**取出来（上面那个 3）。
 *
 * <p>取不到就返回 null，代价只是**这一条跳过不画**：行号算不出来，
 * 与其按猜的行号画一段"跳过了几行"，不如什么都不提。正文照常显示 ——
 * `@@` 开头只可能是 hunk 头（正文那一行总带着 ` `/`+`/`-` 前缀）。
 */
function hunkStart(line: string): number | null {
  const match = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line)
  return match === null ? null : Number(match[1])
}
