import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'

/** 键盘一次挪多少。给不能拖的人一条路：与拖动是同一件事，只是入口不同。 */
const KEY_STEP = 16

/**
 * 两栏之间可拖的把手。
 *
 * 它同时画那条分界线：拖动需要够大的命中区（1px 的线抓不住），在线旁再放一个
 * 透明条会让"看得见的线"和"抓得住的条"不重合，人对着线拖，线却不动。
 *
 * 按住时换掉整页光标：只在把手上设 `cursor` 的话，鼠标一划出去就变回箭头，而人
 * 还在拖。
 */
export function Splitter({
  label,
  onResize,
  onReset,
}: {
  label: string
  /** 往右拖给正数、往左拖给负数。哪边变宽由调用方决定（两栏方向相反） */
  onResize: (delta: number) => void
  /** 双击回到默认宽度。拖到别扭的位置后需要一个不用重拖的出口 */
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
    // 拖动时整页禁掉选中，否则手一抖会把旁边的文字刷成蓝色。
    body.style.userSelect = 'none'
    return () => {
      body.style.cursor = cursor
      body.style.userSelect = select
    }
  }, [dragging])

  function grab(event: ReactPointerEvent<HTMLButtonElement>) {
    // 捕获指针：不捕获的话，手快一点划出这 6px 就丢了，而人还没松手。
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
      // role=separator 是"可拖分隔条"的标准写法：读屏念成"分隔条"而不是"按钮"，
      // 左右方向键是它约定的操作方式。
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
