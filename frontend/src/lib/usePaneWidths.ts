import { useEffect, useState, type RefObject } from 'react'

import { readStorage, writeStorage } from '@/lib/storage'

/**
 * 工作区左右两栏的宽度，以及怎么改它们。
 *
 * 用像素而不是比例：文件树是一条导航（名字多长就要多宽），会话栏是一段可读文本
 * （行宽超过一定长度开始难读），两者都不该跟着窗口变。
 *
 * 中间那一片有下限：拖到极限只剩行号是有意留出的用法（两人同屏时把会话栏拉满、
 * 让代码退成一条边），所以下限只保证不拖成负数。宽度存 localStorage：这是这个
 * 人的习惯，不是项目属性，存丢只回默认值。
 */
interface PaneWidths {
  /** 往右拖为正、往左拖为负；符号由调用方定，因为两栏"变宽"的方向相反 */
  resize: (side: 'left' | 'right', delta: number) => void
  /** 双击把手回到默认。拖到别扭的位置后需要一个不用重拖的出口 */
  reset: () => void
  left: number
  right: number
}

/** 左栏默认宽度。它是一条导航，不该跟着窗口变胖。 */
const DEFAULT_LEFT = 256

/** 右栏默认宽度。一段可读文本的行宽，再宽开始费眼。 */
const DEFAULT_RIGHT = 480

/** 两栏各自的下限。再窄树里的文件名会被截掉，那就不如不给拖。 */
const MIN_LEFT = 160
const MIN_RIGHT = 280

/**
 * 中间那一片的下限：刚好放下行号和几列正文。
 *
 * 96 是按旧只读视图（`CodeLines`）量的：左右各 16px 内边距 + 行号列 40px + 右侧
 * 24px 间距。中栏换成 CodeMirror 后行号宽度随位数增长，这个数字偏保守——窄到
 * 这个程度仍能编辑，只是长行会很快需要横向滚动。
 */
const MIN_MIDDLE = 104

const STORAGE_KEY = 'codeloom.panes'

export function usePaneWidths(row: RefObject<HTMLElement | null>): PaneWidths {
  const [widths, setWidths] = useState<Pair>(remember)

  /** 这一行现在有多宽。每次现读，不缓存：窗口随时会被拉。 */
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
   * 窗口变化时重新夹一遍宽度。
   *
   * 不夹的话，窗口拉小后两栏按上次的像素待着，中间那一片被压成一条缝。挂在
   * mount 上也是故意的：localStorage 里的宽度可能来自更大的屏幕。
   *
   * refit 写在 effect 里，不抽出去也不包 useCallback：抽出去会每次渲染都产生新
   * 函数，effect 每次重挂监听；再配上每次 setState 产生新对象，就是无限渲染
   * （见 {@link fit} 里"没变就原样返回"）。
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
    writeStorage(STORAGE_KEY, JSON.stringify(widths))
  }, [widths])

  return { left: widths.left, right: widths.right, resize, reset }
}

interface Pair {
  left: number
  right: number
}

/**
 * 把一对宽度夹进当前窗口放得下的范围。
 *
 * 先夹左边，再用夹过的左边算右边上限：两个约束互相咬着，同时算可能得到总和
 * 超标组合。
 *
 * 窗口小到两个下限加中间那一片都放不下时，下限赢（宁可撑出去）：140px 的文件树
 * 没法用，横向溢出的窗口至少还看得出是什么。
 *
 * 宽度没变就返回原对象：resize 时会连续调用很多次，每次返回新对象会让 `setWidths`
 * 判定变了，触发再渲染与重挂监听，形成停不下来的循环。
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
  const raw = readStorage(STORAGE_KEY)
  if (!raw) {
    return fallback
  }
  try {
    const parsed = JSON.parse(raw) as Partial<Pair>
    return {
      left: typeof parsed.left === 'number' ? parsed.left : DEFAULT_LEFT,
      right: typeof parsed.right === 'number' ? parsed.right : DEFAULT_RIGHT,
    }
  } catch {
    // 存的东西坏了就回默认：一份布局偏好不值得为它报错。
    return fallback
  }
}
