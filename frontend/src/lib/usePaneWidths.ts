import { useEffect, useState, type RefObject } from 'react'

/**
 * 工作区左右两栏的宽度，以及怎么改它们。
 *
 * <h2>为什么是像素，不是比例</h2>
 * 因为这两栏要的东西**不跟着窗口变**：文件树是一条导航（名字多长它就要多宽），
 * 会话栏是一段可读文本（行宽超过一定长度就开始难读）。按比例分的话，
 * 换个大屏树会莫名其妙变胖，而它一个字也没多。
 *
 * <h2>为什么中间那一片有个下限，而且下限定得那么小</h2>
 * 拖到极限时中间只剩行号 —— 那是**有意留出来的**：两个人在同一个屏上干活时，
 * 把会话栏拉满、让代码退成一条边是很实际的用法。
 * 所以这里不是"防止用户拖坏"，而是"别让它拖成负数"。
 *
 * <h2>为什么宽度存 localStorage</h2>
 * 和"记住上次看的会话"同一条理由：这是**这个人的习惯**，不是项目的属性。
 * 存丢了只是回到默认值 —— 一个合理的退化。
 */
export interface PaneWidths {
  /** 往右拖是正数。往左拖是负数 —— **符号由调用方定**，因为两栏"变宽"的方向相反 */
  resize: (side: 'left' | 'right', delta: number) => void
  /** 双击把手：回到默认。拖到一个别扭的位置之后，人需要一个不用重拖的出口 */
  reset: () => void
  left: number
  right: number
}

/** 左栏默认宽度。它是一条导航，不该跟着窗口变胖。 */
export const DEFAULT_LEFT = 224

/** 右栏默认宽度。一段可读文本的行宽，再宽就开始费眼睛了。 */
export const DEFAULT_RIGHT = 416

/** 两栏各自的下限。再窄的话，树里一行文件名都要被截掉，那就不如不给拖。 */
const MIN_LEFT = 160
const MIN_RIGHT = 280

/**
 * 中间那一片的下限：**刚好放下行号和一点内容**。
 *
 * <p>算出来的：代码区左右各 16px 内边距 + 行号列 40px + 它右边 16px 间距 = 88，
 * 留几个像素的余量。所以"拖到头"的样子确实是「只剩行号」——
 * 这个数是照着 {@code CodeLines} 的实际排版量出来的，不是估的。
 */
const MIN_MIDDLE = 96

const STORAGE_KEY = 'codeloom.panes'

export function usePaneWidths(row: RefObject<HTMLElement | null>): PaneWidths {
  const [widths, setWidths] = useState<Pair>(remember)

  /** 这一行现在有多宽。**每次现读**，不缓存 —— 窗口随时会被拉。 */
  const available = () => row.current?.getBoundingClientRect().width ?? 0

  const resize = (side: 'left' | 'right', delta: number) => {
    const room = available()
    if (room <= 0) {
      return
    }
    setWidths((current) =>
      fit(
        side === 'left'
          ? { left: current.left + delta, right: current.right }
          : { left: current.left, right: current.right + delta },
        room,
      ),
    )
  }

  const reset = () => {
    setWidths(() => fit({ left: DEFAULT_LEFT, right: DEFAULT_RIGHT }, available()))
  }

  /**
   * 窗口变了要把宽度**重新夹一遍**。
   *
   * <p>不夹的话，把窗口拉小之后两栏会按上一次的像素原样待着，
   * 中间那一片被压成一条缝 —— 而它自己不会吭声。
   *
   * <p>挂在 mount 上也是故意的：localStorage 里存的那对宽度可能是上次在一个大屏上拖的，
   * 而这次是在小屏上打开的。
   *
   * <p>整个 refit 都写在 effect 里面，**不抽出去也不包 useCallback**：
   * 抽出去的话它会每次渲染都是一个新函数，effect 就每次渲染都要重挂一遍监听；
   * 而那种写法一旦配上"每次 setState 都产生新对象"，就是一圈永远转不完的渲染
   * （见 {@link fit} 里那句"没变就原样返回"）。
   */
  useEffect(() => {
    const refit = () => {
      const room = row.current?.getBoundingClientRect().width ?? 0
      if (room > 0) {
        setWidths((current) => fit(current, room))
      }
    }
    refit()
    window.addEventListener('resize', refit)
    return () => window.removeEventListener('resize', refit)
  }, [row])

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(widths))
    } catch {
      // 隐私模式下 localStorage 会抛。那不是错误，只是"记不住"而已
    }
  }, [widths])

  return { left: widths.left, right: widths.right, resize, reset }
}

interface Pair {
  left: number
  right: number
}

/**
 * 把一对宽度夹进"当前窗口放得下"的范围。
 *
 * <p>顺序是**先夹左边、再拿夹过的左边去算右边的上限** —— 两个约束是互相咬着的，
 * 同时算的话可能夹出一个总和超标的组合。
 *
 * <p>窗口小到两个下限加中间那一片都放不下时，**下限赢**（宁可撑出去）：
 * 一个 140px 的文件树是没法用的，而横向溢出的窗口至少还看得出是什么东西。
 *
 * <p>**宽度没变就把原对象还回去。** 这不是省一次渲染的小聪明：
 * 窗口 resize 的时候这个函数会被连着调很多次（还有 mount 那一次），
 * 而每次都回一个新对象的话，{@code setWidths} 每次都判定"变了" → 再渲染 →
 * effect 再挂一次监听 —— 那是一圈停不下来的渲染。
 */
function fit(widths: Pair, available: number): Pair {
  if (available <= 0) {
    return widths
  }
  const left = Math.max(MIN_LEFT, Math.min(widths.left, available - widths.right - MIN_MIDDLE))
  const right = Math.max(MIN_RIGHT, Math.min(widths.right, available - left - MIN_MIDDLE))
  return left === widths.left && right === widths.right ? widths : { left, right }
}

function remember(): Pair {
  const fallback: Pair = { left: DEFAULT_LEFT, right: DEFAULT_RIGHT }
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return fallback
    }
    const parsed = JSON.parse(raw) as Partial<Pair>
    return {
      left: typeof parsed.left === 'number' ? parsed.left : DEFAULT_LEFT,
      right: typeof parsed.right === 'number' ? parsed.right : DEFAULT_RIGHT,
    }
  } catch {
    // 存的东西坏了就回默认 —— 一份布局偏好不值得为它报错
    return fallback
  }
}
