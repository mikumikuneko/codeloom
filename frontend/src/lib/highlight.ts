import { useEffect, useRef, useState, type CSSProperties, type RefObject } from 'react'
import type { ThemedToken } from 'shiki'
import type { HighlighterCore } from 'shiki/core'

import { DARCULA } from '@/lib/darcula'

/**
 * 代码高亮。全站共用一个高亮器实例。
 *
 * 用 shiki 的细粒度入口，因为 `codeToTokens` 直接返回"每行一个 token 数组"。
 * 中栏一行一个 `div`（行号单独一列且不可选中），按行拿 token 是唯一顺手的接口；
 * 输出整段 HTML 的库（如 highlight.js）需要自己按行切，跨行的 span 会被切坏。
 *
 * 细粒度 import（`shiki/core` + 逐语言 import）避免把全部语言和主题打进包里；
 * 用 JavaScript 引擎而不是 WASM 引擎，省掉六百多 KB 的 `.wasm`。
 *
 * 主题是 Darcula（见 {@link DARCULA}）：使用者习惯 IDEA 的这套配色；shiki 内置的
 * 是拼写相近的另一个主题。
 */

/**
 * 文件树图标用的"这是代码"清单。与高亮用的 `LANGUAGES` 不是同一张表：那张决定
 * 能否上色，这张决定图标是否是钢蓝色。
 *
 * 文档/数据（md、json、yml、xml）能高亮，但不是代码；Kotlin、C、Ruby 明显是代码，
 * 但没有对应语法包，图标仍是代码色。两张表放在同一文件里便于对照；合并会让两个
 * 判断之一变成错的。
 */
const CODE_EXTENSIONS = new Set([
  'java', 'kt', 'ts', 'tsx', 'js', 'jsx', 'css', 'scss', 'vue', 'svelte',
  'py', 'go', 'rs', 'rb', 'php', 'c', 'h', 'cc', 'cpp', 'hpp', 'cs', 'swift',
  'sh', 'bash', 'sql', 'proto', 'graphql',
])

/** 这个文件名看起来是不是代码（文件树图标用）。点开头的文件不算"点扩展名"。 */
export function isCodeFile(name: string): boolean {
  const dot = name.lastIndexOf('.')
  return dot > 0 && CODE_EXTENSIONS.has(name.slice(dot + 1).toLowerCase())
}

/**
 * 扩展名或围栏词 → shiki 语言 id。
 *
 * 认不出来就不高亮、原样显示：半套高亮比没有高亮更像坏了。只列下面真正 import
 * 了的语言：多写一个语言 id 换来的是一次运行时炸。
 */
const LANGUAGES: Record<string, string> = {
  java: 'java',
  ts: 'typescript',
  tsx: 'tsx',
  js: 'javascript',
  jsx: 'javascript',
  mjs: 'javascript',
  css: 'css',
  html: 'html',
  vue: 'html',
  svelte: 'html',
  json: 'json',
  yml: 'yaml',
  yaml: 'yaml',
  xml: 'xml',
  sql: 'sql',
  md: 'markdown',
  // 全名。写围栏的人可能写 ```markdown，文件也可能叫 README.markdown
  markdown: 'markdown',
  py: 'python',
  go: 'go',
  rs: 'rust',
  sh: 'shellscript',
  bash: 'shellscript',
}

/**
 * 真正加载了的语言 id。高亮只认这个集合：未加载的 id 会让运行时炸，而这里的
 * 失败模式必须是"不高亮"。
 */
const LOADED = new Set([
  'java', 'typescript', 'tsx', 'javascript', 'css', 'html', 'json',
  'yaml', 'xml', 'sql', 'markdown', 'python', 'go', 'rust', 'shellscript',
])

/** 按需加载的语法包，与 `LOADED` 一一对应。 */
const GRAMMARS = [
  () => import('shiki/langs/java.mjs'),
  () => import('shiki/langs/typescript.mjs'),
  () => import('shiki/langs/tsx.mjs'),
  () => import('shiki/langs/javascript.mjs'),
  () => import('shiki/langs/css.mjs'),
  () => import('shiki/langs/html.mjs'),
  () => import('shiki/langs/json.mjs'),
  () => import('shiki/langs/yaml.mjs'),
  () => import('shiki/langs/xml.mjs'),
  () => import('shiki/langs/sql.mjs'),
  () => import('shiki/langs/markdown.mjs'),
  () => import('shiki/langs/python.mjs'),
  () => import('shiki/langs/go.mjs'),
  () => import('shiki/langs/rust.mjs'),
  () => import('shiki/langs/shellscript.mjs'),
]

const THEME = DARCULA.name

/** 建一次就够：高亮器只读，每个文件重建一遍是浪费。 */
let building: Promise<HighlighterCore> | null = null

/**
 * 高亮器。内核也按需拉取。
 *
 * 静态 import 内核会让它进主包，登录页也要先下两百多 KB，只为一块还没出现的代码栏。
 */
function highlighter(): Promise<HighlighterCore> {
  building ??= Promise.all([import('shiki/core'), import('shiki/engine/javascript')]).then(
    ([core, engine]) =>
      core.createHighlighterCore({
        // 主题是我们自己的对象，不是 shiki 内置的，所以直接给，不用 import。
        themes: [DARCULA],
        langs: GRAMMARS.map((load) => load()),
        // JavaScript 引擎省掉六百多 KB 的 .wasm；极少数语法包它解不了，
        // 此时高亮失败并退回原样，见下面。
        engine: engine.createJavaScriptRegexEngine(),
      }),
  )
  return building
}

/**
 * 两次高亮之间最少隔多久。
 *
 * 调用方只把已经写完的行喂进来（见 `splitPartialLine`），逐字增长的帧不会唤醒
 * 这里。它处理的是另一件事：模型连续写很多短行时，一秒二十行不值得每行算一次，
 * 那些行最终会一起出现。隔 150ms 合一次，代价从行数变成时间。
 *
 * 是节流不是防抖：防抖在内容持续变化时永远不会触发，流式正文正是那个形状，
 * 会让代码块在整个生成过程中没有颜色。
 *
 * 剩余代价仍按行数平方（每次都要从头分词整段），这是本方法的下限：真正的增量
 * 分词需要第 K 行处的语法状态，shiki 不暴露它。
 */
const HIGHLIGHT_THROTTLE_MS = 150

/**
 * 高亮一段代码，按行返回 token；扩展名不认识或高亮器未就绪时返回 null。
 *
 * 两种 null 对调用方是同一件事：先按原样显示。分开表达会逼调用方为"还没好"和
 * "永远不会好"写两条一样的分支。
 *
 * @param enabled 这一段现在是否要上色；滚出屏幕的代码块传 false，见
 *                {@link useViewportHighlighting}
 */
export function useHighlighted(
  content: string,
  language: string | null,
  enabled = true,
): ThemedToken[][] | null {
  const [tokens, setTokens] = useState<ThemedToken[][] | null>(null)
  /** 上一次真正分词的时刻；节流按它算下一次该等到何时 */
  const lastRunAt = useRef(0)

  useEffect(() => {
    if (language === null || !enabled) {
      return
    }
    let cancelled = false
    // 到点才分词：连续变化时每次重排，但目标时刻不变，于是平均每 150ms 真算一次。
    const wait = Math.max(0, HIGHLIGHT_THROTTLE_MS - (Date.now() - lastRunAt.current))
    const timer = window.setTimeout(() => {
      lastRunAt.current = Date.now()
      highlighter()
        .then((hl) => hl.codeToTokens(content, { lang: language, theme: THEME }).tokens)
        .then((result) => {
          if (!cancelled) setTokens(result)
        })
        .catch(() => {
          // 高亮失败不该让代码看不见：退回原样。语法包解析不了只是少点颜色，
          // 代码才是这一栏唯一的正文。
          if (!cancelled) setTokens(null)
        })
    }, wait)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [content, language, enabled])

  // 认不出扩展名当场返回 null，不为它进一次 state（effect 里同步 setState 会多渲染
  // 一次，而这件事不需要渲染就能算出来）。
  //
  // 这一行也是必须的：tokens 里可能还留着上一份内容的结果，而高亮结果带着内容
  // 本身，上错色比没色更糟。调用方再挂 per-file key，把这件事变成结构上的保证。
  return language === null ? null : tokens
}

/**
 * 一块代码滚到视口内才上色。
 *
 * 一段长对话可能有几十个代码块，一次只看得到一两个；全部立刻分词会让打开老会话
 * 在首屏做几十次用不上的解析。未上色时代码照常按等宽排好，位置、高度、换行不变；
 * 上色只补颜色，不会让版面跳动。
 *
 * 观察者是模块级的一个（每块一个 `IntersectionObserver` 本身是负担），元素露过面
 * 就摘掉：滚回来不该重新变回黑白。
 */
export function useViewportHighlighting(
  target: RefObject<Element | null>,
  enabled = true,
): boolean {
  // 没有 IntersectionObserver（老浏览器、测试环境）时算作已看过：退化方向是
  // 多做一点功，不是永远没有颜色。写在初始值里，渲染之前就知道，不用多渲染一次。
  const [seen, setSeen] = useState(() => typeof IntersectionObserver === 'undefined')

  useEffect(() => {
    if (seen || !enabled) {
      return
    }
    const element = target.current
    if (element === null) {
      return
    }
    return viewport.observe(element, () => setSeen(true))
  }, [seen, enabled, target])

  return seen && enabled
}

/** 全站共用的观察者。见 {@link useViewportHighlighting}。 */
const viewport = new (class {
  private observer: IntersectionObserver | undefined
  private readonly waiting = new Map<Element, () => void>()

  observe(element: Element, activate: () => void): () => void {
    this.observer ??= new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const fire = this.waiting.get(entry.target)
        if (fire === undefined) continue
        // 看到过就摘掉：再滚回来不该重新变回黑白。
        this.waiting.delete(entry.target)
        this.observer?.unobserve(entry.target)
        fire()
      }
      // 没人等了就关掉观察者：整条会话里只是偶尔需要它。
      if (this.waiting.size === 0) {
        this.observer?.disconnect()
        this.observer = undefined
      }
    })
    this.waiting.set(element, activate)
    this.observer.observe(element)
    return () => {
      this.waiting.delete(element)
      this.observer?.unobserve(element)
    }
  }
})()

/**
 * 一个 token 该长什么样：颜色，加上字重和字形。
 *
 * 必须翻译 `fontStyle`：有些东西在 Darcula 里只靠字重区分，不靠颜色 —— Markdown
 * 标题与正文同色（`#a9b7c6`），唯一区别是粗体；只铺颜色会让 .md 文件在源码视图
 * 里仍是一片灰（实测过，见 `darcula.ts`）。
 *
 * `fontStyle` 是 shiki 的位掩码（italic 1 / bold 2 / underline 4 / strikethrough 8），
 * 不是 CSS 字符串。只翻译前两个：代码里几乎不出现下划线和删除线，未翻译的位直接
 * 塞给 CSS 会得到 `fontWeight: 4` 这种东西。
 */
export function tokenStyle(token: { color?: string; fontStyle?: number }): CSSProperties {
  const style = token.fontStyle ?? 0
  return {
    color: token.color,
    fontStyle: (style & FONT_ITALIC) !== 0 ? 'italic' : undefined,
    // 700 而不是 600：等宽字体基本没有 600 这一档，写 600 会落到不确定的字形。
    fontWeight: (style & FONT_BOLD) !== 0 ? 700 : undefined,
  }
}

const FONT_ITALIC = 1
const FONT_BOLD = 2

/**
 * 一个文件路径该用什么语言高亮；认不出来就是 null。只认扩展名，不看文件名里的
 * 点：`.gitignore` 的点后面不是扩展名。
 */
export function languageOfPath(path: string | null): string | null {
  if (path === null) {
    return null
  }
  const name = path.slice(path.lastIndexOf('/') + 1)
  const dot = name.lastIndexOf('.')
  return dot > 0 ? languageOfWord(name.slice(dot + 1)) : null
}

/**
 * 代码块围栏上的词（```java、```bash）该用什么语言高亮。
 *
 * 与扩展名共用一张表：人写 `java`、`bash`、`ts` 时指的是同一个东西。也认语言
 * 全名（`typescript`、`shellscript`），它们是表里的键，围栏上也可能直接那么写。
 */
export function languageOfFence(word: string): string | null {
  return languageOfWord(word)
}

function languageOfWord(word: string): string | null {
  const key = word.trim().toLowerCase()
  if (LOADED.has(key)) {
    return key
  }
  const mapped = LANGUAGES[key]
  return mapped !== undefined && LOADED.has(mapped) ? mapped : null
}
