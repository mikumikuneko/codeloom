import type { DiffRow } from '@/lib/textDiff'

/**
 * 把统一 diff 正文（`git diff` 的形状）翻成渲染用的行。
 *
 * 不能看见 `+` 就当新增：格式有歧义，正文里以 `--` 开头的一行被删掉后是 `--- …`，
 * 与文件头 `--- a/x` 一模一样。所以跟位置走：只有 `@@` 之后的行才算正文，之前
 * 一律是文件头。
 *
 * `@@ -a,b +c,d @@` 给出两侧起始行号：拿新文件那个算"跳过了几行"，再逐行往下走，
 * 给每行标旧号（删除行）或新号（其余），渲染层不需要知道 diff 格式。
 */
export function parseUnifiedDiff(text: string): DiffRow[] {
  const rows: DiffRow[] = []
  /** 旧、新文件各自走到第几行；新文件那个还用来算"跳过了几行" */
  let oldLine = 0
  let newLine = 0
  /** 这一段 hunk 的起始行是否认得出来；认不出来就不标号，不画猜的号 */
  let numbered = false
  /** `@@` 之后才算正文，见文件头注释里的歧义 */
  let inHunk = false

  for (const line of text.split('\n')) {
    if (line.startsWith('diff --git ')) {
      inHunk = false
      numbered = false
      // 行号按文件数。一段 diff 里可以有几个文件（删一个、加一个），不归零会让
      // 下一个文件带着上一个的行号，画出假的"跳过了几行"。
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
      // 文件头几行（`--- a/x`、`+++ b/x`、`index …`、`new file mode …`）不是正文。
      continue
    }

    if (line.startsWith('+')) {
      // 加上的行标新行号；删掉的行标旧行号（它在新文件里已经没有了）。
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
    // 剩下的是 git 注记（如 `\ No newline at end of file`）和结尾空串：它们不是
    // 文件里的行，算进去会让两侧行号错位。
  }

  return rows
}

/**
 * 取出 `@@ -10,2 +10,3 @@` 里的两侧起始行。
 *
 * 取不到返回 null，代价只是跳过不画、这几行不标号：算不出来就不按猜的行号画。
 * 正文照常显示：`@@` 开头只可能是 hunk 头（正文行总带空格、`+` 或 `-` 前缀）。
 */
function hunkStarts(line: string): { oldStart: number; newStart: number } | null {
  const match = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line)
  return match === null
    ? null
    : { oldStart: Number(match[1]), newStart: Number(match[2]) }
}
