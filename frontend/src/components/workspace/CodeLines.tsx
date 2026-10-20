import { useRef } from 'react'

import { CopyButton } from '@/components/workspace/CopyButton'
import { languageOfPath, tokenStyle, useHighlighted, useViewportHighlighting } from '@/lib/highlight'

/**
 * 带行号的代码块。
 *
 * 两个用处：中栏看文件、裁决时并排看两份。抽出来是为了让行号对齐、字号和"不把
 * 行号复制走"只有一份实现。
 *
 * 行号与代码是两个并排容器，共用行高。复制按钮读代码列的 `textContent`，行号不在
 * 那一列里，所以复制结果与屏幕上的代码逐字相同（同一个 `pre` 里靠 `select-none`
 * 只挡鼠标选中，挡不住取文本）。横向滚动只滚代码列，行号钉住。
 *
 * 高亮按行着色：`codeToTokens` 按行给 token，每个 token 一个 `span`。拿整段 HTML
 * 再按行切会切坏跨行 `span`。
 *
 * `path` 只用来判断语言。认不出扩展名或还没加载好时原样显示（见
 * {@link useHighlighted}）：没有颜色也要能读。
 */
export function CodeLines({
  content,
  path = null,
  className = '',
  copyable = true,
}: {
  content: string
  /** 文件路径，只用来猜语言；不传就不高亮（会话流里围栏代码块没有路径） */
  path?: string | null
  className?: string
  /** 复制按钮。裁决那处并排的两份不给：那里要的是"哪边是谁的"，不是"抄走它" */
  copyable?: boolean
}) {
  const body = useRef<HTMLPreElement>(null)
  // 这一块滚到了才上色：一段流水里有几十个代码块，一次只看得到一两个。
  const seen = useViewportHighlighting(body)
  const tokens = useHighlighted(content, languageOfPath(path), seen)
  const plain = content.split('\n')

  // 行以高亮结果为准：两边行数在文件以换行结尾时可能差一个（末尾空行算不算
  // 一行），各自数会出现"第 12 行的颜色是第 13 行的"。
  const lines = tokens ?? plain
  const shown = lines.slice(0, MAX_LINES)

  // 行号列宽度按最长行号算；写死宽度会让三位数文件的行号贴着代码，
  // 四位数又被截掉。
  const gutter = `${Math.max(2, String(shown.length).length)}ch`

  return (
    // 改这里的排版要跟着改 usePaneWidths 的 MIN_MIDDLE：那个"中栏能拖到多窄"照
    // 这里的实际排版量出来（内边距 + 行号列 + 间距），它是字体度量，代码里算不出来。
    <div className={`group/code relative font-mono text-[13px] leading-6 ${className}`}>
      {copyable && <CopyButton contentRef={body} className="absolute right-0 top-0 z-10" />}
      <div className="flex">
        <div
          aria-hidden
          className="shrink-0 select-none pr-6 text-right text-loom-faint"
          style={{ width: `calc(${gutter} + 1.5rem)` }}
        >
          {shown.map((_, index) => (
            <div key={index}>{index + 1}</div>
          ))}
        </div>
        <pre ref={body} className="min-w-0 flex-1 overflow-x-auto font-mono">
          {shown.map((line, index) => (
            <div key={index} className="whitespace-pre">
              {tokens === null ? (
                (line as string) || ' '
              ) : (
                (line as ThemedToken[]).map((token, at) => (
                  <span key={at} style={tokenStyle(token)}>
                    {token.content}
                  </span>
                ))
              )}
            </div>
          ))}
        </pre>
      </div>
      {plain.length > MAX_LINES && (
        <p className="mt-4 font-sans text-xs text-loom-faint">
          这个文件很长，只显示了前 {MAX_LINES} 行。
        </p>
      )}
    </div>
  )
}

/**
 * 渲染上限。不是为了省流量（后端已限 1 MiB），是为了不让滚动卡住：
 * 两万行塞进 DOM 后的观感与"界面坏了"没有区别。
 */
export const MAX_LINES = 3000

/** shiki 的 token。只用到这几个字段，所以写窄一点，不透传它的类型。 */
interface ThemedToken {
  content: string
  color?: string
  /** 位掩码；翻译成 CSS 的代码在 {@link tokenStyle} 里 */
  fontStyle?: number
}
