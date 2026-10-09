import { useEffect, useRef, useState, type CSSProperties, type RefObject } from 'react'
import type { ThemedToken } from 'shiki'
import type { HighlighterCore } from 'shiki/core'

import { DARCULA } from '@/lib/darcula'

/**
 * 代码高亮。**全站共用这一个高亮器实例。**
 *
 * <h2>为什么用 shiki，而且用它的"细粒度"入口</h2>
 * 因为它给的正好是中栏要的东西：{@code codeToTokens} 直接返回
 * <strong>「一行一个 token 数组」</strong>。中栏是一行一个 {@code div}（行号要单独一列、
 * 而且不能被选中），所以"按行拿 token"是唯一的顺手接口 ——
 * 别的库（highlight.js 之类）吐的是**整段 HTML**，要自己按行切开，
 * 而跨行的 span 会被切坏，那是这类界面上一类出了名的错。
 *
 * <p>细粒度入口（{@code shiki/core} + 逐语言 import）是为了不把全部语言和主题打进包里；
 * 用 JavaScript 引擎而不是 WASM 引擎，是为了省掉那六百多 KB 的 {@code .wasm}。
 *
 * <h2>主题：Darcula，就是 IDEA 那套</h2>
 * 理由很直接 —— **这个项目的两个用户白天都在 IDEA 里看代码**，那套配色是他们已经
 * 读惯了的。见 {@link DARCULA}：它是照 Darcula 众所周知的那套值写的 TextMate 主题
 * （shiki 内置的是**另一个**主题，拼写差一个字母）。
 */

/**
 * 认得的扩展名 → shiki 的语言 id。
 *
 * <p><strong>认不出来就不高亮</strong>，原样显示 —— 半套高亮比没有高亮更像坏了。
 * 只列下面真正 import 了的那些：多写一个语言 id 换来的是一次运行时炸。
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
 * 真正加载了的语言 id。**高亮只认这一个集合** ——
 * 让人写一个没加载的语言 id 换来的是运行时炸，而这里的失败模式必须是"不高亮"。
 */
const LOADED = new Set([
  'java', 'typescript', 'tsx', 'javascript', 'css', 'html', 'json',
  'yaml', 'xml', 'sql', 'markdown', 'python', 'go', 'rust', 'shellscript',
])

/** 按需加载的语法包。**和上面那张表一一对应**。 */
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

/** 建一次就够 —— 高亮器是只读的，每个文件重建一遍纯属浪费。 */
let building: Promise<HighlighterCore> | null = null

/**
 * 高亮器。**连内核都是按需拉的。**
 *
 * <p>静态 import 内核的话，它会进主包 —— 于是**登录页**也要先下两百多 KB 的高亮器，
 * 只为了一个还没出现的代码栏。文件没打开之前，这段代码一行都不该被下载。
 */
function highlighter(): Promise<HighlighterCore> {
  building ??= Promise.all([import('shiki/core'), import('shiki/engine/javascript')]).then(
    ([core, engine]) =>
      core.createHighlighterCore({
        // 主题是我们自己的一个对象，不是 shiki 内置的那个 —— 所以直接给，不用 import
        themes: [DARCULA],
        langs: GRAMMARS.map((load) => load()),
        // JavaScript 引擎而不是 WASM 引擎：省掉那六百多 KB 的 .wasm。
        // 代价是极少数语法包它解不了 —— 那种情况下高亮会失败并退回原样，见下面
        engine: engine.createJavaScriptRegexEngine(),
      }),
  )
  return building
}

/**
 * 两次高亮之间**最少隔多久**。
 *
 * <h2>它的活已经变小了，但没有消失</h2>
 * 从前调用方把**整块正在长的代码**喂进来，每变一个字都算一次，代价是随时间平方增长。
 * 现在调用方只把**已经写完的那些行**喂进来（见 {@code splitPartialLine}），
 * 于是逐字增长的那些帧根本不会叫醒这里 —— 唤醒它的只有"又写完了一行"。
 *
 * <p>所以它现在管的是**另一件事**：模型连着写很多短行的时候，每一行都值得单独算一次吗？
 * 一秒里写完二十行的话，答案是"不值得" —— 那些行最终会一起出现在屏幕上。
 * 隔 150ms 合一次，代价从"行数"变成"时间"，而人看不出来。
 *
 * <p>**是节流不是防抖**：防抖在"内容一直在变"的时候永远不会触发（每次变化都把
 * 定时器推后），流式正文正是那个形状 —— 那样写会让代码块在整个生成过程里一点颜色都没有，
 * 写完才"啪"地一下出现。
 *
 * <p>剩下的那部分代价是**按行数**平方的（每算一次都要从头分词整段），而这是这个
 * 办法的地板：真要增量得分词，得拿到"第 K 行处的语法状态"，shiki 不暴露它。
 */
const HIGHLIGHT_THROTTLE_MS = 150

/**
 * 高亮一段代码，**按行返回 token**；不认得这个扩展名、或者高亮器没准备好时返回 null。
 *
 * <p>返回 null 的那两种情况对调用方是**同一件事**：这段代码暂时按原样显示即可。
 * 分开表达的话，调用方要为"还没好"和"永远不会好"各写一条分支，而它们要做的事一样。
 *
 * @param enabled 这一段**现在要不要上色**。滚出屏幕的代码块传 false ——
 *                见 {@link useViewportHighlighting}
 */
export function useHighlighted(
  content: string,
  language: string | null,
  enabled = true,
): ThemedToken[][] | null {
  const [tokens, setTokens] = useState<ThemedToken[][] | null>(null)
  /** 上一次真正分词的时刻。节流按它算下一次该等到什么时候 */
  const lastRunAt = useRef(0)

  useEffect(() => {
    if (language === null || !enabled) {
      return
    }
    let cancelled = false
    // 到点了才分词：连续变化时每次变化都重新排，**但目标时刻是同一个** ——
    // 于是平均每 150ms 真算一次，而不是每帧算一次
    const wait = Math.max(0, HIGHLIGHT_THROTTLE_MS - (Date.now() - lastRunAt.current))
    const timer = window.setTimeout(() => {
      lastRunAt.current = Date.now()
      highlighter()
        .then((hl) => hl.codeToTokens(content, { lang: language, theme: THEME }).tokens)
        .then((result) => {
          if (!cancelled) setTokens(result)
        })
        .catch(() => {
          // 高亮失败**不该**让代码看不见：退回原样显示就是了。
          // 一个语法包解析不了只是少点颜色，而代码是这一栏唯一的正文
          if (!cancelled) setTokens(null)
        })
    }, wait)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [content, language, enabled])

  // 认不出扩展名 → **当场就知道没有高亮**，不该为它进一次 state
  // （setState 在 effect 里同步调用会多渲染一次，而这件事根本不需要渲染就能算出来）。
  //
  // 这一行也是必须的：`tokens` 里可能还留着上一份内容的结果 ——
  // 而高亮结果带着**内容本身**，上错色比不上色难看得多。
  // （调用方再给它挂一个 per-file 的 key，把这件事变成结构上的保证）
  return language === null ? null : tokens
}

/**
 * 一块代码**滚到了才上色**。
 *
 * <h2>为什么不是"一挂载就上色"</h2>
 * 一段长对话里可能有几十个代码块，而人一次只看得到一两个。
 * 全部立刻分词的话，打开一条老会话要在首屏做几十次用不上的解析 ——
 * 而那正是"这条会话一打开就卡一下"的来源。
 *
 * <p>没上色的时候代码**照常按等宽排好**，所以位置、高度、换行全都是对的 ——
 * 上色只是往同样的字形上补颜色，不会让版面跳一下。
 *
 * <h2>一个观察者，看到过就永久激活</h2>
 * 观察者是**模块级的一个**（不是每个代码块一个）—— 一个页面几十个
 * {@code IntersectionObserver} 本身就是负担。而且元素一旦露过面就把自己摘掉：
 * 人往下滚了又滚回来，不该重新变回黑白。
 */
export function useViewportHighlighting(
  target: RefObject<Element | null>,
  enabled = true,
): boolean {
  // 拿不到 IntersectionObserver（老浏览器、测试环境）就**当场算作已经看过** ——
  // 退化方向是"多做一点功"，不是"永远没有颜色"。
  // 写在初始值里而不是 effect 里：那是"这个环境有没有那个能力"，
  // 渲染之前就知道，不需要为此多渲染一次
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

/** 全站共用的那一个观察者。见 {@link useViewportHighlighting}。 */
const viewport = new (class {
  private observer: IntersectionObserver | undefined
  private readonly waiting = new Map<Element, () => void>()

  observe(element: Element, activate: () => void): () => void {
    this.observer ??= new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const fire = this.waiting.get(entry.target)
        if (fire === undefined) continue
        // 看到过了就摘掉：再滚回来不该重新变回黑白
        this.waiting.delete(entry.target)
        this.observer?.unobserve(entry.target)
        fire()
      }
      // 没人等了就把观察者关掉 —— 它在整条会话里只是偶尔才需要
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
 * <h2>为什么必须翻译 {@code fontStyle}</h2>
 * 因为**有些东西在 Darcula 里只靠字重区分，不靠颜色** —— Markdown 的标题就是：
 * 它和正文同色（{@code #a9b7c6}），唯一的区别是**它是粗的**。
 * 只铺颜色的话，一个 .md 文件在源码视图里仍然是一片灰（这一条是实测出来的，
 * 见 {@code darcula.ts} 里那段）。
 *
 * <p>{@code fontStyle} 是 shiki 的位掩码（italic 1 / bold 2 / underline 4 /
 * strikethrough 8），不是 CSS 字符串。只翻译得上前两个 ——
 * 代码里几乎不出现下划线和删除线，而把没翻译的位直接塞给 CSS 会得到
 * {@code fontWeight: 4} 这种东西。
 */
export function tokenStyle(token: { color?: string; fontStyle?: number }): CSSProperties {
  const style = token.fontStyle ?? 0
  return {
    color: token.color,
    fontStyle: (style & FONT_ITALIC) !== 0 ? 'italic' : undefined,
    // 700 而不是 600：等宽字体基本没有 600 这一档，写 600 会落到某个不确定的字形上
    fontWeight: (style & FONT_BOLD) !== 0 ? 700 : undefined,
  }
}

const FONT_ITALIC = 1
const FONT_BOLD = 2

/**
 * 一个文件路径该用什么语言高亮；认不出来就是 null（**认出扩展名才算**，
 * 不看文件名里的点 —— `.gitignore` 的 `.` 后面不是扩展名）。
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
 * 代码块围栏上写的那个词（```java、```bash）该用什么语言高亮。
 *
 * <p>和扩展名共用一张表：人写 `java`、`bash`、`ts` 的时候，指的就是同一个东西。
 * 另外还认**语言的全名**（`typescript`、`shellscript`）—— 那是我们自己的表里的键，
 * 而围栏上也可能直接那么写。
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
