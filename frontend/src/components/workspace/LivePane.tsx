import { ArrowDown } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

/**
 * 右栏那两种「一条不断长出内容的东西」共用的外壳：会话流、聊天室。
 *
 * <h2>为什么它们该共用一个外壳</h2>
 * 两者的结构一模一样：**上面一片会滚的内容，下面一条固定的输入**。不同的只有
 * "一条消息长什么样" —— 而那是**内容**，不是结构。
 *
 * <p>不抽出来的代价是具体的：第一版里我把这两个面板各写了一遍，于是会话那边
 * 漏了 `flex flex-col`，输入框直接掉到看不见的地方 —— 而同一个错误在聊天室那边
 * 因为凑巧写对了而没有暴露。**同一个结构写两遍，其中一遍必然写错。**
 *
 * <h2>它管住的四件事</h2>
 * <ol>
 *   <li><b>滚动区**只占剩余高度**</b>（`min-h-0 flex-1`）：少了 `min-h-0`，内容会把
 *       容器撑开而不是在里面滚，底下的输入框就被顶出去了。这是这套布局最容易写错的一点。</li>
 *   <li><b>新内容来了滚到底</b>，而且**只在用户本来就看着底部时才滚**。</li>
 *   <li><b>翻上去之后给一条回到底部的路</b>：不然他要一路拖回来，而新内容还在往下长。</li>
 *   <li><b>断线才说话</b>。正常时一个字都没有 —— 一个常绿的"已连接"只是在占地方。</li>
 * </ol>
 *
 * <h2>为什么用 MutationObserver，而不是"条数变了就滚"</h2>
 * 因为会话流里有一部分内容是**直接在原地长出来的**（模型正在打的那一段），
 * 消息条数根本不变 —— 按条数触发的话，用户会看着一句话卡在屏幕外打完。
 * 而 `useEffect` 不带依赖更糟：它每次渲染都滚，于是**用户一往上翻就被拽回底部**。
 *
 * <p>内容真的变了才滚，这是唯一能同时满足这两件事的判据。
 */
export function LivePane({
  disconnected,
  progress,
  footer,
  rail,
  children,
}: {
  /** 连接断了。正常时传 false —— **那时什么都不显示** */
  disconnected: boolean
  /** 断线提示的文案，比如"连接断了，正在重连…" */
  progress: string
  /** 贴在底部那条固定的东西（输入框） */
  footer?: React.ReactNode
  /**
   * 钉在右边缘的一条细导航（轮次导航），它**不跟着内容滚**。
   *
   * <p>之所以开这么一个口子，而不是让调用方自己在内容里 `sticky` 一下：
   * 滚动区是这一层的，调用方拿不到它的尺寸，也就摆不正"贴在它右边缘"这件事。
   */
  rail?: React.ReactNode
  /** 会滚的那一片 */
  children: React.ReactNode
}) {
  const scroller = useRef<HTMLDivElement>(null)
  const bottom = useRef<HTMLDivElement>(null)
  const [awayFromBottom, setAwayFromBottom] = useState(false)

  useEffect(() => {
    const node = scroller.current
    if (!node) {
      return
    }
    const toBottom = () => bottom.current?.scrollIntoView({ block: 'end' })

    // **第一批内容一定要落到最底下。**
    // 从前这里只判"离底部 40px 以内才滚"，而刚打开一条长会话时 scrollTop 是 0 ——
    // 那个条件从一开始就不成立，于是它一次都不滚：人被留在**最老的那一条**上，
    // 而且新内容还在源源不断地往下加，越拉越远。
    //
    // 补一个"第一趟无条件落底"：落过之后 scrollTop 就到底了，
    // 后面每一次都满足那个 40px 的条件，自然接得上
    let landed = false

    /**
     * 现在离底部有多远。
     *
     * <p>**内容变高变矮时也要重算**，不只是滚动的时候：折叠一段思考、收起一次 diff，
     * 都会改 {@code scrollHeight} 而**不产生滚动事件** —— 少了这一句，之前翻上去过一次
     * 的那个判断就永远留着，于是人明明已经在底部，"回到底部"还挂在那儿。
     */
    const measure = () => {
      setAwayFromBottom(node.scrollHeight - node.scrollTop - node.clientHeight > 80)
    }

    const observer = new MutationObserver(() => {
      measure()
      const nearBottom = node.scrollHeight - node.scrollTop - node.clientHeight < 40
      if (!landed || nearBottom) {
        landed = true
        toBottom()
      }
    })
    observer.observe(node, { childList: true, subtree: true, characterData: true })

    node.addEventListener('scroll', measure, { passive: true })

    return () => {
      observer.disconnect()
      node.removeEventListener('scroll', measure)
    }
  }, [])

  return (
    <div className="flex h-full flex-col">
      {disconnected && (
        <p role="status" className="shrink-0 px-4 py-2 text-xs text-loom-faint">
          {progress}
        </p>
      )}

      <div className="relative min-h-0 flex-1">
        {/* `min-h-0` 不能省：flex 子项的默认最小高度是内容高度，不写的话它会把容器撑开。
            `relative` 同理，而且这是个**安静**的坑：裁剪按**包含块**算，不按 DOM 祖先 ——
            这一层自己不是定位元素的话，内容里绝对定位的东西会绕过它往上找定位祖先，
            从找到的那一层往外撑。中栏那边就栽在这上面（见 {@code CodeView}） */}
        <div ref={scroller} className="relative h-full overflow-auto">
          {children}
          <div ref={bottom} />
        </div>

        {/* 翻上去之后才有这一颗。**它不自动消失也不闪** —— 断了的内容接上了、
            或者新东西又长出来了，它都还在这儿等着 */}
        {awayFromBottom && (
          <button
            type="button"
            onClick={() => bottom.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })}
            className="absolute bottom-3 left-1/2 z-20 flex -translate-x-1/2 items-center gap-1 rounded-full bg-card px-2.5 py-1 text-xs text-muted-foreground shadow-[0_0_0_1px_var(--border),0_4px_12px_-4px_rgb(0_0_0/60%)] transition-colors hover:text-foreground"
          >
            <ArrowDown className="size-3" />
            回到底部
          </button>
        )}

        {/* 轮次刻度**只在往上翻的时候出现**，和下面那颗「回到底部」同一个条件。
            它是"跳回某一轮"的入口 —— 人待在底部读最新内容时，它一点用都没有，
            却常驻在角落（贴着一列滚动条）看着像渲染坏了。
            位置也挪开 10px（right-2.5 而不是 0.5）：贴着滚动条时两样东西叠在一起，
            更像"滚动条坏了" */}
        {rail && awayFromBottom && <div className="absolute right-2.5 top-2 z-20">{rail}</div>}
      </div>

      {footer}
    </div>
  )
}
