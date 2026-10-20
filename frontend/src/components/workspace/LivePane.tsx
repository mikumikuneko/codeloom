import { ArrowDown } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

/**
 * 右栏两种「一条不断长出内容的东西」共用的外壳：会话流、聊天室。
 *
 * 两者结构一样：上面一片会滚的内容，下面一条固定的输入；不同的只有"一条消息长什么
 * 样"，那是内容不是结构。写两遍的代价是具体的：第一版里会话那边漏了
 * `flex flex-col`，输入框掉到看不见的地方，而聊天室因为凑巧写对没有暴露。
 *
 * 它管四件事：滚动区只占剩余高度（`min-h-0 flex-1`，少了 `min-h-0` 内容会把容器
 * 撑开，底部输入框被顶出去）；新内容来了滚到底，而且只在用户本来就看着底部时才滚；
 * 翻上去之后给一条回到底部的路；断线才说话，正常时一个字都没有。
 *
 * 用 `MutationObserver` 而不是"条数变了就滚"：会话流里有一部分内容直接在原地长
 * （模型正在打的那段），消息条数不变，按条数触发会让人看着一句话卡在屏幕外打完。
 * `useEffect` 不带依赖更糟：每次渲染都滚，用户一往上翻就被拽回底部。
 */
export function LivePane({
  disconnected,
  progress,
  footer,
  rail,
  visible = true,
  children,
}: {
  /** 连接断了。正常时传 false，那时什么都不显示 */
  disconnected: boolean
  /** 断线提示的文案，例如"连接断了，正在重连…" */
  progress: string
  /** 贴在底部那条固定的东西（输入框） */
  footer?: React.ReactNode
  /**
   * 这一栏现在看不看得见。
   *
   * 聊天室被标签页藏起来时仍挂着连接（否则收不到对方的字），但同时是
   * `display: none`，那种状态下 `scrollIntoView` 什么都不做。所以切回来的那一刻
   * 要再落一次底，否则人看到的是上一条而不是最新一条。
   */
  visible?: boolean
  /**
   * 钉在右边缘的一条细导航（轮次导航），不跟着内容滚。
   *
   * 之所以开这个口子而不让调用方在内容里 `sticky`：滚动区是这一层的，调用方拿不到
   * 它的尺寸，也就摆不正"贴在它右边缘"。
   */
  rail?: React.ReactNode
  /** 会滚的那一片 */
  children: React.ReactNode
}) {
  const scroller = useRef<HTMLDivElement>(null)
  const bottom = useRef<HTMLDivElement>(null)
  const [awayFromBottom, setAwayFromBottom] = useState(false)

  // 从藏起来变回看得见：补一次落底（见 visible 的注释）。
  useEffect(() => {
    if (visible) {
      bottom.current?.scrollIntoView({ block: 'end' })
    }
  }, [visible])

  useEffect(() => {
    const node = scroller.current
    if (!node) {
      return
    }
    const toBottom = () => bottom.current?.scrollIntoView({ block: 'end' })

    // 第一批内容一定要落到最底下。只判"离底部 40px 以内才滚"的话，刚打开长会话
    // 时 scrollTop 是 0，条件从一开始就不成立，一次都不滚：人被留在最老的一条上，
    // 而新内容还在往下加。第一趟无条件落底之后，后面每次都满足那个条件。
    let landed = false

    /**
     * 现在离底部有多远。
     *
     * 内容变高变矮时也要重算，不只是滚动时：折叠一段思考、收起一次 diff 都会改
     * `scrollHeight` 而不产生滚动事件。少了这一句，之前翻上去过一次的判断会永远
     * 留着，人明明已在底部，"回到底部"还挂着。
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
        {/* min-h-0 不能省：flex 子项的默认最小高度是内容高度，不写会把容器撑开。
            relative 同理，而且是个安静的坑：裁剪按包含块算，不按 DOM 祖先。这一层
            不是定位元素时，内容里绝对定位的东西会绕过它往上找定位祖先，从找到的那
            一层往外撑。中栏那边栽过（见 CodeView） */}
        <div ref={scroller} className="relative h-full overflow-auto">
          {children}
          <div ref={bottom} />
        </div>

        {/* 翻上去之后才有这一颗。它不自动消失也不闪：新内容接上了、又长出来了，
            它都在这儿等着 */}
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

        {/* 轮次刻度只在往上翻时出现，与「回到底部」同一条件。人待在底部读最新内容
            时它没有用，常驻在角落（贴着滚动条）看着像渲染坏了。位置挪开 10px：
            贴着滚动条时两样东西叠在一起，更像滚动条坏了 */}
        {rail && awayFromBottom && <div className="absolute right-2.5 top-2 z-20">{rail}</div>}
      </div>

      {footer}
    </div>
  )
}
