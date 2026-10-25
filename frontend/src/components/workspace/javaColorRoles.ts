import { syntaxTree } from '@codemirror/language'
import type { Extension, Range } from '@codemirror/state'
import type { TreeCursor } from '@lezer/common'
import {
  Decoration,
  EditorView,
  ViewPlugin,
  type DecorationSet,
  type ViewUpdate,
} from '@codemirror/view'

import { DARCULA_COLORS } from '@/lib/darcula'

/**
 * Java 里靠 tag 分不出来的两类名字。
 *
 * <p>Lezer 把方法名、构造器名、字段名、类名、局部变量和参数**都**标成
 * {@code definition(variableName)}，`HighlightStyle` 只能按 tag 上色，没法只给方法名
 * 或字段名一种颜色。这里按语法节点补两层装饰：方法/构造器声明用 IDEA 的函数黄，
 * 字段声明用常量紫；类名、局部变量和参数保持默认色。
 *
 * <p>只在 Java 文件和 Markdown 围栏里启用（后者可能有 ```java 代码块）。装饰按视口
 * 算，和 Markdown live preview 同一个理由：大文件每敲一个字遍历整棵树不划算。
 */
const METHOD_NAME = Decoration.mark({ class: 'cm-java-method' })
const FIELD_NAME = Decoration.mark({ class: 'cm-java-field' })

function buildDecorations(view: EditorView): DecorationSet {
  const { state } = view
  const doc = state.doc
  const visible = view.visibleRanges
  const from = visible[0]?.from ?? 0
  const to = visible[visible.length - 1]?.to ?? doc.length
  const ranges: Range<Decoration>[] = []
  const tree = syntaxTree(state)

  /**
   * 这个名字是不是方法/构造器名。
   *
   * <p>正常的类体里是 MethodDeclaration/ConstructorDeclaration > Definition。围栏里
   * 常见的**裸方法片段**不是合法编译单元，Lezer 会错误恢复成
   * LocalVariableDeclaration > VariableDeclarator；这时看名字后面是不是紧跟 `(`，
   * 字段名后面只会是 `=`、`,` 或 `;`。
   */
  const isMethodName = (cursor: TreeCursor, to: number): boolean => {
    const parent = cursor.node.parent
    if (parent?.name === 'MethodDeclaration' || parent?.name === 'ConstructorDeclaration') {
      return true
    }
    if (
      parent?.name === 'VariableDeclarator' &&
      (parent.parent?.name === 'LocalVariableDeclaration' ||
        parent.parent?.name === 'FieldDeclaration')
    ) {
      return doc.sliceString(to, to + 1) === '('
    }
    return false
  }

  /** 在一棵 Java 语法树里找方法/构造器名和字段名。 */
  const collect = (cursor: TreeCursor) => {
    if (cursor.name === 'Definition') {
      const parent = cursor.node.parent
      if (isMethodName(cursor, cursor.to)) {
        ranges.push(METHOD_NAME.range(cursor.from, cursor.to))
      } else if (
        parent?.name === 'VariableDeclarator' &&
        parent.parent?.name === 'FieldDeclaration'
      ) {
        // 字段是 FieldDeclaration > VariableDeclarator > Definition；参数和局部变量
        // 也走 VariableDeclarator，但它们的祖先不是 FieldDeclaration。
        ranges.push(FIELD_NAME.range(cursor.from, cursor.to))
      }
    }
    if (cursor.firstChild()) {
      do {
        collect(cursor)
      } while (cursor.nextSibling())
      cursor.parent()
    }
  }

  tree.iterate({
    from,
    to,
    enter: (node) => {
      if (node.name === 'Definition') {
        const parent = node.node.parent
        if (isMethodName(node.node.cursor(), node.to)) {
          ranges.push(METHOD_NAME.range(node.from, node.to))
        } else if (
          parent?.name === 'VariableDeclarator' &&
          parent.parent?.name === 'FieldDeclaration'
        ) {
          ranges.push(FIELD_NAME.range(node.from, node.to))
        }
        return
      }

      // Markdown 里的 Java 是挂载在围栏上的子语法树。`iterate` 不进入这类 overlay，
      // 必须用游标按位置 enter 进去再自己走。只处理围栏里写着 java 的那些。
      if (node.name === 'FencedCode') {
        let info = ''
        let codeFrom = -1
        let codeTo = -1
        for (let child = node.node.firstChild; child !== null; child = child.nextSibling) {
          if (child.name === 'CodeInfo') {
            info = doc.sliceString(child.from, child.to).trim().toLowerCase()
          } else if (child.name === 'CodeText') {
            codeFrom = child.from
            codeTo = child.to
          }
        }
        if (info !== 'java' || codeFrom < 0 || codeFrom >= codeTo) {
          return
        }
        // 游标必须先停在 FencedCode 上再 enter：从根节点直接按位置 enter 不会进入 overlay
        const nested = node.node.cursor()
        if (nested.enter(codeFrom + 1, 1)) {
          collect(nested)
        }
      }
    },
  })

  return Decoration.set(ranges, true)
}

const javaRolePlugin = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet

    constructor(view: EditorView) {
      this.decorations = buildDecorations(view)
    }

    update(update: ViewUpdate) {
      // Markdown 里的 Java 是异步挂上来的子语法树：挂载完成时编辑器只发一个空事务，
      // 顶层树对象不变，所以只看 doc/viewport 会永远漏掉围栏里的方法名和字段名。
      const parsed = update.transactions.some(
        (transaction) =>
          !transaction.docChanged && transaction.selection === undefined && transaction.effects.length === 0,
      )
      if (
        update.docChanged ||
        update.viewportChanged ||
        update.selectionSet ||
        parsed ||
        syntaxTree(update.state) !== syntaxTree(update.startState)
      ) {
        this.decorations = buildDecorations(update.view)
      }
    }
  },
  { decorations: (instance) => instance.decorations },
)

const JAVA_ROLE_THEME = EditorView.theme(
  {
    '.cm-java-method': { color: DARCULA_COLORS.function },
    '.cm-java-field': { color: DARCULA_COLORS.constant },
  },
  { dark: true },
)

/** 给 Java（以及 Markdown 围栏里的 Java）补上方法名与字段名的颜色。 */
export function javaColorRoles(): Extension {
  return [javaRolePlugin, JAVA_ROLE_THEME]
}
