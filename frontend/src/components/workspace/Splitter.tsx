import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'

/** 键盘一次挪多少。给不能拖的人一条路 —— 和拖动是同一件事，只是入口不同。 */
const KEY_STEP = 16

/**
 * 两栏之间那条可拖的把手。
 *
 * <h2>它同时是那条分界线</h2>
 * 工作区中间那一片从前自带左右两条边框（"唯一自己带分界线的一栏"）。
 * 现在那两条线由把手来画 —— 因为拖动需要一个**够大的命中区**（1px 的线抓不住），
 * 而在 1px 的线旁边再放一个 6px 的透明条，就会出现"看得见的线"和"抓得住的条"
 * 不重合的两样东西：人对着线拖，线却不动。
 *
 * <h2>为什么按住时要把整页的光标换掉</h2>
 * 只在把手上设 `cursor` 的话，鼠标一划出去就变回箭头 —— 而这时候人还在拖。
 * 拖动期间那块区域光标是唯一在回答"现在能干什么"的东西。
 */
export function Splitter({
  label,
  onResize,
  onReset,
}: {
  label: string
  /** 往右拖给正数、往左拖给负数。**哪边变宽由调用方决定**（两栏的方向相反） */
  onResize: (delta: number) => void
  /** 双击回到默认宽度。拖到一个别扭的位置之后，人需要一个不用重拖的出口 */
  onReset: () => void
}) {
  const [dragging, setDragging] = useState(false)
  const last = useRef(0)

  useEffect(() => {
    if (!dragging) {
      return
    }
    const { body } = document
    const cursor = body.style.cursor
    const select = body.style.userSelect
    body.style.cursor = 'col-resize'
    // 拖动时整页禁掉选中：不然手一抖就会把旁边的文字刷成蓝色
    body.style.userSelect = 'none'
    return () => {
      body.style.cursor = cursor
      body.style.userSelect = select
    }
  }, [dragging])

  function grab(event: ReactPointerEvent<HTMLButtonElement>) {
    // 捕获指针：不捕获的话，手快一点划出这 6px 就丢了，而人还没松手
    event.currentTarget.setPointerCapture(event.pointerId)
    last.current = event.clientX
    setDragging(true)
  }

  function drag(event: ReactPointerEvent<HTMLButtonElement>) {
    if (!dragging) {
      return
    }
    onResize(event.clientX - last.current)
    last.current = event.clientX
  }

  return (
    <button
      type="button"
      // role=separator 是"可拖的分隔条"那个标准写法：读屏会念成"分隔条"而不是"按钮"，
      // 而左右方向键是它约定的操作方式
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      onPointerDown={grab}
      onPointerMove={drag}
      onPointerUp={() => setDragging(false)}
      onPointerCancel={() => setDragging(false)}
      onDoubleClick={onReset}
      onKeyDown={(event) => {
        if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
          event.preventDefault()
          onResize(event.key === 'ArrowRight' ? KEY_STEP : -KEY_STEP)
        }
      }}
      className="group relative w-1.5 shrink-0 cursor-col-resize"
    >
      <span
        className={`absolute inset-y-0 left-1/2 w-px -translate-x-1/2 transition-colors ${
          dragging ? 'bg-ring' : 'bg-border group-hover:bg-ring/60'
        }`}
      />
    </button>
  )
}
