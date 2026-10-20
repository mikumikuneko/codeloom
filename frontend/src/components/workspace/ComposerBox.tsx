import { ArrowUp } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'

/**
 * 输入框最多长到几行，再多就内部滚动，不再往上顶。
 *
 * 15 行（376px）够写一段长提示，又留出上面看会话流的地方；23 行（568px）会吃掉
 * 右栏大半高度。
 */
const MAX_LINES = 15

/** 一行实际高度（`leading-6` = 24px），以及上下各一份 `py-2`。 */
const LINE_HEIGHT_PX = 24
const PADDING_PX = 16
const MAX_HEIGHT_PX = MAX_LINES * LINE_HEIGHT_PX + PADDING_PX

/**
 * 右栏底部「说话的地方」，会话和聊天室共用。
 *
 * 输入框是一个圆角容器，不是"输入框 + 旁边一个按钮"：说话的地方是一个盒子，发送
 * 在盒子里面。Input 与 Button 并排读起来像一张要填的表，而这个 pane 是你对着
 * agent 说话的地方。
 *
 * 盒子里第二行整行靠右：调用方塞进来的控件贴着发送键。控件不在输入框外面，因为
 * "用哪个模型"是这句话的一部分，不是前置条件。没有控件时那一行只有发送键，但不
 * 省略：会话和聊天室两处的盒子形状必须一模一样，否则同一个东西会有两份实现。
 *
 * 多行的取舍见 `docs/decisions/feature/2026-10-07-composer-is-multiline.md`。
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
   * 输入框一开始放着什么。
   *
   * 只在挂载时读一次，之后归它自己（初始值，不是受控值）。回滚用它：退掉的那句话
   * 该回到这里，而不是消失。
   */
  initialText?: string
  /** 整个输入区不可用（连接断了之类）。只影响能不能输入 */
  disabled?: boolean
  /** 能不能发。默认能；"还没选模型"时调用方传 false，但输入框照样能打字 */
  canSend?: boolean
  /** 盒子上方的一行提示（发送失败之类）。样式由调用方给 */
  notice?: ReactNode
  /**
   * 把这句话发出去。不抛异常 = 成功，输入框随之清空。
   *
   * 失败请自己吞掉并把提示交给 {@link notice}：这样没发出去的那句话会留在输入框里，
   * 而那是重试代价最小的地方。清空之后再让用户重打一遍，最容易让人放弃。
   */
  onSend: (text: string) => void | Promise<void>
  /**
   * 输入框为空时按了 Esc。调用方拿它接"连按两下"（见 `useDoublePress`）。
   *
   * 有内容时不叫它：那时 Esc 该是"清掉我刚打的字"，一个键只能有一个意思。
   */
  onEscape?: () => void
  /** 发送键左边那一行控件 */
  children?: ReactNode
}) {
  const [text, setText] = useState(initialText ?? '')
  const [busy, setBusy] = useState(false)
  const box = useRef<HTMLTextAreaElement>(null)

  /**
   * 插了换行之后光标该落在哪儿；非 null 时由下面的 effect 消费。
   *
   * 不能在这里直接设：`setText` 之后 React 还要把新值写回 DOM，写回那一下会把
   * 光标顶到末尾，得等它写完再摆。
   */
  const caretAfter = useRef<number | null>(null)

  /**
   * 在光标处插一个换行。
   *
   * 浏览器不替 Ctrl+Enter 做这件事（实测 Chromium：普通回车与 Shift+回车会插换行，
   * Ctrl / Alt / Meta+回车什么都不做），所以必须自己来。
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
   * 先归零再量：不归零量到的是上一次的高度，删字之后框缩不回去。量到上限就不再长，
   * 再多由 textarea 自己滚。
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
      {/* 留白比通用输入框宽：这里是一行字下面一行控件，太窄会挤成一坨，
          看起来像待填的表单 */}
      <div className="rounded-xl border border-input bg-raise px-2.5 py-3 transition-colors focus-within:border-ring/60">
        {/* 是 textarea 不是 input：单行框装不下换行，Ctrl+Enter 换行在它上面
            做不到（不是没绑快捷键） */}
        <textarea
          ref={box}
          rows={1}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
          style={{ maxHeight: MAX_HEIGHT_PX }}
          onKeyDown={(event) => {
            // 输入框为空时按 Esc 才交出去；有内容时人想的是"清掉我刚打的字"，
            // 一个键只能有一个意思。
            if (event.key === 'Escape' && text === '') {
              onEscape?.()
              return
            }
            if (event.key !== 'Enter') {
              return
            }
            // 输入法组字时的回车是"选字"不是"发送"；不挡的话，用拼音打中文
            // 每选一个词就发一次，而屏幕上还是那串字母。
            if (event.nativeEvent.isComposing) {
              return
            }
            // Ctrl / Cmd + 回车 = 换行；自己插，理由见 breakLine。
            if (event.ctrlKey || event.metaKey) {
              event.preventDefault()
              breakLine()
              return
            }
            // Shift + 回车也是换行：放它过去，浏览器自己会插。
            if (event.shiftKey) {
              return
            }
            // 其余 Enter = 发送。textarea 不会自动提交表单，少了这一句
            // 回车就只换行、发不出去。
            event.preventDefault()
            void submit()
          }}
          className="w-full resize-none overflow-y-auto bg-transparent px-1.5 py-2 text-sm leading-6 outline-none placeholder:text-loom-faint disabled:opacity-50"
        />

        <div className="flex items-center justify-end gap-1.5 px-0.5">
          {children}
          {/* 发送是看得见的圆钮，不是两个淡灰字：它是盒子里唯一的动作，
              能按和不能按时都得有明确样子。文字"发送"淡下去像装饰，亮起来像链接 */}
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
