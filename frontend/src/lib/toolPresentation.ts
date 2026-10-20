/**
 * 工具结果里那份只给界面看的结构化数据。
 *
 * 后端把它作为 JSON 文本随 `ToolResult` 事件落库（见 `ToolOutcome.presentation`）：
 * 给模型看的文本是有损的，界面要画卡片就得有一份能还原结构的副本。模型看不到它，
 * 它也不参与任何判据。理由见
 * `docs/decisions/feature/2026-10-17-tool-presentation-for-ui.md`。
 *
 * 解析失败或认不出来返回 null，界面退回渲染那段文本。旧事件没有这一项，那是正常
 * 情况，不是错误。
 */

export interface WebSource {
  url: string
  title?: string
  snippet?: string
  siteName?: string
  date?: string
}

export type ToolPresentation =
  /**
   * 搜索。没有 truncated：工具砍掉多少条是写给模型的（"想看更多就缩小查询范围"），
   * 界面折叠成"展开其余 N 条"已经够了。
   */
  | { kind: 'web_search'; sources: WebSource[] }
  /**
   * 抓取：没有正文。正文连同给模型的提示词只给模型看；界面要标题、截断标记，
   * 以及调用行上可点的网址。
   */
  | { kind: 'web_fetch'; title?: string; truncated: boolean }
  /**
   * 一次改动的起始行号：diff 卡片拿它给每一行标号。
   *
   * 前端只有被替换的那一段，不知道它在文件里的绝对位置；那个数字只有真正读到
   * 文件的后端知道，所以随结果一起带过来。
   */
  | { kind: 'diff'; startLine: number }
  /**
   * 一次 `write_file` 的事实：新建还是覆盖、写了多少行。
   *
   * 不带正文：正文就在这次调用的参数里（界面本来就有），再存一份等于同一内容在
   * 事件流里出现两次。新建时界面用参数里的 `content` 画预览；覆盖时旧内容已经
   * 没了，仍然只显示结论。
   */
  | { kind: 'write'; created: boolean; lines: number }

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value : undefined
}

function source(value: unknown): WebSource | null {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const node = value as Record<string, unknown>
  const url = text(node.url)
  if (url === undefined) {
    return null
  }
  const title = text(node.title)
  const snippet = text(node.snippet)
  const siteName = text(node.siteName)
  const date = text(node.date)
  return {
    url,
    ...(title === undefined ? {} : { title }),
    ...(snippet === undefined ? {} : { snippet }),
    ...(siteName === undefined ? {} : { siteName }),
    ...(date === undefined ? {} : { date }),
  }
}

export function parsePresentation(raw: unknown): ToolPresentation | null {
  if (typeof raw !== 'string' || raw.trim() === '') {
    return null
  }
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const node = value as Record<string, unknown>
  if (node.kind === 'web_search') {
    if (!Array.isArray(node.sources)) {
      return null
    }
    const sources: WebSource[] = []
    for (const item of node.sources) {
      const parsed = source(item)
      if (parsed === null) {
        return null
      }
      sources.push(parsed)
    }
    return { kind: 'web_search', sources }
  }
  if (node.kind === 'diff') {
    const startLine = node.startLine
    if (typeof startLine !== 'number' || !Number.isInteger(startLine) || startLine < 1) {
      return null
    }
    return { kind: 'diff', startLine }
  }
  if (node.kind === 'write') {
    const lines = node.lines
    if (typeof node.created !== 'boolean'
      || typeof lines !== 'number' || !Number.isInteger(lines) || lines < 0) {
      return null
    }
    return { kind: 'write', created: node.created, lines }
  }
  if (node.kind === 'web_fetch') {
    const title = text(node.title)
    return {
      kind: 'web_fetch',
      truncated: node.truncated === true,
      ...(title === undefined ? {} : { title }),
    }
  }
  return null
}

/** 一个外部网址（只认 http/https）；不是就返回 null。 */
export function externalUrl(value: string): string | null {
  try {
    const { protocol } = new URL(value)
    return protocol === 'http:' || protocol === 'https:' ? value : null
  } catch {
    return null
  }
}

/** 显示用的域名：解析不出来就原样返回。 */
export function hostOf(url: string): string {
  try {
    return new URL(url).hostname
  } catch {
    return url
  }
}

/** 只要日期那一段（服务商给的是 RFC 3339）；认不出来就原样返回。 */
export function dayOf(value: string | undefined): string | undefined {
  return value?.match(/^\d{4}-\d{2}-\d{2}/)?.[0] ?? value
}
