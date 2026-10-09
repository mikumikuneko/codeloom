import { Fragment, memo, useRef, type ComponentProps } from 'react'
import ReactMarkdown from 'react-markdown'
import type { ThemedToken } from 'shiki'
import remarkGfm from 'remark-gfm'

import { CopyButton } from '@/components/workspace/CopyButton'
import { languageOfFence, tokenStyle, useHighlighted, useViewportHighlighting } from '@/lib/highlight'
import { remarkCjkStrong } from '@/lib/remarkCjkStrong'
import { splitPartialLine, splitStreamingBlocks, type CodeParts } from '@/lib/streamingMarkdown'
import { useCodeWrap } from '@/lib/useCodeWrap'

/**
 * react-markdown 传给自定义组件的 props：DOM 该有的那些，加上它自己的 `node`。
 *
 * <p>`node` 是 HAST 节点对象。**绝不能原样铺到 DOM 元素上** —— 那会变成一个
 * `node="[object Object]"` 属性（以及一条没必要的控制台警告）。
 * 所以每个组件都先把它摘掉，剩下的才是 DOM 属性。
 *
 * <p>摘掉的那个变量叫 `_node`（下划线开头）：它**就是要被扔掉的**，
 * 下划线在这里的意思是"我知道它在这儿，我故意不用它" ——
 * 而不是"忘了删"。
 */
type Md<T extends keyof React.JSX.IntrinsicElements> = ComponentProps<T> & { node?: unknown }

/**
 * 一个围栏代码块的**颜色**。
 *
 * <p>它不负责那个盒子 —— 盒子是外层 {@code pre} 给的（`bg-secondary` 和圆角都在那儿）。
 * 这里只吐一串上了色的 {@code span}。
 *
 * <p>认不出语言、或者高亮器还没准备好时，**原样吐出来**：代码是正文，
 * 没有颜色也得能读，而"半套高亮"比没有更像坏了。
 */
function FencedCode({
  code,
  language,
  parts,
}: {
  code: string
  language: string | null
  /** 切好的三段。**切在 {@link Markdown} 里做** —— 判"是不是还在写"要看整段正文 */
  parts: CodeParts
}) {
  // 这一块**滚到了才上色**：一段对话里可能有几十个代码块，
  // 而人一次只看得到一两个。没上色的时候它照常按等宽排好，位置和高度都是对的
  const anchor = useRef<HTMLSpanElement>(null)
  const seen = useViewportHighlighting(anchor, language !== null)

  // ★ 只把**写完的那些行**交给高亮器。这是流式时代价的关键：那串文本在有行写完之前
  //   **按值相等**（最后一行在长，但它在 partial 里），于是 effect 的依赖没变、
  //   高亮器一次也不会被叫醒
  const tokens = useHighlighted(parts.complete, language, seen)

  return (
    <span ref={anchor}>
      {/* 还没有高亮（认不出语言、或者高亮器没准备好）时整段原样吐出来 ——
          包括那条没写完的行，那时候它本来也没颜色可掉 */}
      {tokens === null ? (
        code
      ) : (
        <>
          <HighlightedTokens tokens={tokens} />
          {parts.partial}
          {/* 解析器补的那个换行照旧还回去：它在 pre 里是**最后那个空行**，
              少了它整块会矮一行 —— 而"长出来的时候"和"长完之后"不该跳一下 */}
          {parts.trailingBreak}
        </>
      )}
    </span>
  )
}

/**
 * 已经算好的那些行。
 *
 * <h2>为什么它得是 memo 的</h2>
 * 它上面那个组件**每一帧都会被重新渲染**（正文在长），而这段 token 数组
 * 在两次分词之间是**同一个引用** —— 内容一个字都没变。不 memo 的话，
 * React 每一帧都要重新 diff 几百上千个 {@code span}；memo 之后这一整棵子树
 * 直接跳过。同一招 {@code FrozenBlock} 也在用。
 */
const HighlightedTokens = memo(function HighlightedTokens({ tokens }: { tokens: ThemedToken[][] }) {
  return tokens.map((line, index) => (
    <Fragment key={index}>
      {index > 0 && '\n'}
      {line.map((token, at) => (
        <span key={at} style={tokenStyle(token)}>
          {token.content}
        </span>
      ))}
    </Fragment>
  ))
})

/**
 * 代码块的外壳。
 *
 * <h2>复制按钮从**屏幕上那段文字**里取内容</h2>
 * 它读的是这个 {@code pre} 的 `textContent` —— 所以复制到的东西，
 * 和人在这块区域里看见的逐字相同。传一份原始字符串进来的话，那两份迟早会不一致
 * （高亮把 token 拆成了 span、以后还可能加行号）。
 *
 * <p>按钮**平时不显形**，鼠标移到代码块上、或者键盘 Tab 到它，它才出来。
 * 一段对话里可能有好几个代码块，让每个都挂一个常驻按钮，那一栏就变成了工具栏。
 */
function CodeShell({ node: _node, children, ...props }: Md<'pre'>) {
  const body = useRef<HTMLPreElement>(null)
  const [wrapped, toggleWrap] = useCodeWrap()

  return (
    <div className="group/code relative">
      {/* 两个控件平时都不显形，鼠标移到这块代码上、或者键盘 Tab 到它们，才出来。
          一段对话里十几个代码块，每个都挂一排常驻按钮，那一栏就变成工具栏了 */}
      <div className="absolute right-2 top-2 z-10 flex items-center gap-1">
        <button
          type="button"
          onClick={toggleWrap}
          className="rounded px-1.5 py-0.5 text-xs text-loom-faint opacity-0 transition-opacity hover:text-foreground focus-visible:opacity-100 group-hover/code:opacity-100"
        >
          {wrapped ? '不换行' : '换行'}
        </button>
        <CopyButton contentRef={body} />
      </div>
      {/* 不换行时右边多留一点：长行横着滚过去的时候要从那几个按钮底下穿过 */}
      <pre
        ref={body}
        className={`rounded-md bg-secondary py-3 pl-3 font-mono text-[13px] leading-6 ${
          wrapped ? 'whitespace-pre-wrap pr-3' : 'overflow-x-auto pr-24'
        }`}
        {...props}
      >
        {children}
      </pre>
    </div>
  )
}

/**
 * agent（和人）说的话里的 markdown。
 *
 * <h2>为什么必须有它</h2>
 * 模型输出的就是 markdown：`**粗体**`、列表、代码块。原样铺在界面上，人看到的是一堆
 * 星号和反引号 —— 而那**不是它说的话**，是它在客户端里该被渲染掉的记号。
 * （这和"界面文案里不要用标记符号"是同一条：记号不该出现在给人看的地方。）
 *
 * <h2>中文加粗要单独补一条语法规则</h2>
 * 见 {@link remarkCjkStrong}：`**结论：**后面接着写` 这种中文里到处都是的形状，
 * 按 CommonMark 原规则是**解析不出来**的，会原样显示星号。
 *
 * <h2>样式为什么写得这么细</h2>
 * 默认的 markdown 样式是**给文章用的**（大标题、宽松的段距），而这里是一段工作对话 ——
 * 行距、字号、间距都得跟着周围走，否则一段带列表的回答会在流里显得比它本身重。
 *
 * <h2>三档排版，因为"markdown"在三处不是同一件事</h2>
 * 一段工作对话里的 markdown（默认档）要**收着**，一份文档的 markdown 要**分级** ——
 * 是同一种语法的两种用途，不该硬凑成一档。第三档是思考过程那种次要内容。
 */
export function Markdown({
  children,
  variant = 'chat',
}: {
  children: string
  /**
   * - `chat`（默认）：一段工作对话。标题压到和正文一个量级。
   * - `dense`：次要内容（思考过程）。同族，但小一号淡一档。
   * - `document`：一份文档。标题真的分级 —— 中栏打开 .md 时用这个。
   */
  variant?: 'chat' | 'dense' | 'document'
}) {
  const dense = variant === 'dense'
  const document = variant === 'document'

  /**
   * 标题。
   *
   * <p>文档档的上间距给在 **padding** 上而不是 margin 上：外层用的是 {@code space-y-*}，
   * 那也是一条 {@code margin-top}，同属性上的 utility 会被它压过去 ——
   * 写 {@code mt-6} 在这里是个不生效的类，而那种"看起来写了、实际没有"最容易骗过下一个人。
   *
   * @param own 这个元素**自己带来的** class。react-markdown 偶尔会塞一个 ——
   *            脚注标题就是一个（GFM 给它加了 {@code sr-only}，那是插件里硬编码的）。
   *            它必须和我们的类**拼在一起**，不能二选一，见下面的说明
   */
  const heading = (level: 1 | 2 | 3 | 4 | 5 | 6, own?: string) => {
    const ours = document
      ? `first:pt-0 ${['pt-6 text-xl', 'pt-5 text-lg', 'pt-4 text-base', 'pt-3', 'pt-3', 'pt-3'][level - 1]} font-medium`
      : 'font-medium'
    return own ? `${ours} ${own}` : ours
  }

  // 这一整段正文。块级代码那一段切分要用它判"最后一行写完没有" —— 见 splitPartialLine
  const source = children
  return (
    // overflow-wrap: 一个长 URL 或长路径不许把这一栏撑破 —— 撑破的话，
    // 整个三栏布局都会被它顶开，而不只是一行长出去
    //
    // dense: "次要内容"用的那档（思考过程）。它和正文**同族但小一号、淡一档**，
    // 而不是换一种排版 —— 换排版会让人以为那是另一种东西
    <div
      className={`${
        document
          ? 'space-y-4 text-sm leading-7'
          : dense
            ? 'space-y-2 text-xs leading-5'
            : 'space-y-3 text-sm leading-6'
      } [overflow-wrap:anywhere]`}
    >
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkCjkStrong]}
        components={{
          // 段落的间距由上面那个 space-y-3 给，所以段自己不留
          p: ({ node: _node, ...props }: Md<'p'>) => <p className="whitespace-pre-wrap break-words" {...props} />,

          // 标题的档位见上面那个 heading()：对话里压平，文档里分级。
          //
          // **`className` 要先从 props 里摘出来再铺**：JSX 里同名属性是**后写的整个
          // 换掉前面的**（不是合并）。写成 `className={heading(1)} {...props}` 的话，
          // 只要 props 里也有 className，我们这套样式当场被顶掉 —— 而且**不报错**。
          // 平时看不出来，因为 markdown 的普通标题本来不带 class；带的那天（脚注标题
          // 就是一个，GFM 会给它加 sr-only）才会静默失效
          h1: ({ node: _node, className, ...props }: Md<'h1'>) => (
            <p className={heading(1, className)} {...props} />
          ),
          h2: ({ node: _node, className, ...props }: Md<'h2'>) => (
            <p className={heading(2, className)} {...props} />
          ),
          h3: ({ node: _node, className, ...props }: Md<'h3'>) => (
            <p className={heading(3, className)} {...props} />
          ),
          h4: ({ node: _node, className, ...props }: Md<'h4'>) => (
            <p className={heading(4, className)} {...props} />
          ),
          h5: ({ node: _node, className, ...props }: Md<'h5'>) => (
            <p className={heading(5, className)} {...props} />
          ),
          h6: ({ node: _node, className, ...props }: Md<'h6'>) => (
            <p className={heading(6, className)} {...props} />
          ),

          ul: ({ node: _node, ...props }: Md<'ul'>) => <ul className="list-disc space-y-1 pl-5" {...props} />,
          ol: ({ node: _node, ...props }: Md<'ol'>) => <ol className="list-decimal space-y-1 pl-5" {...props} />,

          // 行内的代码用一点点底色和一条极淡的边把它从周围分出来 ——
          // 只靠底色的话，在一个本来就有底色的引用块里它就沉进去了
          code: ({ className, children }: Md<'code'>) => {
            const language = languageOfFence((className ?? '').replace('language-', ''))
            const text = String(children)
            // 是不是块级：有语言标记，或者内容里有换行。两个都没有的一律当行内
            if (language !== null || text.includes('\n')) {
              // 切分**在这里做**，不在 FencedCode 里：判"这块是不是还在写"要看
              // **整段正文**（见 splitPartialLine），而那个只有这一层拿得到
              return (
                <FencedCode code={text} language={language} parts={splitPartialLine(text, source)} />
              )
            }
            return (
              <code className="rounded border border-border bg-secondary px-1 py-0.5 font-mono text-[0.85em]">
                {children}
              </code>
            )
          },

          // 块级代码：外壳负责"盒子"和复制按钮，上面的 FencedCode 负责"颜色" ——
          // 分成两层是因为高亮要按行拿 token（见 highlight.ts），而盒子只需要包一层
          pre: CodeShell,

          // 链接：静止时是一条**虚线**下划线，指上去才变成实线。
          // 实线在静止时就等于在喊"点我"；而一段回答里链接常常只是引用出处，
          // 不是它想让你做的事。虚线加 `underline-offset` 让下划线离开字身，
          // 不挡住中文的下半部分
          a: ({ node: _node, ...props }: Md<'a'>) => (
            <a
              className="underline decoration-dotted underline-offset-[3px] transition-colors hover:decoration-solid hover:text-foreground"
              target="_blank"
              rel="noreferrer"
              {...props}
            />
          ),

          // 引用块：一条左边的线
          blockquote: ({ node: _node, ...props }: Md<'blockquote'>) => (
            <blockquote className="border-l-2 border-border pl-3 text-muted-foreground" {...props} />
          ),

          // 表格。**列多了才横滚**：两三列的表格让它铺满宽度更好读（单元格自己换行），
          // 四五列以上就该保持自然宽度、让它滚 —— 硬塞进窄栏的话，
          // 每个单元格都挤成一个词一行，那张表就没法看了
          table: ({ node: _node, ...props }: Md<'table'>) => (
            <div className="max-w-full overflow-x-auto">
              <table
                className="w-full border-collapse text-xs has-[th:nth-child(4)]:w-max has-[th:nth-child(4)]:max-w-none"
                {...props}
              />
            </div>
          ),
          // 首尾两格不留横向内边距：表格的文字和周围正文**对齐在同一列**上。
          // 留了的话，一张表看起来会比它上下那两段话缩进一点，像贴歪了
          th: ({ node: _node, ...props }: Md<'th'>) => (
            <th
              className="border-b border-border py-1 pr-3 text-left font-normal text-muted-foreground first:pl-0 last:pr-0"
              {...props}
            />
          ),
          td: ({ node: _node, ...props }: Md<'td'>) => (
            <td className="border-b border-border/50 py-1 pr-3 align-top first:pl-0 last:pr-0" {...props} />
          ),

          img: ({ node: _node, ...props }: Md<'img'>) => (
            <img className="max-w-full rounded-md" {...props} />
          ),

          hr: () => <hr className="border-border" />,
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  )
}

/**
 * 已经封口的那一块。**它只认 `text` 这一个 prop，所以在文本不再变之后一帧都不重渲染。**
 *
 * <p>把 `memo` 放在这里而不是放在 {@link Markdown} 上：`Markdown` 是给上层各处用的
 * 普通组件，它该不该记忆是调用方的事；而这里只有一个调用方，就是下面那个流的渲染 ——
 * 那个调用方能保证：同一个块，字符串只会长，不会改。
 */
const FrozenBlock = memo(function FrozenBlock({ text }: { text: string }) {
  return <Markdown>{text}</Markdown>
})

/**
 * 正在长出来的正文。
 *
 * <h2>为什么不直接把累积的全文交给 {@link Markdown}</h2>
 * 因为那样每一帧的代价都和"已经写了多长"成正比，加起来是平方级 ——
 * 一段长回复最该顺的时候反而最卡。
 *
 * <p>切成块之后，写完的部分是几个不会再变的字符串，只解析末尾那一块。
 * 切法见 {@link splitStreamingBlocks}，切错了也只是暂时的观感。
 *
 * <h2>为什么和落地后的渲染是同一个组件</h2>
 * 消息一落地就走 {@link Markdown}（整段一次解析）。两处共用同一套排版规则，
 * 所以"长出来的时候"和"长完之后"不会在视觉上跳一下 ——
 * 而那种一跳，人只会感觉"这个界面不太稳"。
 */
export function StreamingMarkdown({ text }: { text: string }) {
  const { blocks, tail } = splitStreamingBlocks(text)
  return (
    <div className="space-y-3 text-sm leading-6 text-muted-foreground [overflow-wrap:anywhere]">
      {blocks.map((block, index) => (
        // 只用下标当 key：块只会往后追加，同一个下标永远是同一块
        <FrozenBlock key={index} text={block} />
      ))}
      {tail !== '' && <Markdown>{tail}</Markdown>}
    </div>
  )
}
