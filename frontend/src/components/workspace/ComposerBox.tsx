import { ArrowUp } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'

/**
 * 输入框最多长到几行 —— 再多就**内部滚动**，不再往上顶。
 *
 * <p>15：另外那批 UI 参考（Codex、pivot-ui）那边能长到 23 行，但**我们的右栏比它们窄** ——
 * 23 行（568px）会吃掉这一栏大半的高度，把上面正在看的会话流挤没。15 行（376px）够写
 * 一段长提示，又留着上面那块。
 */
const MAX_LINES = 15

/** 一行的实际高度（{@code leading-6} = 24px），以及上下各一份 {@code py-2}。 */
const LINE_HEIGHT_PX = 24
const PADDING_PX = 16
const MAX_HEIGHT_PX = MAX_LINES * LINE_HEIGHT_PX + PADDING_PX

/**
 * 右栏底部那个「说话的地方」—— 会话和聊天室**共用**这一个。
 *
 * <h2>输入框是一个圆角容器，不是"一个输入框 + 旁边一个按钮"</h2>
 * 这是对着三份参考实现（Codex 的两个版本、pivot-ui）看下来**唯一完全一致**的一点：
 * 说话的地方是一个盒子，发送在盒子里面。
 *
 * <p>而 {@code Input} 和 {@code Button} 并排，读起来是"一张要填的表"——
 * 左边一栏填内容、右边一个提交键。这个 pane 不是表，是**你对着 agent 说话的地方**，
 * 所以它该长得像一个说话的地方。
 *
 * <h2>控件那一行</h2>
 * 盒子里面还有第二行，**整行靠右**：调用方塞进来的控件贴着发送键，一起落在右下角。
 * 控件不在输入框外面 —— 因为"用哪个模型"是**这句话的一部分**，不是它的前置条件。
 *
 * <p>从前这一行是"控件靠左、发送键用 {@code ml-auto} 顶到最右"，中间空一大截。
 * 那样读起来控件和发送是**两件不相干的事**，而它们其实是同一次动作的两半：
 * 用什么、说什么。挤在右下角才是一个动作。
 *
 * <p>没有控件时那一行只有发送键（{@code justify-end} 让它照样靠右），**不省略这一行**：
 * 会话和聊天室两处的盒子形状必须一模一样，否则又变成"同一个东西写两遍，其中一遍必然写错"
 * （见 {@code LivePane} 的类注释，那一课已经上过一次了）。
 */
export function ComposerBox({
  placeholder,
  sendLabel = '发送',
  busyLabel = '发送中…',
  disabled = false,
  canSend = true,
  notice,
  onSend,
  onEscape,
  initialText,
  children,
}: {
  placeholder: string
  sendLabel?: string
  busyLabel?: string
  /**
   * 输入框**一开始**放着什么。
   *
   * <p>只在挂载时读一次 —— 之后那个框归它自己，调用方再改这个值也不影响它
   * （初始值，不是受控值）。回滚用它：退掉的那句话该回到这里，而不是消失。
   */
  initialText?: string
  /** 整个输入区不可用（连接断了之类）。**只影响能不能输入** */
  disabled?: boolean
  /** 能不能发。默认能 —— 例如"还没选模型"时调用方传 false，但输入框照样能打字 */
  canSend?: boolean
  /** 盒子上方的一行提示（发送失败之类）。样式由调用方给 */
  notice?: ReactNode
  /**
   * 把这句话发出去。**不抛异常 = 成功**，输入框随之清空。
   *
   * <p>失败请自己吞掉并把提示交给 {@link notice}：这样"没发出去"的那句话
   * 会留在输入框里，而那是重试代价最小的地方 —— 清空之后再让用户重打一遍，
   * 是最容易让人放弃的一种失败。
   */
  onSend: (text: string) => void | Promise<void>
  /**
   * **输入框为空时**按了 Esc。调用方拿它接"连按两下"（见 {@code useDoublePress}）。
   *
   * <p>有内容时不叫它 —— 那种情况下 Esc 该是"清掉我刚打的字"，而一个键只能有一个意思。
   */
  onEscape?: () => void
  /** 发送键左边那一行控件 */
  children?: ReactNode
}) {
  const [text, setText] = useState(initialText ?? '')
  const [busy, setBusy] = useState(false)
  const box = useRef<HTMLTextAreaElement>(null)

  /**
   * 插了换行之后，光标该落在哪儿。**不是 null 时由下面那个 effect 消费掉**。
   *
   * <p>不能在这儿直接设：`setText` 之后 React 还要把新值写回 DOM，而写回去那一下
   * 会把光标顶到末尾 —— 得等它写完再摆。
   */
  const caretAfter = useRef<number | null>(null)

  /**
   * 在光标处插一个换行。
   *
   * <p>**浏览器不替 Ctrl+Enter 做这件事**：实测（Chromium）普通回车和 Shift+回车会插换行，
   * 而 Ctrl / Alt / Meta+回车什么都不做。所以这一下必须自己来。
   */
  function breakLine() {
    const element = box.current
    if (!element) {
      return
    }
    const at = element.selectionStart
    setText(`${text.slice(0, at)}\n${text.slice(element.selectionEnd)}`)
    caretAfter.current = at + 1
  }

  /**
   * 框的高度跟着内容走。
   *
   * <p>**先归零再量**：不归零的话，量到的是"上一次那个高度"，于是删字之后框缩不回去。
   * 量到上限就不再长 —— 再多的行由 textarea 自己滚。
   */
  useEffect(() => {
    const element = box.current
    if (!element) {
      return
    }
    element.style.height = 'auto'
    element.style.height = `${Math.min(element.scrollHeight, MAX_HEIGHT_PX)}px`
    if (caretAfter.current !== null) {
      element.selectionStart = caretAfter.current
      element.selectionEnd = caretAfter.current
      caretAfter.current = null
    }
  }, [text])

  async function submit() {
    const words = text.trim()
    if (words === '' || busy || disabled || !canSend) {
      return
    }
    setBusy(true)
    try {
      await onSend(words)
      setText('')
    } finally {
      setBusy(false)
    }
  }

  const sendable = canSend && !disabled && !busy && text.trim() !== ''

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
      className="shrink-0 p-3"
    >
      {notice}

      {/* focus-within 用那个青：全站只有焦点环和这里借它，所以"焦点在哪"是同一套语言 */}
      {/* 留白给得比通用输入框宽：这里是**对着 agent 说话的地方**，一行字下面是
          一行控件，太窄的话那三样挤成一坨，看起来像个待填的表单 */}
      <div className="rounded-xl border border-input bg-raise px-2.5 py-3 transition-colors focus-within:border-ring/60">
        {/* 是 textarea 不是 input：**单行框在 HTML 里根本装不下换行**，
            于是 Ctrl+Enter 换行这件事在它上面做不到（不是没绑快捷键） */}
        <textarea
          ref={box}
          rows={1}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
          style={{ maxHeight: MAX_HEIGHT_PX }}
          onKeyDown={(event) => {
            // **输入框为空时**按 Esc 才交出去。有内容时不交 —— 那种情况下人想的是
            // "清掉我刚打的字"，而不是"打开回滚"，而这个键只能有一个意思
            if (event.key === 'Escape' && text === '') {
              onEscape?.()
              return
            }
            if (event.key !== 'Enter') {
              return
            }
            // **正在用输入法组字时，这一下回车是"选字"不是"发送"。**
            // 不挡的话，用拼音打中文每选一个词就发出去一次 —— 而那时候
            // 屏幕上还是那串字母，看着像"乱码被发出去了"
            if (event.nativeEvent.isComposing) {
              return
            }
            // Ctrl / Cmd + 回车 = 换行。**自己插**，理由见 breakLine
            if (event.ctrlKey || event.metaKey) {
              event.preventDefault()
              breakLine()
              return
            }
            // Shift + 回车也是换行：**放它过去**，这一种浏览器自己会插
            if (event.shiftKey) {
              return
            }
            // 其余的 Enter = 发送。**textarea 不像单行框那样会自动提交表单**，
            // 所以这里必须自己来 —— 少了这一句，回车就只换行、发不出去
            event.preventDefault()
            void submit()
          }}
          className="w-full resize-none overflow-y-auto bg-transparent px-1.5 py-2 text-sm leading-6 outline-none placeholder:text-loom-faint disabled:opacity-50"
        />

        <div className="flex items-center justify-end gap-1.5 px-0.5">
          {children}
          {/* 发送是一个**看得见的圆钮**，不是两个淡灰色的字：
              它是这个盒子里唯一的动作，而该动作在能按和不能按时都得有个明确的样子。
              文字"发送"做不到 —— 淡下去像装饰，亮起来像链接 */}
          <button
            type="submit"
            disabled={!sendable}
            title={busy ? busyLabel : sendLabel}
            aria-label={busy ? busyLabel : sendLabel}
            className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground transition-colors hover:bg-primary/85 disabled:pointer-events-none disabled:bg-secondary disabled:text-loom-faint"
          >
            <ArrowUp className="size-4" />
          </button>
        </div>
      </div>
    </form>
  )
}
