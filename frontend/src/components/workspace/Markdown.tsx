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
 * `node` 是 HAST 节点对象，绝不能原样铺到 DOM 元素上：会变成
 * `node="[object Object]"` 属性并触发控制台警告。所以每个组件先把它摘掉，剩下的
 * 才是 DOM 属性。
 *
 * 摘掉的变量叫 `_node`：下划线表示"我知道它在这儿，故意不用"，不是忘了删。
 */
type Md<T extends keyof React.JSX.IntrinsicElements> = ComponentProps<T> & { node?: unknown }

/**
 * 一个围栏代码块的颜色。
 *
 * 它不负责盒子：盒子由外层 `pre` 给（`bg-secondary` 和圆角都在那儿），这里只吐
 * 一串上色的 `span`。
 *
 * 认不出语言或高亮器未就绪时原样吐出：代码是正文，没有颜色也得能读；半套高亮
 * 比没有更像坏了。
 */
function FencedCode({
  code,
  language,
  parts,
}: {
  code: string
  language: string | null
  /** 切好的三段。切分在 {@link Markdown} 里做：判"是不是还在写"要看整段正文 */
  parts: CodeParts
}) {
  // 滚到了才上色：一段对话里可能几十个代码块，一次只看得到一两个；没上色时照常
  // 按等宽排好，位置和高度都对。
  const anchor = useRef<HTMLSpanElement>(null)
  const seen = useViewportHighlighting(anchor, language !== null)

  // 只把写完的行交给高亮器，这是流式代价的关键：那串文本在有行写完之前按值相等
  // （最后一行在长，但在 partial 里），effect 依赖不变，高亮器不会被叫醒。
  const tokens = useHighlighted(parts.complete, language, seen)

  return (
    <span ref={anchor}>
      {/* 还没高亮时整段原样吐出，包括没写完的行：那时它本来也没颜色可掉 */}
      {tokens === null ? (
        code
      ) : (
        <>
          <HighlightedTokens tokens={tokens} />
          {parts.partial}
          {/* 解析器补的换行照旧还回去：它在 pre 里是最后那个空行，少了整块会矮
              一行，"长出来时"与"长完之后"会跳一下 */}
          {parts.trailingBreak}
        </>
      )}
    </span>
  )
}

/**
 * 已经算好的那些行。
 *
 * 必须是 memo：上面那个组件每一帧都会重渲染（正文在长），而 token 数组在两次
 * 分词之间是同一个引用。不 memo 的话，React 每帧都要 diff 几百上千个 `span`；
 * memo 之后整棵子树跳过。`FrozenBlock` 也用了同一招。
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
 * 复制按钮读这个 `pre` 的 `textContent`，所以复制到的与屏幕上看见的逐字相同。
 * 传一份原始字符串进来的话，两份迟早不一致（高亮把 token 拆成 `span`，以后还
 * 可能加行号）。
 *
 * 按钮平时不显形，鼠标移到代码块上或键盘 Tab 到它才出来：一段对话里好几个代码块，
 * 每个都挂常驻按钮，那一栏就变成工具栏了。
 */
function CodeShell({ node: _node, children, ...props }: Md<'pre'>) {
  const body = useRef<HTMLPreElement>(null)
  const [wrapped, toggleWrap] = useCodeWrap()

  return (
    <div className="group/code relative">
      {/* 两个控件平时都不显形，鼠标移到这块代码上或键盘 Tab 到它们才出来。
          十几个代码块各挂一排常驻按钮会把这一栏变成工具栏 */}
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
      {/* 不换行时右边多留一点：长行横着滚过时要能从按钮底下穿过 */}
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
 * 模型输出的就是 markdown：粗体、列表、代码块。原样铺出来，人看到的是一堆星号和
 * 反引号，那不是它说的话，是客户端里该被渲染掉的记号。
 *
 * 中文加粗要单独补一条语法规则，见 {@link remarkCjkStrong}：`**结论：**后面接着写`
 * 按 CommonMark 原规则解析不出来，会原样显示星号。
 *
 * 样式写细是因为默认 markdown 样式给文章用（大标题、宽松段距），而这里是一段工作
 * 对话：行距、字号、间距都得跟着周围走，否则带列表的回答会显得比它本身重。
 *
 * 三档排版：`chat` 收着，`document` 分级，另有思考过程那档次要内容。
 */
export function Markdown({
  children,
  variant = 'chat',
}: {
  children: string
  /**
   * - `chat`（默认）：工作对话。标题压到和正文一个量级。
   * - `document`：一份文档。标题真的分级；中栏打开 .md 时用这个。
   */
  variant?: 'chat' | 'document'
}) {
  const document = variant === 'document'

  /**
   * 标题。
   *
   * 文档档的上间距给在 padding 上而不是 margin 上：外层用 `space-y-*`，那也是
   * `margin-top`，同属性上的 utility 会把它压过去；在这里写 `mt-6` 是一个不生效
   * 的类，而"看起来写了、实际没有"最容易骗过下一个人。
   *
   * @param own 元素自己带来的 class。react-markdown 偶尔会塞一个（脚注标题的
   *            `sr-only` 是插件里硬编码的），必须与我们的类拼在一起，见下面对 h1
   *            的说明
   */
  const heading = (level: 1 | 2 | 3 | 4 | 5 | 6, own?: string) => {
    const ours = document
      ? `first:pt-0 ${['pt-6 text-xl', 'pt-5 text-lg', 'pt-4 text-base', 'pt-3', 'pt-3', 'pt-3'][level - 1]} font-medium`
      : 'font-medium'
    return own ? `${ours} ${own}` : ours
  }

  // 这一整段正文。块级代码切分要用它判"最后一行写完没有"，见 splitPartialLine。
  const source = children
  return (
    // overflow-wrap：长 URL 或长路径不许把这一栏撑破，撑破的话整个三栏布局都会
    // 被顶开，而不只是一行长出去。
    //
    // 正文块间距 16：段、表、代码块之间要一眼看出是两块；12 时小标题会和下一段
    // 黏在一起。
    <div
      className={`${
        document ? 'space-y-4 text-sm leading-7' : 'space-y-3 text-sm leading-6'
      } [overflow-wrap:anywhere]`}
    >
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkCjkStrong]}
        components={{
          // 段落间距由上面的 space-y-4 给，段自己不留。
          p: ({ node: _node, ...props }: Md<'p'>) => <p className="whitespace-pre-wrap break-words" {...props} />,

          // 标题档位见上面的 heading()：对话里压平，文档里分级。
          //
          // className 要先从 props 摘出来再铺：JSX 里同名属性后写的整个换掉前面的
          // （不是合并）。写成 className={heading(1)} {...props}，只要 props 里有
          // className，我们的样式当场被顶掉，而且不报错。普通标题不带 class，带的
          // 那天（脚注标题的 sr-only）才会静默失效。
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

          // 行内代码用一点底色和极淡的边从周围分出来：只靠底色的话，在本来就有
          // 底色的引用块里会沉进去。
          code: ({ className, children }: Md<'code'>) => {
            const language = languageOfFence((className ?? '').replace('language-', ''))
            const text = String(children)
            // 是不是块级：有语言标记，或内容里有换行；两者都没有的一律当行内。
            if (language !== null || text.includes('\n')) {
              // 切分在这里做，不在 FencedCode 里：判这块是不是还在写要看整段正文
              // （见 splitPartialLine），只有这一层拿得到。
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

          // 块级代码：外壳负责盒子和复制按钮，FencedCode 负责颜色。分两层是因为
          // 高亮要按行拿 token（见 highlight.ts），而盒子只需包一层。
          pre: CodeShell,

          // 链接：静止时虚线，指上去才变实线。实线在静止时等于喊"点我"，而回答里
          // 的链接常常只是引用出处。underline-offset 让下划线离开字身，不挡中文
          // 下半部分。
          a: ({ node: _node, ...props }: Md<'a'>) => (
            <a
              className="underline decoration-dotted underline-offset-[3px] transition-colors hover:decoration-solid hover:text-foreground"
              target="_blank"
              rel="noreferrer"
              {...props}
            />
          ),

          // 引用块：一条左边的线。
          blockquote: ({ node: _node, ...props }: Md<'blockquote'>) => (
            <blockquote className="border-l-2 border-border pl-3 text-muted-foreground" {...props} />
          ),

          // 表格：列多了才横滚。两三列铺满宽度更好读（单元格自己换行）；四五列
          // 以上保持自然宽度并滚动，硬塞进窄栏会让每个单元格挤成一个词一行。
          table: ({ node: _node, ...props }: Md<'table'>) => (
            <div className="max-w-full overflow-x-auto">
              <table
                className="w-full border-collapse text-xs has-[th:nth-child(4)]:w-max has-[th:nth-child(4)]:max-w-none"
                {...props}
              />
            </div>
          ),
          // 首尾两格不留横向内边距：表格文字与周围正文对齐在同一列；留了的话，
          // 整张表会像比上下两段缩进了一点。
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

          // 分隔线是模型自己写的 ---（如正文与"来源："之间）：保留，但上下留白、
          // 颜色压暗；紧贴字的 1px 线看起来像界面画错了。
          hr: () => <hr className="my-4 border-t border-border/70" />,
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  )
}

/**
 * 已经封口的那一块。它只认 `text` 一个 prop，所以在文本不再变之后一帧都不重渲染。
 *
 * `memo` 放在这里而不是 {@link Markdown} 上：Markdown 是给上层各处用的普通组件，
 * 该不该记忆是调用方的事；这里只有一个调用方（下面那个流的渲染），它能保证同一个
 * 块字符串只会长、不会改。
 */
const FrozenBlock = memo(function FrozenBlock({ text }: { text: string }) {
  return <Markdown>{text}</Markdown>
})

/**
 * 正在长出来的正文。
 *
 * 不把累积全文直接交给 {@link Markdown}：那样每帧代价与已写长度成正比，加起来是
 * 平方级，长回复最该顺的时候反而最卡。切成块之后，写完的部分是不再变的字符串，
 * 只解析末尾一块。切法见 {@link splitStreamingBlocks}，切错了也只是暂时的观感。
 *
 * 落地后的渲染用同一个 Markdown（整段一次解析），两处共用排版规则，"长出来时"和
 * "长完之后"不会视觉上跳一下。
 */
export function StreamingMarkdown({ text }: { text: string }) {
  const { blocks, tail } = splitStreamingBlocks(text)
  return (
    <div className="space-y-3 text-sm leading-6 text-muted-foreground [overflow-wrap:anywhere]">
      {blocks.map((block, index) => (
        // 只用下标当 key：块只会往后追加，同一个下标永远是同一块。
        <FrozenBlock key={index} text={block} />
      ))}
      {tail !== '' && <Markdown>{tail}</Markdown>}
    </div>
  )
}
