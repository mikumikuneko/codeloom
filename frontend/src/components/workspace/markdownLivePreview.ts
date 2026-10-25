import { syntaxTree } from '@codemirror/language'
import type { Extension, Range } from '@codemirror/state'
import {
  Decoration,
  EditorView,
  ViewPlugin,
  WidgetType,
  type DecorationSet,
  type ViewUpdate,
} from '@codemirror/view'

/**
 * Markdown 的所见即所得：一块只有一张视图。
 *
 * 光标所在的行显示源码，其余行隐藏 Markdown 记号并套上排版——标题是标题、粗体
 * 是粗体、链接只剩链接文字、列表是圆点，观感和 Typora 一致。点回某一处，那一行
 * 的 `###`、`**` 又露出来，可以直接改。
 *
 * 记号用 `Decoration.replace` 藏起来，光标因此必须视作原子区间，否则点进被藏住的
 * 记号会在中间落下一个看不见的光标。行样式只在**当前视口**里算：一份一兆的文档
 * 每敲一个字遍历整棵树是不必要的开销，滚动时重建一次就够。
 *
 * 表格和图片不做渲染：前者要把管道符重排成网格，后者要处理尺寸和加载失败，都
 * 属于另一个量级的改动；它们的源码照常显示。
 */

/** 视口外多算一点，滚动一屏不至于正好从没样式的地方开始。 */
const VIEWPORT_MARGIN = 1000

const PREVIEW_THEME = EditorView.theme(
  {
    // 没有行号列，正文自己留左边距。
    //
    // 选择器必须比基础主题的 `.cm-content` 多一层：两份主题都是模块级常量，
    // 注入顺序跟扩展数组里的先后无关，权重相同时会输掉，正文就贴着栏边排。
    '.cm-scroller > .cm-content': {
      paddingLeft: '16px',
      color: 'var(--foreground)',
    },
    // 标题的字号和行高。选择器带两层，压过基础主题里的 `.cm-line`
    '.cm-line.cm-md-h1': { fontSize: '22px', lineHeight: '34px', fontWeight: '600', paddingTop: '8px' },
    '.cm-line.cm-md-h2': { fontSize: '19px', lineHeight: '32px', fontWeight: '600', paddingTop: '8px' },
    '.cm-line.cm-md-h3': { fontSize: '17px', lineHeight: '30px', fontWeight: '600', paddingTop: '6px' },
    '.cm-line.cm-md-h4': { fontSize: '15px', fontWeight: '600', paddingTop: '4px' },
    '.cm-line.cm-md-h5, .cm-line.cm-md-h6': { fontWeight: '600', paddingTop: '2px' },
    '.cm-md-marker': { color: 'var(--color-loom-faint)' },
    '.cm-md-strong': { fontWeight: '700' },
    '.cm-md-em': { fontStyle: 'italic' },
    '.cm-md-strike': { textDecoration: 'line-through', color: 'var(--muted-foreground)' },
    '.cm-md-code': {
      fontFamily: 'var(--font-mono)',
      fontSize: '0.88em',
      background: 'var(--secondary)',
      border: '1px solid var(--border)',
      borderRadius: '3px',
      padding: '0 3px',
    },
    '.cm-line.cm-md-quote': {
      borderLeft: '2px solid var(--border)',
      paddingLeft: '12px',
      color: 'var(--muted-foreground)',
    },
    '.cm-line.cm-md-code-line, .cm-line.cm-md-fence': {
      background: 'var(--secondary)',
      fontFamily: 'var(--font-mono)',
    },
    '.cm-line.cm-md-fence': { color: 'transparent' },
    '.cm-md-link': {
      textDecoration: 'underline',
      textDecorationStyle: 'dotted',
      textUnderlineOffset: '3px',
    },
    '.cm-md-bullet': { color: 'var(--muted-foreground)' },
    '.cm-md-checkbox': { color: 'var(--muted-foreground)' },
    '.cm-md-rule': {
      display: 'block',
      height: '1px',
      background: 'var(--border)',
      margin: '4px 0',
    },
  },
  { dark: true },
)

class BulletWidget extends WidgetType {
  eq() {
    return true
  }

  toDOM() {
    const element = document.createElement('span')
    element.className = 'cm-md-bullet'
    element.textContent = '•'
    return element
  }
}

class CheckboxWidget extends WidgetType {
  private readonly checked: boolean

  constructor(checked: boolean) {
    super()
    this.checked = checked
  }

  eq(other: CheckboxWidget) {
    return other.checked === this.checked
  }

  toDOM() {
    const element = document.createElement('span')
    element.className = 'cm-md-checkbox'
    element.textContent = this.checked ? '☑' : '☐'
    return element
  }
}

class RuleWidget extends WidgetType {
  eq() {
    return true
  }

  toDOM() {
    const element = document.createElement('hr')
    element.className = 'cm-md-rule'
    return element
  }
}

const BULLET = new BulletWidget()
const RULE = new RuleWidget()

/**
 * 按当前文档、光标和视口算一遍装饰。
 *
 * 光标在编辑器里时，光标（或选区）覆盖的行保持源码；编辑器没有焦点时一行都不露，
 * 整篇按渲染结果铺开——打开文件先看到的就是成稿，而不是源码。
 */
function buildDecorations(view: EditorView): DecorationSet {
  const { state } = view
  const doc = state.doc
  const entries: Range<Decoration>[] = []
  const seenLines = new Set<string>()

  /** 有焦点的选区覆盖了哪些行；没焦点就一行都不算。 */
  const activeLines = new Set<number>()
  if (view.hasFocus) {
    for (const range of state.selection.ranges) {
      const first = doc.lineAt(range.from).number
      const last = doc.lineAt(range.to).number
      for (let line = first; line <= last; line += 1) {
        activeLines.add(line)
      }
    }
  }
  const isActive = (pos: number) => activeLines.has(doc.lineAt(pos).number)

  const hide = (from: number, to: number, widget?: WidgetType) => {
    if (to > from) {
      entries.push(Decoration.replace(widget ? { widget } : {}).range(from, to))
    }
  }
  const style = (from: number, to: number, className: string) => {
    if (to > from) {
      entries.push(Decoration.mark({ class: className }).range(from, to))
    }
  }
  const lineStyle = (pos: number, className: string) => {
    const line = doc.lineAt(pos)
    const key = `${line.number}:${className}`
    if (seenLines.has(key)) {
      return
    }
    seenLines.add(key)
    entries.push(Decoration.line({ class: className }).range(line.from))
  }

  const visible = view.visibleRanges
  const from = Math.max(0, (visible[0]?.from ?? 0) - VIEWPORT_MARGIN)
  const to = Math.min(doc.length, (visible[visible.length - 1]?.to ?? doc.length) + VIEWPORT_MARGIN)
  const tree = syntaxTree(state)

  tree.iterate({
    from,
    to,
    enter: (node) => {
      const name = node.name

      // 标题：行本身一直是标题的样子，记号只在光标进来时露出来
      const heading = /^ATXHeading([1-6])$/.exec(name)
      if (heading !== null) {
        lineStyle(node.from, `cm-md-h${heading[1]}`)
        const mark = node.node.firstChild
        if (mark?.name === 'HeaderMark') {
          if (isActive(mark.from)) {
            style(mark.from, mark.to, 'cm-md-marker')
          } else {
            // 把记号后面那个空格一起藏掉，否则渲染出来会多一个缩进
            const end = doc.sliceString(mark.to, mark.to + 1) === ' ' ? mark.to + 1 : mark.to
            hide(mark.from, end)
          }
        }
        return
      }

      if (name === 'StrongEmphasis' || name === 'Emphasis' || name === 'Strikethrough') {
        const marks: { from: number; to: number }[] = []
        for (let child = node.node.firstChild; child !== null; child = child.nextSibling) {
          if (child.name === 'EmphasisMark' || child.name === 'StrikethroughMark') {
            marks.push(child)
          }
        }
        for (const mark of marks) {
          if (isActive(mark.from)) {
            style(mark.from, mark.to, 'cm-md-marker')
          } else {
            hide(mark.from, mark.to)
          }
        }
        if (marks.length >= 2) {
          const className =
            name === 'StrongEmphasis'
              ? 'cm-md-strong'
              : name === 'Emphasis'
                ? 'cm-md-em'
                : 'cm-md-strike'
          style(marks[0].to, marks[marks.length - 1].from, className)
        }
        return
      }

      if (name === 'InlineCode') {
        const marks: { from: number; to: number }[] = []
        for (let child = node.node.firstChild; child !== null; child = child.nextSibling) {
          if (child.name === 'CodeMark') {
            marks.push(child)
          }
        }
        for (const mark of marks) {
          if (isActive(mark.from)) {
            style(mark.from, mark.to, 'cm-md-marker')
          } else {
            hide(mark.from, mark.to)
          }
        }
        if (marks.length >= 2) {
          style(marks[0].to, marks[marks.length - 1].from, 'cm-md-code')
        }
        return
      }

      // 链接：留下链接文字，藏掉方括号和地址。单尖括号自动链接没有方括号，地址本身
      // 就是文字，不能藏
      if (name === 'Link') {
        const marks: { from: number; to: number }[] = []
        const targets: { from: number; to: number }[] = []
        let textFrom = -1
        let textTo = -1
        for (let child = node.node.firstChild; child !== null; child = child.nextSibling) {
          if (child.name === 'LinkMark') {
            if (marks.length === 0) {
              textFrom = child.to
            } else if (marks.length === 1) {
              textTo = child.from
            }
            marks.push(child)
          } else if (child.name === 'URL' || child.name === 'LinkTitle') {
            targets.push(child)
          }
        }
        const inline = marks.length >= 3
        for (const mark of marks) {
          if (isActive(mark.from)) {
            style(mark.from, mark.to, 'cm-md-marker')
          } else {
            hide(mark.from, mark.to)
          }
        }
        if (inline) {
          for (const target of targets) {
            if (!isActive(target.from)) {
              hide(target.from, target.to)
            }
          }
        } else {
          // 自动链接里地址就是显示文字，别藏
          textFrom = node.from
          textTo = marks.length >= 1 ? marks[marks.length - 1].from : node.to
        }
        if (textFrom >= 0 && textTo > textFrom) {
          style(textFrom, textTo, 'cm-md-link')
        }
        return
      }

      if (name === 'QuoteMark') {
        lineStyle(node.from, 'cm-md-quote')
        if (isActive(node.from)) {
          style(node.from, node.to, 'cm-md-marker')
        } else {
          const end = doc.sliceString(node.to, node.to + 1) === ' ' ? node.to + 1 : node.to
          hide(node.from, end)
        }
        return
      }

      if (name === 'ListMark') {
        if (isActive(node.from)) {
          style(node.from, node.to, 'cm-md-marker')
          return
        }
        const text = doc.sliceString(node.from, node.to)
        // 无序列表换成圆点；有序列表的编号本来就是渲染出来的样子，留着
        if (text === '-' || text === '*' || text === '+') {
          hide(node.from, node.to, BULLET)
        }
        return
      }

      if (name === 'TaskMarker') {
        if (isActive(node.from)) {
          style(node.from, node.to, 'cm-md-marker')
        } else {
          const checked = doc.sliceString(node.from, node.to).toLowerCase().includes('x')
          hide(node.from, node.to, new CheckboxWidget(checked))
        }
        return
      }

      if (name === 'HorizontalRule') {
        if (isActive(node.from)) {
          style(node.from, node.to, 'cm-md-marker')
        } else {
          hide(node.from, node.to, RULE)
        }
        return
      }

      if (name === 'CodeMark' && node.node.parent?.name === 'FencedCode') {
        if (isActive(node.from)) {
          style(node.from, node.to, 'cm-md-marker')
        } else {
          // 整行藏掉：围栏和语言名在渲染视图里不该占正文的位置
          const line = doc.lineAt(node.from)
          hide(line.from, line.to)
          lineStyle(line.from, 'cm-md-fence')
        }
        return
      }

      if (name === 'CodeInfo') {
        // 围栏行整行被藏起来时，语言名也跟着藏了，不用再补一次样式
        if (isActive(node.from)) {
          style(node.from, node.to, 'cm-md-marker')
        }
        return
      }

      if (name === 'FencedCode') {
        // 代码正文按行铺底色，围栏行由上面的分支单独处理
        const start = Math.max(node.from, from)
        const end = Math.min(node.to, to)
        let pos = start
        while (pos <= end) {
          const line = doc.lineAt(pos)
          const text = doc.sliceString(line.from, line.to)
          if (!/^\s*(```|~~~)/.test(text)) {
            lineStyle(line.from, 'cm-md-code-line')
          }
          if (line.to >= end) {
            break
          }
          pos = line.to + 1
        }
      }
    },
  })

  return Decoration.set(entries, true)
}

const livePreviewPlugin = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet

    constructor(view: EditorView) {
      this.decorations = buildDecorations(view)
    }

    update(update: ViewUpdate) {
      if (
        update.docChanged ||
        update.selectionSet ||
        update.focusChanged ||
        update.viewportChanged ||
        syntaxTree(update.state) !== syntaxTree(update.startState)
      ) {
        this.decorations = buildDecorations(update.view)
      }
    }
  },
  {
    decorations: (instance) => instance.decorations,
  },
)

/**
 * 给 Markdown 编辑器装上所见即所得。
 *
 * 让编辑器本身就用这套视图，不再有“渲染视图 / 源码视图”两个组件：两边各写一套
 * 排版，迟早会一个改了另一个没改。
 */
export function markdownLivePreview(): Extension {
  return [
    livePreviewPlugin,
    // 被藏起来的记号是一整块：光标不能停在它中间
    EditorView.atomicRanges.of(
      (view) => view.plugin(livePreviewPlugin)?.decorations ?? Decoration.none,
    ),
    PREVIEW_THEME,
  ]
}
