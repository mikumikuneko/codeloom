import { useRef } from 'react'

import { CopyButton } from '@/components/workspace/CopyButton'
import { languageOfPath, tokenStyle, useHighlighted, useViewportHighlighting } from '@/lib/highlight'

/**
 * 带行号的代码块。
 *
 * <p>抽出来是因为它有三个用处：中栏看一个文件、裁决时并排看两份、以及会话流里的代码块。
 * 各写一遍的话，行号的对齐方式、字号、以及"不把行号复制走"这几点迟早会在其中一处走样 ——
 * 而那种不一致读者一眼看得出来，只是说不出是哪里不对。
 *
 * <h2>行号在一列，代码在另一列 —— 不是一个 `pre` 里拼出来的</h2>
 * 行号和代码是**两个并排的容器**，共用同一个行高。这样做有两个好处，
 * 而两个都是"结构上成立"，不是靠样式约定：
 *
 * <ul>
 *   <li><b>复制不会带上行号。</b>复制按钮读的是代码那一列的 {@code textContent} ——
 *       行号根本不在那一列里，所以"复制到的东西"和"屏幕上那段代码"逐字相同。
 *       （放在同一个 `pre` 里、靠 `select-none` 挡住的写法只能挡住鼠标选中，
 *       挡不住取文本。）</li>
 *   <li><b>横向滚的时候行号不动。</b>滚的是代码那一列。用 IDEA 的人对这个手感是熟的：
 *       一行很长的时候，你想看的是行号还钉在那儿。</li>
 * </ul>
 *
 * <h2>高亮按行着色，不是把整段 HTML 塞进来</h2>
 * {@code codeToTokens} 按行给 token，每个 token 一个 {@code span} 上色。
 * 拿整段 HTML 再按行切开的话，跨行的 span 会被切坏 —— 那是这类界面上出了名的一类错。
 *
 * <p>{@code path} 只用来判断语言。认不出扩展名、或者还没加载好时，
 * 原样显示（见 {@link useHighlighted}）—— <strong>没有颜色也要能读</strong>。
 */
export function CodeLines({
  content,
  path = null,
  className = '',
  copyable = true,
}: {
  content: string
  /** 文件路径，只用来猜语言；不传就是不高亮（会话流里那些围栏代码块就没有路径） */
  path?: string | null
  className?: string
  /** 复制按钮。**裁决那处并排的两份不给** —— 那里要的是"哪边是谁的"，不是"抄走它" */
  copyable?: boolean
}) {
  const body = useRef<HTMLPreElement>(null)
  // 这一块**滚到了才上色**：一段流水里有几十个代码块，而人一次只看得到一两个
  const seen = useViewportHighlighting(body)
  const tokens = useHighlighted(content, languageOfPath(path), seen)
  const plain = content.split('\n')

  // 行以**高亮结果为准**（有的话）：两边的行数在某些边界上会差一个（文件以换行结尾时
  // 末尾那个空行算不算一行），各自数各自的就会出现"第 12 行的颜色是第 13 行的"
  const lines = tokens ?? plain
  const shown = lines.slice(0, MAX_LINES)

  // 行号那一列多宽：按最长的那个行号算。写死一个宽度的话，
  // 三位数的文件里行号会贴着代码，四位数又会被截掉
  const gutter = `${Math.max(2, String(shown.length).length)}ch`

  return (
    <div className={`group/code relative font-mono text-[13px] leading-6 ${className}`}>
      {copyable && <CopyButton contentRef={body} className="absolute right-0 top-0 z-10" />}
      <div className="flex">
        <div
          aria-hidden
          className="shrink-0 select-none pr-4 text-right text-loom-faint"
          style={{ width: `calc(${gutter} + 1rem)` }}
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
 * 渲染上限。**不是为了省流量**（后端已经限了 1 MiB），是为了不让滚动卡住 ——
 * 两万行塞进 DOM 之后卡住的观感，和"这个界面坏了"没有区别。
 */
export const MAX_LINES = 3000

/** shiki 的 token —— 只用到下面那几个字段，所以在这里写窄一点，不透传它的类型。 */
interface ThemedToken {
  content: string
  color?: string
  /** 位掩码。翻译成 CSS 的那一段在 {@link tokenStyle} 里 */
  fontStyle?: number
}
