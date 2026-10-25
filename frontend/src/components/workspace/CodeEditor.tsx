import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { css } from '@codemirror/lang-css'
import { html } from '@codemirror/lang-html'
import { java } from '@codemirror/lang-java'
import { javascript } from '@codemirror/lang-javascript'
import { json } from '@codemirror/lang-json'
import { markdown, markdownLanguage } from '@codemirror/lang-markdown'
import { python } from '@codemirror/lang-python'
import { sql } from '@codemirror/lang-sql'
import { xml } from '@codemirror/lang-xml'
import { yaml } from '@codemirror/lang-yaml'
import {
  HighlightStyle,
  StreamLanguage,
  bracketMatching,
  indentOnInput,
  syntaxHighlighting,
  type Language,
} from '@codemirror/language'
import { go } from '@codemirror/legacy-modes/mode/go'
import { rust } from '@codemirror/legacy-modes/mode/rust'
import { shell } from '@codemirror/legacy-modes/mode/shell'
import { EditorState, type Extension } from '@codemirror/state'
import { EditorView, drawSelection, dropCursor, keymap, lineNumbers } from '@codemirror/view'
import { tags } from '@lezer/highlight'
import { ClipboardPaste, Copy, Scissors } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { ContextMenu, type MenuAction } from '@/components/workspace/ContextMenu'
import { javaColorRoles } from '@/components/workspace/javaColorRoles'
import { markdownLivePreview } from '@/components/workspace/markdownLivePreview'
import { copyText } from '@/lib/clipboard'
import { DARCULA_COLORS } from '@/lib/darcula'
import { languageOfPath } from '@/lib/highlight'

/** Darcula 那套颜色，色值和只读视图共用一份（见 {@link DARCULA_COLORS}）。 */
const HIGHLIGHT = HighlightStyle.define([
  { tag: tags.comment, color: DARCULA_COLORS.comment },
  {
    tag: [tags.keyword, tags.modifier, tags.controlKeyword, tags.operatorKeyword, tags.definitionKeyword],
    color: DARCULA_COLORS.keyword,
  },
  { tag: tags.annotation, color: DARCULA_COLORS.annotation },
  { tag: [tags.string, tags.special(tags.string)], color: DARCULA_COLORS.string },
  // 布尔和 null 在 IDEA 里和常量同色，不是数字色
  { tag: [tags.number], color: DARCULA_COLORS.number },
  { tag: [tags.bool, tags.null], color: DARCULA_COLORS.constant },
  {
    tag: [tags.function(tags.variableName), tags.function(tags.propertyName)],
    color: DARCULA_COLORS.function,
  },
  { tag: [tags.typeName, tags.className], color: DARCULA_COLORS.plain },
  // 基础类型（int、boolean、double…）在 Lezer 里是 standard(typeName)，不是 typeName；
  // IDEA 把它们和关键字一样画成橙色
  { tag: tags.standard(tags.typeName), color: DARCULA_COLORS.keyword },
  // this / super 在 IDEA 里按关键字上色
  { tag: tags.self, color: DARCULA_COLORS.keyword },
  { tag: tags.tagName, color: DARCULA_COLORS.tag },
  { tag: tags.attributeName, color: DARCULA_COLORS.attribute },
  { tag: tags.heading, color: DARCULA_COLORS.plain, fontWeight: 'bold' },
  { tag: [tags.link, tags.url], color: DARCULA_COLORS.number },
  { tag: tags.emphasis, fontStyle: 'italic' },
  { tag: tags.strong, fontWeight: 'bold' },
])

const THEME = EditorView.theme(
  {
    '&': {
      height: '100%',
      backgroundColor: 'transparent',
      color: DARCULA_COLORS.plain,
      fontSize: '13px',
    },
    '.cm-scroller': {
      fontFamily: 'var(--font-mono)',
      lineHeight: '24px',
      overflow: 'auto',
    },
    '.cm-content': {
      caretColor: 'var(--ring)',
      // 上 8、右 16、下 32；左边由行号列承担。渲染视图没有行号列，另加 16（见
      // markdownLivePreview 的主题）。
      padding: '8px 16px 32px 0',
    },
    '.cm-line': {
      padding: '0',
      lineHeight: '24px',
    },
    '.cm-gutters': {
      backgroundColor: 'transparent',
      color: 'var(--color-loom-faint)',
      border: 'none',
      paddingLeft: '16px',
    },
    '.cm-lineNumbers .cm-gutterElement': {
      padding: '0 1.5rem 0 0',
    },
    '.cm-selectionBackground, &.cm-focused .cm-selectionBackground, ::selection': {
      backgroundColor: 'color-mix(in oklab, var(--ring) 30%, transparent)',
    },
    '&.cm-focused': {
      outline: 'none',
    },
    '.cm-cursor': {
      borderLeftColor: 'var(--ring)',
    },
  },
  { dark: true },
)

/**
 * 中栏真正的编辑器。
 *
 * 只读视图是 Shiki 渲染出来的静态行；这里换成 CodeMirror：行号、语法色、光标、
 * 选择和撤销栈都是真的，点进去就能改。自动保存由 {@link CodeView} 统一安排，
 * 这里只把每次文档变化交出去。
 *
 * Markdown 打开 `livePreview`：同一张视图里，光标所在行露源码，其余行按渲染结果
 * 显示，见 {@link markdownLivePreview}。
 */
export function CodeEditor({
  value,
  path,
  onChange,
  onBlur,
  onSave,
  livePreview = false,
}: {
  value: string
  path: string
  onChange: (value: string) => void
  /** 焦点离开编辑器；调用方据此立刻保存 */
  onBlur?: () => void
  /** Ctrl/Cmd+S；调用方用来立即保存，不再弹按钮 */
  onSave?: () => void
  /** Markdown 的所见即所得；这类文件没有行号列 */
  livePreview?: boolean
}) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const [menu, setMenu] = useState<{ x: number; y: number; hasSelection: boolean } | null>(null)
  const onChangeRef = useRef(onChange)
  const onBlurRef = useRef(onBlur)
  const onSaveRef = useRef(onSave)
  onChangeRef.current = onChange
  onBlurRef.current = onBlur
  onSaveRef.current = onSave

  useEffect(() => {
    const parent = host.current
    if (parent === null) {
      return
    }
    const state = EditorState.create({
      doc: value,
      extensions: [
        // 渲染视图里不该有行号；Typora 也没有
        ...(livePreview ? [] : [lineNumbers()]),
        ...(livePreview ? [markdownLivePreview()] : []),
        // 方法声明/字段名在 Java 里与类名、局部变量同 tag，只能按语法节点补色
        ...(languageOfPath(path) === 'java' || livePreview ? [javaColorRoles()] : []),
        history(),
        drawSelection(),
        dropCursor(),
        indentOnInput(),
        bracketMatching(),
        syntaxHighlighting(HIGHLIGHT),
        languageFor(path),
        THEME,
        keymap.of([
          {
            key: 'Mod-s',
            preventDefault: true,
            run: () => {
              onSaveRef.current?.()
              return true
            },
          },
          ...defaultKeymap,
          ...historyKeymap,
          indentWithTab,
        ]),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            onChangeRef.current(update.state.doc.toString())
          }
        }),
        EditorView.domEventHandlers({
          blur: () => {
            onBlurRef.current?.()
            return false
          },
        }),
      ],
    })
    const editor = new EditorView({ state, parent })
    view.current = editor
    return () => {
      editor.destroy()
      view.current = null
    }
    // 每个标签有自己的 path 和生命周期；path 变了就是另一份文档
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path])

  // 外部重读或保存成功后同步内容。只有文档真的和传入值不同才改：用户每敲一个字，
  // 父层拿到的 value 已经和文档一致，所以这里不会把正在输入的内容回退；而文件在
  // 别处变了、又没有本地草稿时，会把新内容送进来。
  useEffect(() => {
    const editor = view.current
    if (editor === null) {
      return
    }
    if (editor.state.doc.toString() !== value) {
      editor.dispatch({
        changes: { from: 0, to: editor.state.doc.length, insert: value },
      })
    }
  }, [value])

  const actions: MenuAction[] = [
    {
      label: '剪切',
      hint: 'Ctrl+X',
      icon: <Scissors className="size-3.5" />,
      disabled: menu === null || !menu.hasSelection,
      onSelect: () => {
        const editor = view.current
        if (editor !== null) {
          void copySelection(editor)
          editor.dispatch(editor.state.replaceSelection(''))
          editor.focus()
        }
      },
    },
    {
      label: '复制',
      hint: 'Ctrl+C',
      icon: <Copy className="size-3.5" />,
      disabled: menu === null || !menu.hasSelection,
      onSelect: () => {
        if (view.current !== null) {
          void copySelection(view.current)
        }
      },
    },
    {
      label: '粘贴',
      hint: 'Ctrl+V',
      icon: <ClipboardPaste className="size-3.5" />,
      onSelect: () => {
        const editor = view.current
        if (editor === null) {
          return
        }
        void navigator.clipboard
          .readText()
          .then((text) => {
            editor.dispatch(editor.state.replaceSelection(text))
            editor.focus()
          })
          .catch(() => {
            // 浏览器拒绝读剪贴板时什么都不做；不能用假的成功反馈
          })
      },
    },
  ]

  return (
    <div
      ref={host}
      className="h-full min-h-0 flex-1 overflow-hidden"
      onContextMenu={(event) => {
        event.preventDefault()
        const editor = view.current
        setMenu({
          x: event.clientX,
          y: event.clientY,
          hasSelection: editor !== null && !editor.state.selection.main.empty,
        })
      }}
    >
      {menu !== null && (
        <ContextMenu
          x={menu.x}
          y={menu.y}
          label="代码操作"
          actions={actions}
          onClose={() => setMenu(null)}
        />
      )}
    </div>
  )
}

/**
 * 各语言的语法支持。编辑器按路径和 Markdown 围栏共用同一批实例：
 * `java()` 每调用一次都新建一个 {@code LanguageSupport}，围栏解析会反复问，
 * 每问一次建一份既浪费，也会让解析器缓存失效。
 */
const JAVA = java()
const TYPESCRIPT = javascript({ typescript: true })
const TSX = javascript({ typescript: true, jsx: true })
const JAVASCRIPT = javascript({ jsx: true })
const JSON_LANGUAGE = json()
const CSS = css()
const HTML = html()
const PYTHON = python()
const SQL = sql()
const XML = xml()
const YAML = yaml()
const GO = StreamLanguage.define(go)
const RUST = StreamLanguage.define(rust)
const SHELL = StreamLanguage.define(shell)

/**
 * 围栏上的语言词 → 语法。认不出、或写的是 {@code text} 时返回 null，内容保持纯文本。
 *
 * <p>Markdown 解析器不会自己按围栏加载语法：`markdown()` 必须显式给
 * {@code codeLanguages}，围栏内容才会按语言解析并上色。围栏里的 Markdown 不递归，
 * 免得同一个解析器套自己。
 */
function fencedLanguage(info: string): Language | null {
  switch (info.trim().toLowerCase()) {
    case 'java':
      return JAVA.language
    case 'ts':
    case 'typescript':
      return TYPESCRIPT.language
    case 'tsx':
      return TSX.language
    case 'js':
    case 'jsx':
    case 'javascript':
      return JAVASCRIPT.language
    case 'json':
      return JSON_LANGUAGE.language
    case 'css':
      return CSS.language
    case 'html':
    case 'vue':
    case 'svelte':
      return HTML.language
    case 'py':
    case 'python':
      return PYTHON.language
    case 'go':
      return GO
    case 'rs':
    case 'rust':
      return RUST
    case 'sql':
      return SQL.language
    case 'xml':
      return XML.language
    case 'yml':
    case 'yaml':
      return YAML.language
    case 'sh':
    case 'bash':
    case 'shell':
    case 'shellscript':
      return SHELL
    default:
      return null
  }
}

// 默认 base 是 CommonMark，不含删除线、任务列表和表格；预览里要按 GFM 解析，
// 否则 `~~x~~` 会原样露出波浪线、`- [ ]` 不会被当成任务。
const MARKDOWN = markdown({ base: markdownLanguage, codeLanguages: fencedLanguage })

/** 按路径挑语法。认不出就只做纯文本编辑，不影响行号和保存。 */
function languageFor(path: string): Extension {
  switch (languageOfPath(path)) {
    case 'java':
      return JAVA
    case 'typescript':
      return TYPESCRIPT
    case 'tsx':
      return TSX
    case 'javascript':
      return JAVASCRIPT
    case 'json':
      return JSON_LANGUAGE
    case 'css':
      return CSS
    case 'html':
      return HTML
    case 'markdown':
      return MARKDOWN
    case 'python':
      return PYTHON
    case 'go':
      return GO
    case 'rust':
      return RUST
    case 'sql':
      return SQL
    case 'xml':
      return XML
    case 'yaml':
      return YAML
    case 'shellscript':
      return SHELL
    default:
      return []
  }
}

/** 复制当前选中的文本；菜单按钮不是键盘手势，需要显式写剪贴板。 */
async function copySelection(editor: EditorView) {
  const selected = editor.state.sliceDoc(editor.state.selection.main.from, editor.state.selection.main.to)
  if (selected !== '') {
    await copyText(selected)
  }
}
