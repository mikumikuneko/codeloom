import { useEffect, useLayoutEffect, useRef, useState } from 'react'

/** 菜单里的一项。 */
export interface MenuAction {
  label: string
  onSelect: () => void
  /** 破坏性动作。平时普通颜色，指上去才变红：该被认出来，但不该先喊 */
  danger?: boolean
  /** 在它上面画一条分组线 */
  divided?: boolean
  /** 右端那串快捷键提示；只是提示，键盘处理在调用方 */
  hint?: string
  /** 左侧的小图标；没有就不留空槽 */
  icon?: React.ReactNode
  /** 当前不可用。仍然显示，让人知道有这一项，只是现在不能按 */
  disabled?: boolean
}

/** 卡片和窗口边之间留的空隙。 */
const MARGIN = 8

/**
 * 右键菜单。
 *
 * 左上角就在右键那一点：菜单从指的地方长出来，不是从某个角落弹出。靠窗口右下角时
 * 会翻回窗口内，保证整张可点；翻转后不再"就在那儿"，但不翻的话那半张点不到。
 *
 * 菜单开着时点任何地方都应是关掉它，而不是点到下面的东西。铺一层透明全屏层最
 * 简单也最不会漏；挂在 window 上监听 click 要处理冒泡顺序和重复关闭。
 */
export function ContextMenu({
  x,
  y,
  actions,
  label,
  onClose,
}: {
  x: number
  y: number
  actions: MenuAction[]
  label: string
  onClose: () => void
}) {
  const card = useRef<HTMLDivElement>(null)
  const [at, setAt] = useState({ left: x, top: y })
  const [active, setActive] = useState(0)

  // 先按右键那一点摆好，量出真实尺寸，再夹回窗口。useLayoutEffect 在浏览器绘制
  // 之前跑，这个修正看不见；用 useEffect 会先闪一下再跳回来。
  useLayoutEffect(() => {
    const box = card.current?.getBoundingClientRect()
    if (!box) {
      return
    }
    setAt({
      left: Math.max(MARGIN, Math.min(x, window.innerWidth - box.width - MARGIN)),
      top: Math.max(MARGIN, Math.min(y, window.innerHeight - box.height - MARGIN)),
    })
  }, [x, y])

  // 拉开就把焦点收进卡片，否则方向键没有落脚点。
  useEffect(() => {
    card.current?.focus()
  }, [])

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : actions.length - 1
      setActive((current) => (current + step) % actions.length)
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      const action = actions[active]
      if (action !== undefined && action.disabled !== true) {
        action.onSelect()
        onClose()
      }
    }
  }

  return (
    <>
      <div
        className="fixed inset-0 z-40"
        onPointerDown={onClose}
        // 在别处再右键一次也是关掉它，同时挡掉浏览器自带的菜单。
        onContextMenu={(event) => {
          event.preventDefault()
          onClose()
        }}
      />
      <div
        ref={card}
        role="menu"
        tabIndex={-1}
        aria-label={label}
        style={{ left: at.left, top: at.top }}
        onKeyDown={onKeyDown}
        // 描边和阴影只有 shadow-float 一处画（1px 描边是它的第一层）：两套同时上
        // 迟早走岔。
        className="fixed z-50 min-w-44 rounded-lg bg-popover py-1 shadow-float outline-none"
      >
        {actions.map((action, index) => (
          <div key={action.label}>
            {action.divided && <div className="my-1 h-px bg-border" />}
            <button
              type="button"
              role="menuitem"
              disabled={action.disabled}
              onClick={() => {
                if (action.disabled === true) {
                  return
                }
                action.onSelect()
                onClose()
              }}
              // 鼠标划过和键盘走同一套 active 状态，免得"鼠标在这、方向键选的是另一个"。
              onPointerEnter={() => setActive(index)}
              className={`flex w-full items-center px-3 py-1.5 text-left text-sm transition-colors disabled:opacity-45 ${
                index === active ? 'bg-selected' : ''
              } ${action.danger && index === active ? 'text-destructive' : ''}`}
            >
              {action.icon && (
                <span className="mr-2 flex size-4 shrink-0 items-center justify-center">
                  {action.icon}
                </span>
              )}
              <span>{action.label}</span>
              {action.hint && (
                <span className="ml-auto pl-6 text-xs text-loom-faint">{action.hint}</span>
              )}
            </button>
          </div>
        ))}
      </div>
    </>
  )
}
