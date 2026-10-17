import type { DiffRow } from '@/lib/textDiff'

/**
 * 把一段**统一的 diff 正文**（`git diff` 那个形状）翻成渲染用的行。
 *
 * <h2>为什么不是"看见 `+` 就当新增"</h2>
 * 那个格式**有歧义**：正文里本来就有以 `--` 开头的一行，它被删掉之后是 `--- …`，
 * 长文件和文件头 `--- a/x` 一模一样。所以这里跟**位置**走，不跟前缀走：
 * 只有 `@@` 之后的那些行才算正文，`@@` 之前的一律是文件头。
 *
 * <h2>`@@` 同时给出两样东西</h2>
 * git 每个 hunk 只带几行上下文，两段之间隔着的东西它不写出来。那条 `@@ -a,b +c,d @@`
 * 里有两侧的起始行号：拿新文件那个算"跳过了几行"，再逐行往下走，给每一行标上
 * **旧号（删除行）或新号（其余）** —— 于是渲染那一层不用知道 diff 的格式。
 */
export function parseUnifiedDiff(text: string): DiffRow[] {
  const rows: DiffRow[] = []
  /** 旧、新文件各自走到第几行了。新文件那个还算"跳过了几行" */
  let oldLine = 0
  let newLine = 0
  /** 这一段 hunk 的起始行认不认得出来。**认不出来就不标号** —— 不画一个猜的号 */
  let numbered = false
  /** `@@` 之后才算正文 —— 见类注释里那个歧义 */
  let inHunk = false

  for (const line of text.split('\n')) {
    if (line.startsWith('diff --git ')) {
      inHunk = false
      numbered = false
      // 行号是**按文件**数的。一段 diff 里可以有几个文件（删一个、加一个），
      // 不归零的话下一个文件会带着上一个文件的行号算，于是画出一条假的"跳过了几行"
      oldLine = 0
      newLine = 0
      continue
    }

    if (line.startsWith('@@')) {
      inHunk = true
      const starts = hunkStarts(line)
      numbered = starts !== null
      if (starts !== null) {
        const hidden = starts.newStart - newLine - 1
        if (hidden > 0) {
          rows.push({ kind: 'gap', hidden })
        }
        oldLine = starts.oldStart - 1
        newLine = starts.newStart - 1
      }
      continue
    }

    if (!inHunk) {
      // 文件头那几行（`--- a/x`、`+++ b/x`、`index …`、`new file mode …`）不是正文
      continue
    }

    if (line.startsWith('+')) {
      // 加上的行标**新**行号；删掉的行标**旧**行号（它在新文件里已经没有了）
      rows.push({ kind: 'added', text: line.slice(1), ...(numbered ? { line: newLine + 1 } : {}) })
      newLine++
    } else if (line.startsWith('-')) {
      rows.push({ kind: 'removed', text: line.slice(1), ...(numbered ? { line: oldLine + 1 } : {}) })
      oldLine++
    } else if (line.startsWith(' ')) {
      rows.push({ kind: 'context', text: line.slice(1), ...(numbered ? { line: newLine + 1 } : {}) })
      oldLine++
      newLine++
    }
    // 剩下的是 `\ No newline at end of file` 这种 git 的注记，以及结尾那个空串：
    // 它们不是文件里的行，跟着正文一起算会让两侧的行号错位
  }

  return rows
}

/**
 * 把 `@@ -10,2 +10,3 @@` 里的**两侧起始行**取出来。
 *
 * <p>取不到就返回 null，代价只是**这一条跳过不画、这几行不标号**：
 * 行号算不出来，与其按猜的行号画，不如什么都不提。正文照常显示 ——
 * `@@` 开头只可能是 hunk 头（正文那一行总带着 ` `/`+`/`-` 前缀）。
 */
function hunkStarts(line: string): { oldStart: number; newStart: number } | null {
  const match = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line)
  return match === null
    ? null
    : { oldStart: Number(match[1]), newStart: Number(match[2]) }
}
