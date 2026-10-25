import { ClipboardPaste, Copy, Scissors } from 'lucide-react'
import { useRef, useState } from 'react'

import { ContextMenu, type MenuAction } from '@/components/workspace/ContextMenu'
import { copyText } from '@/lib/clipboard'
import { languageOfPath, tokenStyle, useHighlighted, useViewportHighlighting } from '@/lib/highlight'
import { lineNumberWidth } from '@/lib/lineNumbers'

/**
 * 带行号的只读代码块。
 *
 * 用在两处：截断的中栏文件，和裁决时并排看的两份。抽出来是为了让这两处的行号对齐、
 * 字号和"不把行号复制走"只有一份实现；会话流里的文件预览与 diff 另有行号列，因为
 * 每行还要挂增删记号。
 *
 * 行号与代码是两个并排容器，共用行高。行号列 `select-none`，横向滚动只滚代码列，
 * 行号钉住。Ctrl/Cmd+A 在这里只选代码列，不把整页左右栏一起选上。
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
}: {
  content: string
  /** 文件路径，只用来猜语言；不传就不高亮（会话流里围栏代码块没有路径） */
  path?: string | null
}) {
  const body = useRef<HTMLPreElement>(null)
  /** 右键菜单的位置和打开时选中的那段代码；菜单本身不重新读取选择 */
  const [menu, setMenu] = useState<{ x: number; y: number; text: string } | null>(null)
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
  const gutter = lineNumberWidth(String(shown.length).length, 1.5)

  // 菜单一按选择就没了，所以选中的文本在打开菜单时先记进 menu.text。
  const actions: MenuAction[] = [
    {
      label: '剪切',
      hint: 'Ctrl+X',
      icon: <Scissors className="size-3.5" />,
      // 代码是只读的，剪掉不落到文件上；保留这一项但标成不可用，避免假装能改
      disabled: true,
      onSelect: () => {},
    },
    {
      label: '复制',
      hint: 'Ctrl+C',
      icon: <Copy className="size-3.5" />,
      disabled: menu === null || menu.text === '',
      onSelect: () => {
        if (menu !== null) {
          void copyText(menu.text)
        }
      },
    },
    {
      label: '粘贴',
      hint: 'Ctrl+V',
      icon: <ClipboardPaste className="size-3.5" />,
      // 同上：这里没有可写入的光标位置，粘贴没有落点
      disabled: true,
      onSelect: () => {},
    },
  ]

  return (
    <>
      {/* 改这里的排版要跟着改 usePaneWidths 的 MIN_MIDDLE：那个"中栏能拖到多窄"照
          这里的实际排版量出来（内边距 + 行号列 + 间距），它是字体度量，代码里算不出来。
          tabIndex=-1 让点击代码后这一块能接住全局的 Ctrl+A 处理，但不进普通 Tab 顺序。
          data-select-all-* 是全局选择作用域的标记：只有标了 scope 的区域允许全选，
          内容选的是标了 content 的代码列，行号不进来。 */}
      <div
        tabIndex={-1}
        data-select-all-scope
        onMouseDown={(event) => {
          // 点代码就把焦点收到这一块，全局 Ctrl+A 处理才知道该选哪一段；焦点落在
          // body 上时按键会被当成"不在任何代码区里"而拦掉
          if (!event.currentTarget.contains(document.activeElement)) {
            event.currentTarget.focus({ preventScroll: true })
          }
        }}
        onContextMenu={(event) => {
          event.preventDefault()
          const selection = window.getSelection()
          const text =
            selection !== null &&
            !selection.isCollapsed &&
            event.currentTarget.contains(selection.anchorNode)
              ? selection.toString()
              : ''
          setMenu({ x: event.clientX, y: event.clientY, text })
        }}
        className="group/code relative font-mono text-[13px] leading-6 outline-none"
      >
      <div className="flex">
        <div
          aria-hidden
          className="shrink-0 select-none pr-6 text-right text-loom-faint"
          style={{ width: gutter }}
        >
          {shown.map((_, index) => (
            <div key={index}>{index + 1}</div>
          ))}
        </div>
        <pre ref={body} data-select-all-content className="min-w-0 flex-1 overflow-x-auto font-mono">
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

      {menu !== null && (
        <ContextMenu
          x={menu.x}
          y={menu.y}
          label="代码操作"
          actions={actions}
          onClose={() => setMenu(null)}
        />
      )}
    </>
  )
}

/**
 * 渲染上限。不是为了省流量（后端已限 1 MiB），是为了不让滚动卡住：
 * 两万行塞进 DOM 后的观感与"界面坏了"没有区别。
 */
const MAX_LINES = 3000

/** shiki 的 token。只用到这几个字段，所以写窄一点，不透传它的类型。 */
interface ThemedToken {
  content: string
  color?: string
  /** 位掩码；翻译成 CSS 的代码在 {@link tokenStyle} 里 */
  fontStyle?: number
}
